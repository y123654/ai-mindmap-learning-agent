package com.example.aimindmap.agentruntime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.aimindmap.ai.AiChatRequest;
import com.example.aimindmap.ai.AiGateway;
import com.example.aimindmap.ai.AiJsonRequest;
import com.example.aimindmap.metrics.BusinessMetrics;
import com.example.aimindmap.vectorindex.QdrantProperties;
import com.example.aimindmap.vectorindex.QdrantVectorGateway;
import com.example.aimindmap.vectorindex.VectorIndexExternalException;
import com.example.aimindmap.vectorindex.VectorResourceType;
import com.example.aimindmap.vectorindex.VectorSearchService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;

/**
 * P1-5 多查询 + RRF 的确定性单测：<b>不真调 AI、不真连 Qdrant</b>。
 *
 * <p>覆盖方案 §P1-5 的 4 条硬规则（RRF 排序 / 每篇 2 chunk / 去重 / 改写失败降级）、开关关闭的旧行为、
 * 以及“两个改写并发 + 整体落在 10s 工具超时之内”。
 *
 * <p><b>P1-E2c（所有者 2026-10-28 决定）</b>：LLM 改写默认关。因此凡是<b>要验证 LLM 改写链路</b>的用例
 * 都显式 {@code setRewriteLlmEnabled(true)}（这些用例写于“默认开”的年代，不打开就测不到原路径）；
 * 另外新增两条守门用例：默认 false 时 {@code AiGateway} <b>零交互</b>、显式 true 时仍发起 2 路改写。
 */
class MultiQueryRetrieverTest {

    private static final Long USER_ID = 8L;
    private static final List<VectorResourceType> TYPES = List.of(VectorResourceType.DOCUMENT_CHUNK);

    private static final String ORIGINAL_QUERY = "原问题";
    private static final String REWRITE_PARAPHRASE = "改写说法";
    private static final String REWRITE_KEYWORDS = "改写关键词";

    private final VectorSearchService vectorSearchService = mock(VectorSearchService.class);
    private final QdrantProperties qdrantProperties = new QdrantProperties();
    private final RetrievalProperties retrievalProperties = new RetrievalProperties();
    private FakeAiGateway aiGateway = FakeAiGateway.rewriting();

    // ---------- 0) P1-E2c：LLM 改写默认关（所有者 2026-10-28 决定） ----------

    @Test
    void defaultConfigNeverTouchesAiGatewayAndKeepsRuleRewritePlusRrf() {
        AiGateway gateway = mock(AiGateway.class);
        List<String> searched = recordQueries(query -> List.of(hit("a", 0), hit("b", 0)));
        MultiQueryRetriever retriever = new MultiQueryRetriever(vectorSearchService, gateway,
                qdrantProperties, retrievalProperties);

        VectorSearchService.SearchOutcome outcome = retriever.search(USER_ID, "什么是进程调度", TYPES);

        // 1) 零交互：连 configured() 探测都不允许发生（不是“调一次再判断”）
        verifyNoInteractions(gateway);
        // 2) 只用规则改写：原 query + 1 个清洗变体，列表长度 ≥1
        assertThat(searched).containsExactlyInAnyOrder("什么是进程调度", "进程调度");
        // 3) 仍有结果
        assertThat(outcome.hits()).hasSizeGreaterThanOrEqualTo(1);
        // 4) 仍走 RRF 融合：两条列表都把它排第 1 → 2/(60+1)，证明没有偷偷退回旧单查询路径
        assertThat(scoreOf(outcome, "a"))
                .isCloseTo(2.0d / 61, org.assertj.core.data.Offset.offset(1e-12));

        // 5) 与“网关为 null”的规则路径逐条一致（同一输入同结果）
        MultiQueryRetriever ruleOnly = new MultiQueryRetriever(vectorSearchService, null,
                qdrantProperties, retrievalProperties);
        assertThat(outcome.hits())
                .isEqualTo(ruleOnly.search(USER_ID, "什么是进程调度", TYPES).hits());
    }

    @Test
    void explicitSwitchOnStillIssuesBothLlmRewriteCalls() {
        retrievalProperties.setRewriteLlmEnabled(true);
        aiGateway = FakeAiGateway.rewriting();
        List<String> searched = recordQueries(query -> List.of(hit("a", 0)));

        VectorSearchService.SearchOutcome outcome = retriever().search(USER_ID, ORIGINAL_QUERY, TYPES);

        assertThat(outcome.hits()).isNotEmpty();
        assertThat(aiGateway.calls()).as("换说法 + 提关键词两路各一次").isEqualTo(2);
        assertThat(searched).containsExactlyInAnyOrder(
                ORIGINAL_QUERY, REWRITE_PARAPHRASE, REWRITE_KEYWORDS);
    }

    // ---------- 1) RRF 排序 ----------

    @Test
    void rrfFusionLiftsChunkThatOnlyRanksFirstInOneRewriteList() {
        // 这条用例验证的是 LLM 改写链路下的融合：显式打开（P1-E2c 起默认关）
        retrievalProperties.setRewriteLlmEnabled(true);
        // 原列表：a..g 占第 1~7，x 只排第 8（正常情况根本进不了最终结果）
        List<VectorSearchService.VectorHit> original = List.of(
                hit("a", 0), hit("b", 0), hit("c", 0), hit("d", 0),
                hit("e", 0), hit("f", 0), hit("g", 0), hit("x", 0));
        // 改写 A：x 排第 1；改写 B：只有一个 i
        List<VectorSearchService.VectorHit> rewriteA = List.of(hit("x", 0), hit("h", 0));
        List<VectorSearchService.VectorHit> rewriteB = List.of(hit("i", 0));
        stubThreeQueries(original, rewriteA, rewriteB);

        VectorSearchService.SearchOutcome outcome = retriever().search(USER_ID, ORIGINAL_QUERY, TYPES);

        List<String> ids = outcome.hits().stream()
                .map(VectorSearchService.VectorHit::sourceId)
                .toList();
        // x = 1/(60+8) + 1/(60+1)，a/i = 1/(60+1)，b/h = 1/(60+2)，最终收敛 5 条
        assertThat(ids).containsExactly("x", "a", "i", "b", "h");
        assertThat(scoreOf(outcome, "x")).isCloseTo(1.0d / 68 + 1.0d / 61, org.assertj.core.data.Offset.offset(1e-12));
        assertThat(scoreOf(outcome, "a")).isCloseTo(1.0d / 61, org.assertj.core.data.Offset.offset(1e-12));
        assertThat(scoreOf(outcome, "b")).isCloseTo(1.0d / 62, org.assertj.core.data.Offset.offset(1e-12));
        // 规格要求的关键现象：只在改写列表排第 1、在原列表排第 8 的 chunk 被抬到原列表第 2 名之前
        assertThat(ids.indexOf("x")).isLessThan(ids.indexOf("b"));
    }

    // ---------- 2) 每篇文档最多 2 个 chunk ----------

    @Test
    void keepsAtMostTwoChunksPerDocument() {
        retrievalProperties.setRewriteLlmEnabled(true);
        List<VectorSearchService.VectorHit> original = List.of(
                hit("9", 0), hit("9", 1), hit("9", 2), hit("9", 3), hit("9", 4), hit("10", 0));
        // 改写 A 又命中 9#2：它的融合分最高，应该和 9#0 一起留下，其余 3 个 chunk 被丢掉
        stubThreeQueries(original, List.of(hit("9", 2)), List.of());

        VectorSearchService.SearchOutcome outcome = retriever().search(USER_ID, ORIGINAL_QUERY, TYPES);

        assertThat(outcome.hits())
                .extracting(hit -> hit.sourceId() + "#" + hit.chunkIndex())
                .containsExactly("9#2", "9#0", "10#0");
        assertThat(outcome.hits().stream().filter(hit -> "9".equals(hit.sourceId())).count())
                .isEqualTo(RetrievalProperties.DEFAULT_MAX_CHUNKS_PER_DOCUMENT);
    }

    // ---------- 2b) 两个上限来自配置（所有者 2026-10-27 裁定：下一段要扫 5 vs 8、1 vs 2） ----------

    @Test
    void maxResultsComesFromProperties() {
        List<VectorSearchService.VectorHit> original = List.of(
                hit("a", 0), hit("b", 0), hit("c", 0), hit("d", 0),
                hit("e", 0), hit("f", 0), hit("g", 0));
        stubThreeQueries(original, List.of(), List.of());

        retrievalProperties.setMaxResults(8);
        assertThat(retriever().search(USER_ID, ORIGINAL_QUERY, TYPES).hits()).hasSize(7);

        retrievalProperties.setMaxResults(3);
        assertThat(retriever().search(USER_ID, ORIGINAL_QUERY, TYPES).hits()).hasSize(3);

        retrievalProperties.setMaxResults(RetrievalProperties.DEFAULT_MAX_RESULTS);
        assertThat(retriever().search(USER_ID, ORIGINAL_QUERY, TYPES).hits()).hasSize(5);
    }

    @Test
    void maxChunksPerDocumentComesFromProperties() {
        List<VectorSearchService.VectorHit> original = List.of(
                hit("9", 0), hit("9", 1), hit("9", 2), hit("9", 3));
        stubThreeQueries(original, List.of(), List.of());

        retrievalProperties.setMaxChunksPerDocument(1);
        assertThat(retriever().search(USER_ID, ORIGINAL_QUERY, TYPES).hits())
                .extracting(hit -> hit.sourceId() + "#" + hit.chunkIndex())
                .containsExactly("9#0");

        retrievalProperties.setMaxChunksPerDocument(3);
        assertThat(retriever().search(USER_ID, ORIGINAL_QUERY, TYPES).hits())
                .extracting(hit -> hit.sourceId() + "#" + hit.chunkIndex())
                .containsExactly("9#0", "9#1", "9#2");
    }

    // ---------- 3) 去重 + 分数累加 ----------

    @Test
    void dedupesSameChunkAcrossListsAndAccumulatesRrfScore() {
        retrievalProperties.setRewriteLlmEnabled(true);
        List<VectorSearchService.VectorHit> single = List.of(hit("9", 2));
        stubThreeQueries(single, single, single);

        VectorSearchService.SearchOutcome outcome = retriever().search(USER_ID, ORIGINAL_QUERY, TYPES);

        assertThat(outcome.hits()).singleElement().satisfies(hit -> {
            assertThat(hit.sourceId()).isEqualTo("9");
            assertThat(hit.chunkIndex()).isEqualTo(2);
            // 三个列表都排第 1 → 1/61 + 1/61 + 1/61，且只输出一条
            assertThat(hit.score()).isCloseTo(3.0d / 61, org.assertj.core.data.Offset.offset(1e-12));
        });
    }

    // ---------- 4) 改写失败兜底（异常 / 返回 null / 超时 / 抠不出改写） ----------

    @Test
    void fallsBackToRuleRewriteWhenGatewayThrows() {
        retrievalProperties.setRewriteLlmEnabled(true);
        aiGateway = FakeAiGateway.throwing();
        List<String> searched = recordQueries(query -> List.of(hit("a", 0)));

        VectorSearchService.SearchOutcome outcome = retriever()
                .search(USER_ID, "什么是进程调度的算法？", TYPES);

        assertThat(outcome.hits()).isNotEmpty();
        assertThat(searched).contains("什么是进程调度的算法？");
        assertThat(searched).hasSizeGreaterThanOrEqualTo(2);
    }

    @Test
    void fallsBackToRuleRewriteWhenGatewayReturnsNullJson() {
        retrievalProperties.setRewriteLlmEnabled(true);
        aiGateway = FakeAiGateway.returningNull();
        List<String> searched = recordQueries(query -> List.of(hit("a", 0)));

        VectorSearchService.SearchOutcome outcome = retriever().search(USER_ID, "什么是进程调度", TYPES);

        assertThat(outcome.hits()).isNotEmpty();
        assertThat(searched).hasSizeGreaterThanOrEqualTo(2);
    }

    @Test
    void fallsBackToRuleRewriteAndStaysInsideToolTimeoutWhenRewriteHangs() {
        retrievalProperties.setRewriteLlmEnabled(true);
        // 两个改写都卡 5s：必须在“改写合计 4s”内放弃，改用规则改写，且整体 < 10s 工具超时
        aiGateway = FakeAiGateway.slow(5_000);
        List<String> searched = recordQueries(query -> List.of(hit("a", 0)));

        long startedNanos = System.nanoTime();
        VectorSearchService.SearchOutcome outcome = retriever().search(USER_ID, "什么是进程调度", TYPES);
        long elapsedMs = (System.nanoTime() - startedNanos) / 1_000_000L;

        assertThat(outcome.hits()).isNotEmpty();
        assertThat(searched).hasSizeGreaterThanOrEqualTo(2);
        assertThat(elapsedMs)
                .as("多查询最坏耗时必须小于 10s 工具超时，实测 %dms", elapsedMs)
                .isLessThan(10_000L);
    }

    @Test
    void alwaysSearchesOriginalQueryEvenWhenNothingCanBeRewritten() {
        // “开关打开但网关未配置”这条分支仍要覆盖：只有原 query 可检索，列表长度退化为 1
        retrievalProperties.setRewriteLlmEnabled(true);
        aiGateway = FakeAiGateway.notConfigured();
        List<String> searched = recordQueries(query -> List.of(hit("a", 0)));

        VectorSearchService.SearchOutcome outcome = retriever().search(USER_ID, "什么是？", TYPES);

        assertThat(outcome.hits()).isNotEmpty();
        // 全是疑问词、规则也抠不出实体 → 列表长度退化为 1，仍有结果
        assertThat(searched).containsExactly("什么是？");
    }

    @Test
    void ruleRewriteOnlyCleansAndNeverIntroducesConceptWords() {
        List<String> variants = retriever().ruleRewrites("什么是进程调度的算法？");

        // 规则兜底只做清洗：去疑问词/停用词/标点压平 → 至多 1 个变体，与原文不同才保留
        assertThat(variants).containsExactly("进程调度 算法");
        // 关键断言：变体里不允许出现原 query 没有的词
        //（旧实现会追加“线程”这类“相关概念而非同义词”，所有者 2026-10-27 裁定删除该逻辑）
        assertThat(variants).noneMatch(variant -> variant.contains("线程") || variant.contains("并发"));
        assertThat(variants).allSatisfy(variant -> {
            for (char character : variant.replace(" ", "").toCharArray()) {
                assertThat("什么是进程调度的算法？")
                        .as("变体里的每个字都必须来自原 query（只许删、不许增）")
                        .contains(String.valueOf(character));
            }
        });
        // 规则改写必须可复现（同输入同输出）
        assertThat(retriever().ruleRewrites("什么是进程调度的算法？")).isEqualTo(variants);
        // 清洗后与原文相同 / 抠不出任何实体 → 没有变体（查询集只剩原 query 一路）
        assertThat(retriever().ruleRewrites("进程调度")).isEmpty();
        assertThat(retriever().ruleRewrites("？？")).isEmpty();
    }

    @Test
    void originalQueryFailureStaysFatalLikeBefore() {
        aiGateway = FakeAiGateway.notConfigured();
        when(vectorSearchService.searchTopChunks(eq(USER_ID), anyString(), anyList(), anyInt()))
                .thenThrow(new VectorIndexExternalException("Qdrant 检索失败", null));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> retriever().search(USER_ID, "什么是进程", TYPES))
                .isInstanceOf(VectorIndexExternalException.class)
                .hasMessageContaining("Qdrant 检索失败");
    }

    // ---------- 5) 开关关闭 = 旧行为（真 VectorSearchService + 假 Qdrant） ----------

    @Test
    void switchOffUsesExactlyOneVectorSearchAndKeepsOneChunkPerDocument() {
        QdrantVectorGateway gateway = mock(QdrantVectorGateway.class);
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        when(embeddingModel.embed(anyString())).thenReturn(new float[]{0.1f});
        when(gateway.search(any(), any(), any(), anyInt())).thenReturn(List.of(
                point(0.90, "9", 0), point(0.85, "9", 1), point(0.80, "9", 2),
                point(0.75, "9", 3), point(0.70, "9", 4), point(0.60, "10", 0)));
        VectorSearchService service = new VectorSearchService(embeddingModel, gateway,
                qdrantProperties, new BusinessMetrics(new SimpleMeterRegistry()));
        retrievalProperties.setMultiQueryEnabled(false);
        // 两个上限只作用于多查询路径：开关关时即使配成 8 / 3，旧路径也必须逐字节不变
        retrievalProperties.setMaxResults(8);
        retrievalProperties.setMaxChunksPerDocument(3);
        FakeAiGateway aiGateway = FakeAiGateway.rewriting();
        MultiQueryRetriever retriever = new MultiQueryRetriever(service, aiGateway,
                qdrantProperties, retrievalProperties);

        VectorSearchService.SearchOutcome viaRetriever = retriever.search(1L, "什么是进程", TYPES);
        VectorSearchService.SearchOutcome viaOldPath = service.search(1L, "什么是进程", TYPES);

        // 与直接调用旧检索逐条相同：每篇只留 1 个 chunk（分数最高的那个）
        assertThat(viaRetriever.hits()).isEqualTo(viaOldPath.hits());
        assertThat(viaRetriever.hits())
                .extracting(VectorSearchService.VectorHit::chunkIndex)
                .containsOnly(0);
        verify(gateway, times(2)).search(any(), any(), any(), anyInt());
        assertThat(aiGateway.calls()).as("开关关闭时零 LLM 调用").isZero();
    }

    @Test
    void legacyFactoryIsAlwaysTheOldSingleQueryPath() {
        VectorSearchService service = mock(VectorSearchService.class);
        VectorSearchService.SearchOutcome oldOutcome = new VectorSearchService.SearchOutcome(
                List.of(hit("a", 0)), true);
        when(service.search(eq(USER_ID), eq("什么是进程"), anyList())).thenReturn(oldOutcome);

        MultiQueryRetriever legacy = MultiQueryRetriever.legacy(service);

        assertThat(legacy.isEnabled()).isFalse();
        assertThat(legacy.search(USER_ID, "什么是进程", TYPES)).isEqualTo(oldOutcome);
        verify(service, never()).searchTopChunks(any(), anyString(), anyList(), anyInt());
    }

    // ---------- 6) 两个改写并发 ----------

    @Test
    void twoRewritesRunConcurrentlyAndRespectTheToolTimeout() {
        retrievalProperties.setRewriteLlmEnabled(true);
        aiGateway = FakeAiGateway.slow(500);
        List<String> searched = recordQueries(query -> List.of(hit("a", 0)));

        long startedNanos = System.nanoTime();
        VectorSearchService.SearchOutcome outcome = retriever().search(USER_ID, "什么是进程调度", TYPES);
        long elapsedMs = (System.nanoTime() - startedNanos) / 1_000_000L;

        assertThat(outcome.hits()).isNotEmpty();
        assertThat(searched).as("原 query + 2 个改写都要各检索一次").hasSize(3);

        List<long[]> windows = aiGateway.windows();
        assertThat(windows).hasSize(2);
        long overlapNanos = Math.min(windows.get(0)[1], windows.get(1)[1])
                - Math.max(windows.get(0)[0], windows.get(1)[0]);
        assertThat(overlapNanos)
                .as("两个改写必须并发（并行重叠时长 %dms），而不是串行 2+2s", overlapNanos / 1_000_000L)
                .isGreaterThan(200_000_000L);
        assertThat(elapsedMs)
                .as("串行需要 ≥1000ms，并发应显著更快；实测 %dms", elapsedMs)
                .isLessThan(900L);
    }

    // ---------- 工具方法 ----------

    private MultiQueryRetriever retriever() {
        return new MultiQueryRetriever(vectorSearchService, aiGateway, qdrantProperties,
                retrievalProperties);
    }

    private void stubThreeQueries(List<VectorSearchService.VectorHit> original,
                                  List<VectorSearchService.VectorHit> rewriteA,
                                  List<VectorSearchService.VectorHit> rewriteB) {
        when(vectorSearchService.searchTopChunks(eq(USER_ID), eq(ORIGINAL_QUERY), anyList(), anyInt()))
                .thenReturn(original);
        when(vectorSearchService.searchTopChunks(eq(USER_ID), eq(REWRITE_PARAPHRASE), anyList(), anyInt()))
                .thenReturn(rewriteA);
        when(vectorSearchService.searchTopChunks(eq(USER_ID), eq(REWRITE_KEYWORDS), anyList(), anyInt()))
                .thenReturn(rewriteB);
    }

    /** 用 doAnswer 记录每次检索用的 query（线程安全），并按 query 返回固定结果。 */
    private List<String> recordQueries(
            Function<String, List<VectorSearchService.VectorHit>> hitsByQuery) {
        List<String> queries = Collections.synchronizedList(new ArrayList<>());
        doAnswer(invocation -> {
            String query = invocation.getArgument(1);
            queries.add(query);
            return hitsByQuery.apply(query);
        }).when(vectorSearchService).searchTopChunks(eq(USER_ID), anyString(), anyList(), anyInt());
        return queries;
    }

    private static VectorSearchService.VectorHit hit(String sourceId, int chunkIndex) {
        return new VectorSearchService.VectorHit("document_chunk", sourceId, chunkIndex,
                "标题" + sourceId, "正文" + sourceId + "#" + chunkIndex, List.of(), 0.9d, null);
    }

    private static QdrantVectorGateway.RetrievedPoint point(double score, String sourceId, int chunkIndex) {
        Map<String, Object> payload = Map.of(
                "resource_type", "document_chunk",
                "source_id", sourceId,
                "chunk_index", chunkIndex,
                "title", "标题" + sourceId,
                "content", "正文" + sourceId + "#" + chunkIndex,
                "source_path", List.of("操作系统"));
        return new QdrantVectorGateway.RetrievedPoint(UUID.randomUUID(), score, payload);
    }

    private static double scoreOf(VectorSearchService.SearchOutcome outcome, String sourceId) {
        return outcome.hits().stream()
                .filter(hit -> sourceId.equals(hit.sourceId()))
                .mapToDouble(VectorSearchService.VectorHit::score)
                .findFirst()
                .orElseThrow();
    }

    /** 假网关：可配置“已配置/未配置”“延迟”“返回什么/抛什么”，并记录每次调用的时间窗。 */
    private static final class FakeAiGateway implements AiGateway {

        private final boolean configured;
        private final Function<AiJsonRequest, String> responder;
        private final long delayMillis;
        private final AtomicInteger calls = new AtomicInteger();
        private final List<long[]> windows = Collections.synchronizedList(new ArrayList<>());

        private FakeAiGateway(boolean configured, Function<AiJsonRequest, String> responder,
                              long delayMillis) {
            this.configured = configured;
            this.responder = responder;
            this.delayMillis = delayMillis;
        }

        private static FakeAiGateway rewriting() {
            return new FakeAiGateway(true, request -> isKeywordPrompt(request)
                    ? REWRITE_KEYWORDS : REWRITE_PARAPHRASE, 0L);
        }

        private static FakeAiGateway throwing() {
            return new FakeAiGateway(true, request -> {
                throw new IllegalStateException("模拟改写网关故障");
            }, 0L);
        }

        private static FakeAiGateway returningNull() {
            return new FakeAiGateway(true, request -> null, 0L);
        }

        private static FakeAiGateway notConfigured() {
            return new FakeAiGateway(false, request -> {
                throw new AssertionError("未配置的网关不应该被调用");
            }, 0L);
        }

        private static FakeAiGateway slow(long delayMillis) {
            return new FakeAiGateway(true, request -> isKeywordPrompt(request)
                    ? REWRITE_KEYWORDS : REWRITE_PARAPHRASE, delayMillis);
        }

        private static boolean isKeywordPrompt(AiJsonRequest request) {
            return request != null && request.systemPrompt() != null
                    && request.systemPrompt().contains("关键词");
        }

        private int calls() {
            return calls.get();
        }

        private List<long[]> windows() {
            return windows;
        }

        @Override
        public <T> T completeJson(AiJsonRequest request, Class<T> responseType) {
            calls.incrementAndGet();
            long startedNanos = System.nanoTime();
            try {
                if (delayMillis > 0) {
                    Thread.sleep(delayMillis);
                }
                String rewritten = responder.apply(request);
                if (rewritten == null) {
                    return null;
                }
                if (responseType != QueryRewriteResult.class) {
                    throw new IllegalStateException("假网关只支持 QueryRewriteResult：" + responseType);
                }
                return responseType.cast(new QueryRewriteResult(rewritten));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("假网关被中断", e);
            } finally {
                windows.add(new long[]{startedNanos, System.nanoTime()});
            }
        }

        @Override
        public String completeChat(AiChatRequest request) {
            throw new AssertionError("多查询不应调用 completeChat");
        }

        @Override
        public void streamChat(AiChatRequest request, Consumer<String> onDelta) {
            throw new AssertionError("多查询不应调用 streamChat");
        }

        @Override
        public boolean configured() {
            return configured;
        }

        @Override
        public String provider() {
            return "fake";
        }

        @Override
        public String model() {
            return "fake-model";
        }
    }
}
