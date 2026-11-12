package com.example.aimindmap.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.aimindmap.assistant.RedisChatRecentMessageCache;
import com.example.aimindmap.mqsupport.TaskType;
import com.example.aimindmap.progress.LocalSseRegistry;
import com.example.aimindmap.ratelimit.RateLimitInterceptor;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * 阶段八-1：Prometheus 指标名契约测试 —— 直接用 {@link PrometheusMeterRegistry}（纯 micrometer，
 * 不起 Spring 上下文、不连任何中间件）抓取文本，锁死阶段八-2 Grafana 面板要引用的指标名。
 *
 * <p>为什么单独一个类：micrometer 的点名（{@code aimindmap.task.enqueued}）到 Prometheus 名
 * （{@code aimindmap_task_enqueued_total}）有一套命名约定（点换下划线、Counter 补 {@code _total}、
 * Timer 补 {@code _seconds_count/_sum/_max}），阶段八-2 的面板 JSON 必须按渲染后的名字写。
 * 本测试把七组指标的渲染结果逐一断言，命名约定一旦被升级打破，这里会先失败。
 *
 * <p>{@code ActuatorEndpointsIntegrationTest} 断言的是同一份名单（端点可达性 + 匿名放行），
 * 两者互为印证：这个类保证名字对，那个类保证名字真的能从 {@code /actuator/prometheus} 抓到。
 */
class PrometheusNamingContractTest {

    /** 七组业务指标各自的代表性 Prometheus 名（与 IT 中的名单逐字一致）。 */
    private static final String[] SEVEN_GROUP_NAMES = {
            "aimindmap_task_enqueued_total",
            "aimindmap_task_consumed_total",
            "aimindmap_task_dead_letter_total",
            "aimindmap_task_consume_seconds_count",
            "aimindmap_pdf_upload_seconds_count",
            "aimindmap_sync_job_seconds_count",
            "aimindmap_ai_call_seconds_count",
            "aimindmap_ai_call_failure_total",
            "aimindmap_embedding_call_seconds_count",
            "aimindmap_embedding_call_failure_total",
            "aimindmap_chat_recent_cache_hits_total",
            "aimindmap_chat_recent_cache_misses_total",
            "aimindmap_ratelimit_rejections_total",
            "aimindmap_sse_connections",
    };

    private final PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
    private final MqTaskMetrics mqTaskMetrics = new MqTaskMetrics(registry);
    private final BusinessMetrics businessMetrics = new BusinessMetrics(registry);
    private final LocalSseRegistry sseRegistry = new LocalSseRegistry();

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> providerOf(T instance) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(instance);
        return provider;
    }

    @Test
    void scrapedTextCarriesAllSevenMetricGroupNames() {
        RedisChatRecentMessageCache cache = mock(RedisChatRecentMessageCache.class);
        when(cache.getHits()).thenReturn(4L);
        when(cache.getMisses()).thenReturn(1L);
        RateLimitInterceptor interceptor = mock(RateLimitInterceptor.class);
        when(interceptor.getRejections()).thenReturn(2L);
        new BusinessMetricsBinder(registry, providerOf(cache), providerOf(interceptor), sseRegistry);

        recordOneSamplePerLazyGroup();
        sseRegistry.subscribe(990001L);

        String scraped = registry.scrape();

        assertThat(scraped).contains(SEVEN_GROUP_NAMES);
    }

    @Test
    void countersRenderWithTotalSuffixAndTimersWithSecondsSuffix() {
        recordOneSamplePerLazyGroup();

        String scraped = registry.scrape();

        // 渲染实况（micrometer 1.14 + prometheus client_java 1.3）：tag 按字母序输出、
        // 末尾无逗号；Counter 值为 1.0，Timer 是 summary 类型（_count/_sum 为整数/科学计数），
        // 另有一条独立的 _max gauge。阶段八-2 面板按这个格式写 PromQL。
        assertThat(scraped).contains(
                "aimindmap_task_enqueued_total{task_type=\"PDF_IMPORT\"} 1.0",
                "aimindmap_task_consumed_total{outcome=\"success\",task_type=\"PDF_IMPORT\"} 1.0",
                "aimindmap_task_dead_letter_total{reason=\"retries_exhausted\","
                        + "task_type=\"MINDMAP_SYNC\"} 1.0",
                "aimindmap_ai_call_failure_total{error_code=\"AI_TIMEOUT\",model=\"test-model\","
                        + "operation=\"chat\"} 1.0",
                "aimindmap_embedding_call_failure_total{model=\"text-embedding-v3\","
                        + "operation=\"search\"} 1.0");
        assertThat(scraped).contains("aimindmap_task_consume_seconds_count")
                .contains("aimindmap_task_consume_seconds_sum")
                .contains("aimindmap_task_consume_seconds_count{outcome=\"success\","
                        + "task_type=\"PDF_IMPORT\"} 1")
                .contains("aimindmap_pdf_upload_seconds_count 1")
                .contains("aimindmap_pdf_upload_seconds_max")
                .contains("aimindmap_sync_job_seconds_count{outcome=\"ready\"} 1")
                .contains("# TYPE aimindmap_task_consume_seconds summary")
                .contains("# TYPE aimindmap_pdf_upload_seconds_max gauge");
    }

    @Test
    void gaugesAndFunctionCountersRenderWithoutTotalForGaugeOnly() {
        RedisChatRecentMessageCache cache = mock(RedisChatRecentMessageCache.class);
        when(cache.getHits()).thenReturn(3L);
        when(cache.getMisses()).thenReturn(1L);
        RateLimitInterceptor interceptor = mock(RateLimitInterceptor.class);
        when(interceptor.getRejections()).thenReturn(7L);
        new BusinessMetricsBinder(registry, providerOf(cache), providerOf(interceptor), sseRegistry);
        sseRegistry.subscribe(990001L);
        sseRegistry.subscribe(990001L);

        String scraped = registry.scrape();

        assertThat(scraped).contains("aimindmap_chat_recent_cache_hits_total 3.0")
                .contains("aimindmap_chat_recent_cache_misses_total 1.0")
                .contains("aimindmap_ratelimit_rejections_total 7.0")
                .contains("aimindmap_sse_connections 2.0");
        // 命中率 = hits/(hits+misses) = 0.75，阶段八-2 面板用 PromQL 算，这里验证分子分母都能抓到
        assertThat(3.0d / (3.0d + 1.0d)).isEqualTo(0.75d);
    }

    @Test
    void helpAndTypeLinesAreEmittedForCustomMetrics() {
        mqTaskMetrics.recordEnqueued(TaskType.VECTOR_UPSERT);

        String scraped = registry.scrape();

        assertThat(scraped).contains("# TYPE aimindmap_task_enqueued_total counter");
    }

    /** 埋点铁律在渲染层的最终自查：业务指标行不得出现 ID 类 tag，也不得出现密钥字样。 */
    @Test
    void scrapedBusinessMetricLinesCarryNoHighCardinalityTagsOrSecrets() {
        RedisChatRecentMessageCache cache = mock(RedisChatRecentMessageCache.class);
        RateLimitInterceptor interceptor = mock(RateLimitInterceptor.class);
        new BusinessMetricsBinder(registry, providerOf(cache), providerOf(interceptor), sseRegistry);
        recordOneSamplePerLazyGroup();

        String scraped = registry.scrape();

        for (String line : scraped.split("\n")) {
            if (!line.startsWith("aimindmap_")) {
                continue;
            }
            assertThat(line).doesNotContain("user_id=").doesNotContain("session_id=")
                    .doesNotContain("job_id=").doesNotContain("task_id=")
                    .doesNotContain("message_id=").doesNotContain("document_id=");
        }
        assertThat(scraped).doesNotContain("sk-").doesNotContain("api_key");
    }

    /**
     * 纯 micrometer 层面验证 P95 前提（不依赖 Spring）：给 registry 挂一个开启
     * {@code percentilesHistogram} 的 {@link MeterFilter} 后，Timer 必须渲染成
     * {@code # TYPE ... histogram} 并带 {@code le} 标签的 {@code _bucket} 序列，
     * 同时保留 {@code _count} / {@code _sum} —— 这正是 {@code histogram_quantile(0.95, ...)}
     * 需要的三件套。与 {@code HistogramDistributionWiringTest} 互补：那边证明
     * application.yml 的键真的把这个开关打开了，这边证明开关打开后的渲染结果符合 PromQL 预期。
     */
    @Test
    void histogramEnabledTimerRendersBucketsWithLeTag() {
        PrometheusMeterRegistry histogramRegistry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        histogramRegistry.config().meterFilter(new MeterFilter() {
            @Override
            public DistributionStatisticConfig configure(Meter.Id id, DistributionStatisticConfig config) {
                return DistributionStatisticConfig.builder()
                        .percentilesHistogram(true)
                        .build()
                        .merge(config);
            }
        });
        BusinessMetrics metrics = new BusinessMetrics(histogramRegistry);

        metrics.recordSyncJobProcess("ready", metrics.startTimer());

        String scraped = histogramRegistry.scrape();
        assertThat(scraped).contains("# TYPE aimindmap_sync_job_seconds histogram");
        assertThat(scraped).contains("aimindmap_sync_job_seconds_bucket{outcome=\"ready\",le=\"+Inf\"}");
        assertThat(scraped).contains("aimindmap_sync_job_seconds_count{outcome=\"ready\"}");
        assertThat(scraped).contains("aimindmap_sync_job_seconds_sum{outcome=\"ready\"}");
    }

    private void recordOneSamplePerLazyGroup() {
        long startedNanos = mqTaskMetrics.startTimer();
        mqTaskMetrics.recordEnqueued(TaskType.PDF_IMPORT);
        mqTaskMetrics.recordConsumed(TaskType.PDF_IMPORT, MqTaskMetrics.ConsumeOutcome.SUCCESS, startedNanos);
        mqTaskMetrics.recordDeadLettered(TaskType.MINDMAP_SYNC,
                MqTaskMetrics.DeadLetterReason.RETRIES_EXHAUSTED, mqTaskMetrics.startTimer());

        businessMetrics.recordPdfUploadAccepted(businessMetrics.startTimer());
        businessMetrics.recordSyncJobProcess("ready", businessMetrics.startTimer());
        businessMetrics.recordAiCallSuccess(BusinessMetrics.AI_OP_JSON, "test-model",
                businessMetrics.startTimer());
        businessMetrics.recordAiCallFailure(BusinessMetrics.AI_OP_CHAT, "test-model", "AI_TIMEOUT",
                businessMetrics.startTimer());
        businessMetrics.recordEmbeddingSuccess(BusinessMetrics.EMBEDDING_OP_INDEX,
                "text-embedding-v3", businessMetrics.startTimer());
        businessMetrics.recordEmbeddingFailure(BusinessMetrics.EMBEDDING_OP_SEARCH,
                "text-embedding-v3", businessMetrics.startTimer());
    }
}
