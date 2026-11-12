package com.example.aimindmap.vectorindex;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.util.concurrent.Futures;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Collections.CollectionInfo;
import io.qdrant.client.grpc.Collections.CollectionOperationResponse;
import io.qdrant.client.grpc.Collections.PayloadSchemaInfo;
import io.qdrant.client.grpc.Collections.PayloadSchemaType;
import io.qdrant.client.grpc.Collections.VectorParams;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 事故 A-004 处置 B：QdrantCollectionInitializer 并发创建幂等化纯单测（mock QdrantClient，不连真实 Qdrant）。
 * 核心用例：创建集合时收到 gRPC ALREADY_EXISTS 必须视为成功、不抛异常、启动流程继续。
 */
class QdrantCollectionInitializerTest {

    private static final String COLLECTION = "unit_test_collection_1024";

    private final QdrantClient qdrantClient = mock(QdrantClient.class);
    private final QdrantProperties properties = new QdrantProperties();
    private final QdrantCollectionInitializer initializer =
            new QdrantCollectionInitializer(qdrantClient, properties);

    @BeforeEach
    void setUp() {
        properties.setCollection(COLLECTION);
        properties.setVectorDimension(1024);
    }

    /** 四个 payload 索引全部报为已存在，让索引环节不触发创建，聚焦集合创建逻辑。 */
    private void stubAllPayloadIndexesExist() {
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
        when(qdrantClient.getCollectionInfoAsync(anyString(), any(Duration.class)))
                .thenReturn(Futures.immediateFuture(collectionInfo));
    }

    @Test
    void createsCollectionWhenAbsent() throws Exception {
        when(qdrantClient.collectionExistsAsync(anyString(), any(Duration.class)))
                .thenReturn(Futures.immediateFuture(false));
        when(qdrantClient.createCollectionAsync(anyString(), any(VectorParams.class), any(Duration.class)))
                .thenReturn(Futures.immediateFuture(CollectionOperationResponse.getDefaultInstance()));
        stubAllPayloadIndexesExist();

        assertThatCode(() -> initializer.run(null)).doesNotThrowAnyException();

        verify(qdrantClient, times(1))
                .createCollectionAsync(anyString(), any(VectorParams.class), any(Duration.class));
        verify(qdrantClient, never())
                .createPayloadIndexAsync(anyString(), anyString(), any(), any(), any(), any(), any());
    }

    /** A-004 核心用例：并发竞争下创建集合收到 ALREADY_EXISTS，必须不抛异常并复查存在性后继续启动。 */
    @Test
    void treatsAlreadyExistsOnCreateAsSuccessAndRechecksExistence() {
        when(qdrantClient.collectionExistsAsync(anyString(), any(Duration.class)))
                .thenReturn(Futures.immediateFuture(false), Futures.immediateFuture(true));
        when(qdrantClient.createCollectionAsync(anyString(), any(VectorParams.class), any(Duration.class)))
                .thenReturn(Futures.immediateFailedFuture(new StatusRuntimeException(
                        Status.ALREADY_EXISTS.withDescription(
                                "Wrong input: Collection '" + COLLECTION + "' already exists!"))));
        stubAllPayloadIndexesExist();

        assertThatCode(() -> initializer.run(null)).doesNotThrowAnyException();

        // 首查(不存在) + 冲突后复查(存在) 共两次
        verify(qdrantClient, times(2)).collectionExistsAsync(anyString(), any(Duration.class));
    }

    /** 防止把修复写成"吞掉一切异常"：非 ALREADY_EXISTS（如 UNAVAILABLE）仍必须让启动失败。 */
    @Test
    void rethrowsNonAlreadyExistsCreateFailure() {
        when(qdrantClient.collectionExistsAsync(anyString(), any(Duration.class)))
                .thenReturn(Futures.immediateFuture(false));
        when(qdrantClient.createCollectionAsync(anyString(), any(VectorParams.class), any(Duration.class)))
                .thenReturn(Futures.immediateFailedFuture(new StatusRuntimeException(
                        Status.UNAVAILABLE.withDescription("io exception"))));

        assertThatThrownBy(() -> initializer.run(null))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(StatusRuntimeException.class)
                .satisfies(e -> assertThat(((StatusRuntimeException) e.getCause()).getStatus().getCode())
                        .isEqualTo(Status.Code.UNAVAILABLE));
    }

    /** 同类竞争也覆盖 payload 索引：并发建索引收到 ALREADY_EXISTS 视为成功，四个字段全部继续。 */
    @Test
    void treatsPayloadIndexAlreadyExistsAsSuccess() {
        when(qdrantClient.collectionExistsAsync(anyString(), any(Duration.class)))
                .thenReturn(Futures.immediateFuture(true));
        when(qdrantClient.getCollectionInfoAsync(anyString(), any(Duration.class)))
                .thenReturn(Futures.immediateFuture(CollectionInfo.getDefaultInstance()));
        when(qdrantClient.createPayloadIndexAsync(anyString(), anyString(), any(), any(), any(), any(), any()))
                .thenReturn(Futures.immediateFailedFuture(new StatusRuntimeException(
                        Status.ALREADY_EXISTS.withDescription("Wrong input: Index already exists!"))));

        assertThatCode(() -> initializer.run(null)).doesNotThrowAnyException();

        verify(qdrantClient, times(4))
                .createPayloadIndexAsync(anyString(), anyString(), any(), any(), any(), any(), any());
    }
}
