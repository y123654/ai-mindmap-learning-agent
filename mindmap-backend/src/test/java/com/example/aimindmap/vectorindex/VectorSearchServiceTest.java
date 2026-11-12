package com.example.aimindmap.vectorindex;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.aimindmap.metrics.BusinessMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.qdrant.client.grpc.Common.Condition;
import io.qdrant.client.grpc.Common.Filter;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;

class VectorSearchServiceTest {

    private final EmbeddingModel embeddingModel = org.mockito.Mockito.mock(EmbeddingModel.class);
    private final QdrantVectorGateway gateway = org.mockito.Mockito.mock(QdrantVectorGateway.class);
    private final QdrantProperties properties = new QdrantProperties();
    /** 阶段八-1：embedding 指标断言用真实 SimpleMeterRegistry（不 mock 指标组件）。 */
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final BusinessMetrics businessMetrics = new BusinessMetrics(meterRegistry);
    private final VectorSearchService service =
            new VectorSearchService(embeddingModel, gateway, properties, businessMetrics);

    @Test
    void filterContainsUserIdAndActiveStatus() {
        Filter filter = VectorSearchService.buildFilter(42L, List.of());

        boolean hasUserFilter = false;
        boolean hasActiveStatus = false;
        for (Condition condition : filter.getMustList()) {
            assertThat(condition.hasField()).isTrue();
            String key = condition.getField().getKey();
            if ("user_id".equals(key)) {
                hasUserFilter = true;
                assertThat(condition.getField().getMatch().getIntegers().getIntegersList())
                        .contains(42L);
            }
            if ("status".equals(key)) {
                hasActiveStatus = true;
                assertThat(condition.getField().getMatch().getKeyword())
                        .isEqualTo("active");
            }
        }
        assertThat(hasUserFilter).isTrue();
        assertThat(hasActiveStatus).isTrue();
    }

    @Test
    void filterIncludesResourceTypesWhenProvided() {
        Filter filter = VectorSearchService.buildFilter(1L,
                List.of(VectorResourceType.DOCUMENT_CHUNK, VectorResourceType.QUESTION));

        Condition resourceCondition = filter.getMustList().stream()
                .filter(c -> c.getField().getKey().equals("resource_type"))
                .findFirst()
                .orElseThrow();
        assertThat(resourceCondition.getField().getMatch().getKeywords().getStringsList())
                .containsExactly("document_chunk", "question");
    }

    @Test
    void searchDedupesBySourceAppliesThresholdAndLimitsResults() {
        when(embeddingModel.embed(any(String.class))).thenReturn(new float[]{0.1f});

        List<QdrantVectorGateway.RetrievedPoint> points = List.of(
                point(0.81, "document_chunk", "3", 0, "进程定义"),
                point(0.79, "document_chunk", "3", 1, "进程定义2"),
                point(0.70, "mindmap_node", "n1", 0, "进程节点"),
                point(0.20, "question", "9", 0, "低分题目"));
        when(gateway.search(any(), any(), any(), anyInt())).thenReturn(points);

        VectorSearchService.SearchOutcome outcome = service.search(
                1L, "什么是进程", List.of());

        assertThat(outcome.matched()).isTrue();
        assertThat(outcome.hits())
                .extracting(hit -> hit.sourceType() + ":" + hit.sourceId())
                .containsExactly("document_chunk:3", "mindmap_node:n1");
        assertThat(outcome.hits().get(0).score()).isEqualTo(0.81);
    }

    @Test
    void allPointsBelowThresholdMeansNoMatch() {
        when(embeddingModel.embed(any(String.class))).thenReturn(new float[]{0.1f});
        when(gateway.search(any(), any(), any(), anyInt())).thenReturn(List.of(
                point(0.10, "document_chunk", "3", 0, "不相关内容")));

        VectorSearchService.SearchOutcome outcome = service.search(
                1L, "火星上有企鹅吗", List.of());

        assertThat(outcome.matched()).isFalse();
        assertThat(outcome.hits()).isEmpty();
    }

    // ---------- 阶段八-1：embedding 耗时与失败率埋点（325 行第 ④ 项） ----------

    @Test
    void searchRecordsEmbeddingSuccessMetric() {
        when(embeddingModel.embed(any(String.class))).thenReturn(new float[]{0.1f});
        when(gateway.search(any(), any(), any(), anyInt())).thenReturn(List.of());

        service.search(1L, "什么是进程", List.of());

        assertThat(meterRegistry.timer("aimindmap.embedding.call",
                "operation", "search", "model", "text-embedding-v3", "outcome", "success").count())
                .isEqualTo(1L);
        assertThat(meterRegistry.find("aimindmap.embedding.call.failure").counter()).isNull();
    }

    @Test
    void searchEmbeddingFailureRecordsFailureMetric() {
        when(embeddingModel.embed(any(String.class))).thenThrow(new RuntimeException("embedding down"));

        assertThatThrownBy(() -> service.search(1L, "什么是进程", List.of()))
                .isInstanceOf(VectorIndexExternalException.class);

        assertThat(meterRegistry.counter("aimindmap.embedding.call.failure",
                "operation", "search", "model", "text-embedding-v3").count()).isEqualTo(1.0);
        assertThat(meterRegistry.timer("aimindmap.embedding.call",
                "operation", "search", "model", "text-embedding-v3", "outcome", "failure").count())
                .isEqualTo(1L);
        assertThat(meterRegistry.find("aimindmap.embedding.call").tag("outcome", "success").timer()).isNull();
    }

    @Test
    void searchTopMemoryUsesDistinctOperationTag() {
        when(embeddingModel.embed(any(String.class))).thenReturn(new float[]{0.1f});
        when(gateway.search(any(), any(), any(), anyInt())).thenReturn(List.of());

        service.searchTopMemory(1L, "喜欢代码例子", 0.8d);

        assertThat(meterRegistry.timer("aimindmap.embedding.call",
                "operation", "memory_search", "model", "text-embedding-v3", "outcome", "success").count())
                .isEqualTo(1L);
        assertThat(meterRegistry.find("aimindmap.embedding.call").tag("operation", "search").timer())
                .isNull();
    }

    // ---------- P1-6：记忆相关性注入检索 ----------

    @Test
    void searchMemoriesFiltersByMinScoreSortsDescAndLimitsTopN() {
        when(embeddingModel.embed(any(String.class))).thenReturn(new float[]{0.1f});
        when(gateway.search(any(), any(), any(), anyInt())).thenReturn(List.of(
                point(0.70, "memory", "2", 0, "m2"),
                point(0.90, "memory", "1", 0, "m1"),
                point(0.40, "memory", "4", 0, "m4"),
                point(0.55, "memory", "3", 0, "m3")));

        List<VectorSearchService.VectorHit> hits = service.searchMemories(1L, "进程和线程", 0.5d, 2);

        // 低于 0.5 的被过滤，按分数降序取前 2
        assertThat(hits).extracting(VectorSearchService.VectorHit::sourceId)
                .containsExactly("1", "2");
        assertThat(hits).extracting(VectorSearchService.VectorHit::score)
                .containsExactly(0.90, 0.70);
        verify(gateway).search(any(), any(), any(), eq(2));
    }

    @Test
    void searchMemoriesRejectsBlankQueryAndNonPositiveLimit() {
        assertThatThrownBy(() -> service.searchMemories(1L, "  ", 0.5d, 5))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.searchMemories(1L, "进程", 0.5d, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void searchTopMemoryStillReturnsSingleHitAboveThreshold() {
        when(embeddingModel.embed(any(String.class))).thenReturn(new float[]{0.1f});
        when(gateway.search(any(), any(), any(), anyInt())).thenReturn(List.of(
                point(0.90, "memory", "7", 0, "喜欢代码例子")));

        assertThat(service.searchTopMemory(1L, "喜欢代码例子", 0.8d))
                .get()
                .extracting(VectorSearchService.VectorHit::sourceId)
                .isEqualTo("7");
        verify(gateway).search(any(), any(), any(), eq(1));
    }

    @Test
    void searchTopMemoryStillReturnsEmptyBelowThreshold() {
        when(embeddingModel.embed(any(String.class))).thenReturn(new float[]{0.1f});
        when(gateway.search(any(), any(), any(), anyInt())).thenReturn(List.of(
                point(0.10, "memory", "7", 0, "不相关")));

        assertThat(service.searchTopMemory(1L, "喜欢代码例子", 0.8d)).isEmpty();
    }

    private static QdrantVectorGateway.RetrievedPoint point(double score,
                                                             String resourceType,
                                                             String sourceId,
                                                             int chunkIndex,
                                                             String content) {
        Map<String, Object> payload = Map.of(
                "resource_type", resourceType,
                "source_id", sourceId,
                "chunk_index", chunkIndex,
                "title", content,
                "content", content,
                "source_path", List.of("操作系统"));
        return new QdrantVectorGateway.RetrievedPoint(UUID.randomUUID(), score, payload);
    }
}
