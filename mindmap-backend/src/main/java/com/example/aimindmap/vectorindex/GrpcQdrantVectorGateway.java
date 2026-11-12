package com.example.aimindmap.vectorindex;

import io.qdrant.client.PointIdFactory;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.VectorsFactory;
import io.qdrant.client.grpc.Common.Filter;
import io.qdrant.client.grpc.Common.PointId;
import io.qdrant.client.grpc.Points.GetPoints;
import io.qdrant.client.grpc.Points.ScrollPoints;
import io.qdrant.client.grpc.Points.PointStruct;
import io.qdrant.client.grpc.Points.ScoredPoint;
import io.qdrant.client.grpc.Points.SearchPoints;
import io.qdrant.client.grpc.Points.WithVectorsSelector;
import io.qdrant.client.grpc.Points.WithPayloadSelector;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** 基于 Qdrant 官方 Java 客户端 gRPC 的读写实现。 */
@Component
public class GrpcQdrantVectorGateway implements QdrantVectorGateway {

    private final QdrantClient qdrantClient;
    private final QdrantProperties properties;

    public GrpcQdrantVectorGateway(QdrantClient qdrantClient, QdrantProperties properties) {
        this.qdrantClient = qdrantClient;
        this.properties = properties;
    }

    @Override
    public void upsert(String collection, VectorPoint point) {
        PointStruct struct = PointStruct.newBuilder()
                .setId(PointIdFactory.id(point.pointId()))
                .setVectors(VectorsFactory.vectors(point.vector()))
                .putAllPayload(QdrantPayloads.toPayload(point.data()))
                .build();
        Duration timeout = Duration.ofSeconds(properties.getCallTimeoutSeconds());
        try {
            qdrantClient.upsertAsync(collection, List.of(struct), timeout).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Qdrant 写入被中断", e);
        } catch (Exception e) {
            throw new IllegalStateException("Qdrant 写入失败", e);
        }
    }

    @Override
    public void delete(String collection, UUID pointId) {
        Duration timeout = Duration.ofSeconds(properties.getCallTimeoutSeconds());
        try {
            qdrantClient.deleteAsync(collection, List.of(PointIdFactory.id(pointId)), timeout).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Qdrant 删除被中断", e);
        } catch (Exception e) {
            throw new IllegalStateException("Qdrant 删除失败", e);
        }
    }

    @Override
    public List<RetrievedPoint> search(String collection,
                                       Filter filter,
                                       float[] queryVector,
                                       int limit) {
        List<Float> floats = new ArrayList<>();
        for (float value : queryVector) {
            floats.add(value);
        }
        SearchPoints request = SearchPoints.newBuilder()
                .setCollectionName(collection)
                .setFilter(filter)
                .addAllVector(floats)
                .setLimit(limit)
                .setWithPayload(WithPayloadSelector.newBuilder().setEnable(true).build())
                .build();
        Duration timeout = Duration.ofSeconds(properties.getCallTimeoutSeconds());
        try {
            List<ScoredPoint> points = qdrantClient.searchAsync(request, timeout).get();
            List<RetrievedPoint> result = new ArrayList<>();
            for (ScoredPoint point : points) {
                UUID pointId = UUID.fromString(point.getId().getUuid());
                result.add(new RetrievedPoint(pointId, point.getScore(),
                        QdrantPayloads.toJavaMap(point.getPayloadMap())));
            }
            return result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Qdrant 检索被中断", e);
        } catch (Exception e) {
            throw new IllegalStateException("Qdrant 检索失败", e);
        }
    }

    @Override
    public Optional<Map<String, Object>> readPayload(String collection, UUID pointId) {
        GetPoints request = GetPoints.newBuilder()
                .setCollectionName(collection)
                .addIds(PointIdFactory.id(pointId))
                .setWithPayload(WithPayloadSelector.newBuilder().setEnable(true).build())
                .setWithVectors(WithVectorsSelector.newBuilder().setEnable(false).build())
                .build();
        Duration timeout = Duration.ofSeconds(properties.getCallTimeoutSeconds());
        try {
            List<io.qdrant.client.grpc.Points.RetrievedPoint> points =
                    qdrantClient.retrieveAsync(request, timeout).get();
            if (points.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(QdrantPayloads.toJavaMap(points.get(0).getPayloadMap()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Qdrant 读取被中断", e);
        } catch (Exception e) {
            throw new IllegalStateException("Qdrant 读取失败", e);
        }
    }

    @Override
    public List<UUID> listPointIds(String collection, Filter filter) {
        List<UUID> result = new ArrayList<>();
        PointId offset = null;
        Duration timeout = Duration.ofSeconds(properties.getCallTimeoutSeconds());
        try {
            while (true) {
                ScrollPoints.Builder builder = ScrollPoints.newBuilder()
                        .setCollectionName(collection)
                        .setFilter(filter)
                        .setLimit(256)
                        .setWithPayload(WithPayloadSelector.newBuilder().setEnable(false).build())
                        .setWithVectors(WithVectorsSelector.newBuilder().setEnable(false).build());
                if (offset != null) {
                    builder.setOffset(offset);
                }
                var response = qdrantClient.scrollAsync(builder.build(), timeout).get();
                for (io.qdrant.client.grpc.Points.RetrievedPoint point : response.getResultList()) {
                    result.add(UUID.fromString(point.getId().getUuid()));
                }
                PointId nextOffset = response.getNextPageOffset();
                if (nextOffset.equals(PointId.getDefaultInstance())
                        || response.getResultCount() == 0) {
                    return result;
                }
                offset = nextOffset;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Qdrant 滚动枚举被中断", e);
        } catch (Exception e) {
            throw new IllegalStateException("Qdrant 滚动枚举失败", e);
        }
    }
}
