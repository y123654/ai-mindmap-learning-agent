package com.example.aimindmap.integration;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.aimindmap.ai.AiGateway;
import com.example.aimindmap.mindmap.SyncEngine;
import com.example.aimindmap.vectorindex.QdrantVectorGateway;
import com.google.common.util.concurrent.Futures;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Collections.CollectionInfo;
import io.qdrant.client.grpc.Collections.PayloadSchemaInfo;
import io.qdrant.client.grpc.Collections.PayloadSchemaType;
import java.time.Duration;
import java.util.Map;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 阶段四 4-4：集成测试三层外部依赖全部 mock（不连真实 Qdrant、不调真实 Embedding API）。
 *
 * <p>三个 mock 用 static final 单例：标准 @SpringBootTest 与手工起的多个应用上下文
 * （H1-2/H1-5）拿到的都是同一实例，因此调用计数可以跨上下文累计（H1-5 双实例断言依赖这一点）。
 * bean 方法名故意与生产 bean 不同并标 @Primary，避免 bean definition 覆盖。
 */
@TestConfiguration
public class MqIntegrationMocks {

    public static final QdrantClient QDRANT_CLIENT = mock(QdrantClient.class);
    public static final QdrantVectorGateway GATEWAY = mock(QdrantVectorGateway.class);
    public static final EmbeddingModel EMBEDDING_MODEL = mock(EmbeddingModel.class);
    /**
     * 阶段六-1：同步链路 IT 用 SyncEngine 替身（不调真实 LLM、不跑真实解析/校验链）。
     * static 单例：手工启动的多个应用上下文拿到同一实例，双实例只执行一次的计数断言依赖这一点。
     */
    public static final SyncEngine SYNC_ENGINE = mock(SyncEngine.class);
    /**
     * 阶段六-2：出题链路 IT 用 AiGateway 替身（不调真实 LLM）。
     * static 单例：与其它替身一致，多上下文拿到同一实例，便于跨轮次打桩与计数。
     */
    public static final AiGateway AI_GATEWAY = mock(AiGateway.class);
    /**
     * 阶段六-3：摘要链路 IT 用 Spring AI 原生 ChatModel 替身（不调真实 LLM）。
     * static 单例：手工启动的多个应用上下文拿到同一实例，双实例只调用一次的计数断言依赖这一点。
     */
    public static final ChatModel CHAT_MODEL = mock(ChatModel.class);

    static {
        // QdrantCollectionInitializer 启动期会直接调用 QdrantClient：
        // 集合视为已存在，并把四个 payload 索引全部报为已建，保证无真实 Qdrant 也能启动。
        PayloadSchemaInfo keywordIndex = PayloadSchemaInfo.newBuilder()
                .setDataType(PayloadSchemaType.Keyword)
                .build();
        CollectionInfo collectionInfo = CollectionInfo.newBuilder()
                .putAllPayloadSchema(Map.of(
                        "user_id", keywordIndex,
                        "resource_type", keywordIndex,
                        "source_id", keywordIndex,
                        "status", keywordIndex))
                .build();
        when(QDRANT_CLIENT.collectionExistsAsync(anyString(), any(Duration.class)))
                .thenReturn(Futures.immediateFuture(true));
        when(QDRANT_CLIENT.getCollectionInfoAsync(anyString(), any(Duration.class)))
                .thenReturn(Futures.immediateFuture(collectionInfo));
    }

    @Bean
    @Primary
    public QdrantClient mockQdrantClient() {
        return QDRANT_CLIENT;
    }

    @Bean
    @Primary
    public QdrantVectorGateway mockQdrantVectorGateway() {
        return GATEWAY;
    }

    @Bean
    @Primary
    public EmbeddingModel mockEmbeddingModel() {
        return EMBEDDING_MODEL;
    }

    @Bean
    @Primary
    public SyncEngine mockSyncEngine() {
        return SYNC_ENGINE;
    }

    @Bean
    @Primary
    public AiGateway mockAiGateway() {
        return AI_GATEWAY;
    }

    @Bean
    @Primary
    public ChatModel mockChatModel() {
        return CHAT_MODEL;
    }
}
