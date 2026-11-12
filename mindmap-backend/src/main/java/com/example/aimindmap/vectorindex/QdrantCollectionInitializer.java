package com.example.aimindmap.vectorindex;

import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Collections.Distance;
import io.qdrant.client.grpc.Collections.PayloadIndexParams;
import io.qdrant.client.grpc.Collections.PayloadSchemaInfo;
import io.qdrant.client.grpc.Collections.PayloadSchemaType;
import io.qdrant.client.grpc.Collections.VectorParams;
import io.qdrant.client.grpc.Points.WriteOrderingType;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 启动时确保向量集合与四个 payload 索引存在（幂等：已存在则跳过）。
 * 只操作 app.qdrant.collection 指定的集合，不触碰库内其他项目的集合。
 *
 * <p>事故 A-004 处置 B：多实例并发冷启动时"先查再建"会有一方收到 gRPC
 * ALREADY_EXISTS，此类异常按"其它实例已建好"处理（视为成功、继续启动），
 * 其余异常保持原有失败语义（启动失败）。判定一律用 gRPC 状态码，不匹配错误文案。
 */
@Component
@Order(0)
public class QdrantCollectionInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(QdrantCollectionInitializer.class);

    private static final Map<String, PayloadSchemaType> REQUIRED_INDEXES = new LinkedHashMap<>();

    static {
        REQUIRED_INDEXES.put("user_id", PayloadSchemaType.Integer);
        REQUIRED_INDEXES.put("resource_type", PayloadSchemaType.Keyword);
        REQUIRED_INDEXES.put("source_id", PayloadSchemaType.Keyword);
        REQUIRED_INDEXES.put("status", PayloadSchemaType.Keyword);
    }

    private final QdrantClient qdrantClient;
    private final QdrantProperties properties;

    public QdrantCollectionInitializer(QdrantClient qdrantClient, QdrantProperties properties) {
        this.qdrantClient = qdrantClient;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        String collection = properties.getCollection();
        Duration timeout = Duration.ofSeconds(properties.getCallTimeoutSeconds());

        boolean exists = Boolean.TRUE.equals(qdrantClient.collectionExistsAsync(collection, timeout).get());
        if (exists) {
            log.info("向量集合已存在，跳过创建: collection={}", collection);
        } else {
            createCollectionToleratingConcurrentCreate(collection, timeout);
        }

        Map<String, PayloadSchemaInfo> existingIndexes =
                qdrantClient.getCollectionInfoAsync(collection, timeout).get().getPayloadSchemaMap();
        for (Map.Entry<String, PayloadSchemaType> required : REQUIRED_INDEXES.entrySet()) {
            String field = required.getKey();
            if (existingIndexes.containsKey(field)) {
                log.debug("payload 索引已存在，跳过: collection={} field={}", collection, field);
                continue;
            }
            try {
                qdrantClient.createPayloadIndexAsync(
                        collection,
                        field,
                        required.getValue(),
                        PayloadIndexParams.newBuilder().build(),
                        true,
                        WriteOrderingType.Strong,
                        timeout).get();
                log.info("payload 索引创建成功: collection={} field={} type={}",
                        collection, field, required.getValue());
            } catch (ExecutionException e) {
                if (!isAlreadyExists(e.getCause())) {
                    log.error("payload 索引创建失败，启动终止: collection={} field={} type={}",
                            collection, field, required.getValue(), e);
                    throw e;
                }
                log.info("payload 索引并发创建冲突，已由其它实例建好，本实例继续启动: collection={} field={} type={}",
                        collection, field, required.getValue());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("创建 payload 索引等待被中断，启动终止: collection={} field={}", collection, field, e);
                throw e;
            }
        }
    }

    /**
     * 创建集合；若因并发竞争收到 gRPC ALREADY_EXISTS，则复查存在性后视为成功继续启动（A-004 处置 B）。
     * 其余异常保持原失败语义：log.error 带上下文与堆栈后原样抛出，让应用启动失败。
     */
    private void createCollectionToleratingConcurrentCreate(String collection, Duration timeout)
            throws Exception {
        VectorParams vectorParams = VectorParams.newBuilder()
                .setSize(properties.getVectorDimension())
                .setDistance(Distance.Cosine)
                .build();
        try {
            qdrantClient.createCollectionAsync(collection, vectorParams, timeout).get();
            log.info("向量集合创建成功: collection={} dimension={} distance=COSINE",
                    collection, properties.getVectorDimension());
            return;
        } catch (ExecutionException e) {
            if (!isAlreadyExists(e.getCause())) {
                log.error("向量集合创建失败，启动终止: collection={} dimension={}",
                        collection, properties.getVectorDimension(), e);
                throw e;
            }
            log.info("向量集合并发创建冲突（ALREADY_EXISTS），已由其它实例建好，本实例继续启动: collection={} dimension={}",
                    collection, properties.getVectorDimension());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("创建向量集合等待被中断，启动终止: collection={}", collection, e);
            throw e;
        }
        boolean existsAfterRace;
        try {
            existsAfterRace = Boolean.TRUE.equals(qdrantClient.collectionExistsAsync(collection, timeout).get());
        } catch (Exception recheckException) {
            log.error("向量集合并发创建冲突后复查存在性失败，启动终止: collection={}", collection, recheckException);
            throw recheckException;
        }
        if (!existsAfterRace) {
            log.error("向量集合创建返回 ALREADY_EXISTS 但复查不存在，启动终止: collection={} dimension={}",
                    collection, properties.getVectorDimension());
            throw new IllegalStateException(
                    "集合创建返回 ALREADY_EXISTS 但复查不存在: collection=" + collection);
        }
    }

    /** 用 gRPC 状态码判定"资源已存在"，不匹配错误文案（文案随 Qdrant 版本变化，脆弱）。 */
    private static boolean isAlreadyExists(Throwable cause) {
        return cause instanceof StatusRuntimeException statusRuntimeException
                && statusRuntimeException.getStatus().getCode() == Status.Code.ALREADY_EXISTS;
    }
}
