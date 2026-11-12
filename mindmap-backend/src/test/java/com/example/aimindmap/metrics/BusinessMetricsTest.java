package com.example.aimindmap.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * 阶段八-1：非 MQ 业务指标组件单测（{@link SimpleMeterRegistry}，纯内存，不发任何真实请求）。
 *
 * <p>覆盖方案 325 行的 ②PDF 上传 RT、③同步 RT、④AI/Embedding 耗时与失败率三组埋点的
 * 指标名、tag 与类型；同时用「tag 键白名单」测试锁死埋点铁律（不得出现高基数/隐私 tag）。
 */
class BusinessMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final BusinessMetrics metrics = new BusinessMetrics(registry);

    // ---------- ② PDF 上传 RT ----------

    @Test
    void pdfUploadTimerRecordsAcceptedUpload() {
        long startedNanos = System.nanoTime() - Duration.ofMillis(80).toNanos();

        metrics.recordPdfUploadAccepted(startedNanos);

        Timer timer = registry.timer("aimindmap.pdf.upload");
        assertThat(timer.count()).isEqualTo(1L);
        assertThat(timer.totalTime(TimeUnit.MILLISECONDS)).isGreaterThanOrEqualTo(80d);
        assertThat(timer.getId().getTags()).isEmpty();
    }

    // ---------- ③ 同步 RT ----------

    @Test
    void syncJobTimerIsTaggedByOutcome() {
        metrics.recordSyncJobProcess("ready", metrics.startTimer());
        metrics.recordSyncJobProcess("ready", metrics.startTimer());
        metrics.recordSyncJobProcess("failed_business", metrics.startTimer());

        assertThat(registry.timer("aimindmap.sync.job", "outcome", "ready").count()).isEqualTo(2L);
        assertThat(registry.timer("aimindmap.sync.job", "outcome", "failed_business").count()).isEqualTo(1L);
    }

    @Test
    void syncJobTimerRecordsElapsedDuration() {
        long startedNanos = System.nanoTime() - Duration.ofMillis(200).toNanos();

        metrics.recordSyncJobProcess("early_exit", startedNanos);

        Timer timer = registry.timer("aimindmap.sync.job", "outcome", "early_exit");
        assertThat(timer.totalTime(TimeUnit.MILLISECONDS)).isGreaterThanOrEqualTo(200d);
    }

    @Test
    void blankSyncOutcomeFallsBackToExceptionTag() {
        metrics.recordSyncJobProcess("  ", metrics.startTimer());

        assertThat(registry.timer("aimindmap.sync.job", "outcome", "exception").count()).isEqualTo(1L);
    }

    // ---------- ④ AI 耗时与失败率 ----------

    @Test
    void aiCallSuccessRecordsTimerWithOperationAndModelTags() {
        long startedNanos = System.nanoTime() - Duration.ofMillis(1500).toNanos();

        metrics.recordAiCallSuccess(BusinessMetrics.AI_OP_JSON, "deepseek-chat", startedNanos);

        Timer timer = registry.timer("aimindmap.ai.call",
                "operation", "json", "model", "deepseek-chat", "outcome", "success");
        assertThat(timer.count()).isEqualTo(1L);
        assertThat(timer.totalTime(TimeUnit.MILLISECONDS)).isGreaterThanOrEqualTo(1500d);
        assertThat(registry.find("aimindmap.ai.call.failure").counter()).isNull();
    }

    @Test
    void aiCallFailureRecordsTimerAndFailureCounterWithErrorCode() {
        metrics.recordAiCallFailure(BusinessMetrics.AI_OP_CHAT, "deepseek-chat",
                "AI_TIMEOUT", metrics.startTimer());
        metrics.recordAiCallFailure(BusinessMetrics.AI_OP_CHAT, "deepseek-chat",
                "AI_TIMEOUT", metrics.startTimer());
        metrics.recordAiCallSuccess(BusinessMetrics.AI_OP_CHAT, "deepseek-chat", metrics.startTimer());

        assertThat(registry.timer("aimindmap.ai.call",
                "operation", "chat", "model", "deepseek-chat", "outcome", "failure").count()).isEqualTo(2L);
        assertThat(registry.counter("aimindmap.ai.call.failure",
                "operation", "chat", "model", "deepseek-chat", "error_code", "AI_TIMEOUT")
                .count()).isEqualTo(2.0);
        // 失败率 = failure / (success + failure) = 2/3，分子分母都在同一个 registry 里可算
        assertThat(registry.timer("aimindmap.ai.call",
                "operation", "chat", "model", "deepseek-chat", "outcome", "success").count()).isEqualTo(1L);
    }

    @Test
    void aiCallStreamOperationUsesDistinctTagValue() {
        metrics.recordAiCallFailure(BusinessMetrics.AI_OP_CHAT_STREAM, "deepseek-chat",
                "AI_STREAM_FAILED", metrics.startTimer());

        assertThat(registry.counter("aimindmap.ai.call.failure",
                "operation", "chat_stream", "model", "deepseek-chat",
                "error_code", "AI_STREAM_FAILED").count()).isEqualTo(1.0);
    }

    // ---------- ④ Embedding 耗时与失败率 ----------

    @Test
    void embeddingSuccessRecordsTimerWithOperationAndModel() {
        metrics.recordEmbeddingSuccess(BusinessMetrics.EMBEDDING_OP_INDEX,
                "text-embedding-v3", metrics.startTimer());

        assertThat(registry.timer("aimindmap.embedding.call",
                "operation", "index", "model", "text-embedding-v3", "outcome", "success")
                .count()).isEqualTo(1L);
        assertThat(registry.find("aimindmap.embedding.call.failure").counter()).isNull();
    }

    @Test
    void embeddingFailureRecordsTimerAndFailureCounter() {
        metrics.recordEmbeddingFailure(BusinessMetrics.EMBEDDING_OP_SEARCH,
                "text-embedding-v3", metrics.startTimer());

        assertThat(registry.timer("aimindmap.embedding.call",
                "operation", "search", "model", "text-embedding-v3", "outcome", "failure")
                .count()).isEqualTo(1L);
        assertThat(registry.counter("aimindmap.embedding.call.failure",
                "operation", "search", "model", "text-embedding-v3").count()).isEqualTo(1.0);
    }

    @Test
    void blankModelFallsBackToUnknownTagValue() {
        metrics.recordEmbeddingSuccess(BusinessMetrics.EMBEDDING_OP_MEMORY_UPSERT, "", metrics.startTimer());
        metrics.recordAiCallSuccess(BusinessMetrics.AI_OP_CHAT, null, metrics.startTimer());

        assertThat(registry.timer("aimindmap.embedding.call",
                "operation", "memory_upsert", "model", "unknown", "outcome", "success").count()).isEqualTo(1L);
        assertThat(registry.timer("aimindmap.ai.call",
                "operation", "chat", "model", "unknown", "outcome", "success").count()).isEqualTo(1L);
    }

    @Test
    void blankErrorCodeFallsBackToUnknownTagValue() {
        metrics.recordAiCallFailure(BusinessMetrics.AI_OP_JSON, "deepseek-chat", null, metrics.startTimer());

        assertThat(registry.counter("aimindmap.ai.call.failure",
                "operation", "json", "model", "deepseek-chat", "error_code", "unknown").count())
                .isEqualTo(1.0);
    }

    /**
     * 埋点铁律自查：全部 tag 键只允许 operation / model / outcome / error_code 四个低基数维度，
     * 严禁 userId / sessionId / jobId / 提示词正文类 tag；指标名统一 aimindmap.* 前缀。
     */
    @Test
    void tagKeysNeverCarryHighCardinalityOrPrivacyValues() {
        metrics.recordPdfUploadAccepted(metrics.startTimer());
        metrics.recordSyncJobProcess("ready", metrics.startTimer());
        metrics.recordAiCallSuccess(BusinessMetrics.AI_OP_CHAT, "deepseek-chat", metrics.startTimer());
        metrics.recordAiCallFailure(BusinessMetrics.AI_OP_JSON, "deepseek-chat",
                "AI_HTTP_429", metrics.startTimer());
        metrics.recordEmbeddingSuccess(BusinessMetrics.EMBEDDING_OP_MEMORY_SEARCH,
                "text-embedding-v3", metrics.startTimer());
        metrics.recordEmbeddingFailure(BusinessMetrics.EMBEDDING_OP_INDEX,
                "text-embedding-v3", metrics.startTimer());

        Set<String> tagKeys = registry.getMeters().stream()
                .flatMap(meter -> meter.getId().getTags().stream())
                .map(Tag::getKey)
                .collect(Collectors.toSet());

        assertThat(tagKeys).containsExactlyInAnyOrder("operation", "model", "outcome", "error_code");
        assertThat(tagKeys).doesNotContain("user_id", "userId", "session_id", "sessionId",
                "job_id", "jobId", "document_id", "uri", "prompt", "content");
        assertThat(registry.getMeters()).allSatisfy(meter ->
                assertThat(meter.getId().getName()).startsWith("aimindmap."));
    }
}
