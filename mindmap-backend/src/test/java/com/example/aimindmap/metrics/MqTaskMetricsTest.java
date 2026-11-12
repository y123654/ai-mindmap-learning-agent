package com.example.aimindmap.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.aimindmap.mqsupport.TaskType;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * 阶段八-1：MQ 任务指标组件单测（{@link SimpleMeterRegistry}，纯内存，不连 MQ/DB/Redis）。
 *
 * <p>断言的指标名与 tag 就是阶段八-2 Grafana 面板要引用的口径（见
 * {@code docs/阶段八-1-指标埋点完成汇报-20261019.md} 的指标名清单表），
 * 因此这里用字面量而不是复用主代码常量：常量写错时测试必须失败。
 */
class MqTaskMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final MqTaskMetrics metrics = new MqTaskMetrics(registry);

    @Test
    void enqueuedCounterIsTaggedByTaskTypeOnly() {
        metrics.recordEnqueued(TaskType.PDF_IMPORT);
        metrics.recordEnqueued(TaskType.PDF_IMPORT);
        metrics.recordEnqueued(TaskType.MINDMAP_SYNC);

        assertThat(registry.counter("aimindmap.task.enqueued",
                "task_type", "PDF_IMPORT").count()).isEqualTo(2.0);
        assertThat(registry.counter("aimindmap.task.enqueued",
                "task_type", "MINDMAP_SYNC").count()).isEqualTo(1.0);
        assertThat(registry.get("aimindmap.task.enqueued").counters())
                .allSatisfy(counter -> assertThat(counter.getId().getTags())
                        .extracting(Tag::getKey).containsExactly("task_type"));
    }

    @Test
    void consumedCounterAndConsumeTimerShareTaskTypeAndOutcomeTags() {
        metrics.recordConsumed(TaskType.QUESTION_GENERATE,
                MqTaskMetrics.ConsumeOutcome.FAILED_BUSINESS, metrics.startTimer());

        assertThat(registry.counter("aimindmap.task.consumed",
                "task_type", "QUESTION_GENERATE", "outcome", "failed_business").count()).isEqualTo(1.0);
        assertThat(registry.timer("aimindmap.task.consume",
                "task_type", "QUESTION_GENERATE", "outcome", "failed_business").count()).isEqualTo(1L);
    }

    @Test
    void deadLetterRecordsBothConsumedOutcomeAndReasonCounter() {
        metrics.recordDeadLettered(TaskType.VECTOR_UPSERT,
                MqTaskMetrics.DeadLetterReason.RETRIES_EXHAUSTED, metrics.startTimer());

        assertThat(registry.counter("aimindmap.task.dead.letter",
                "task_type", "VECTOR_UPSERT", "reason", "retries_exhausted").count()).isEqualTo(1.0);
        assertThat(registry.counter("aimindmap.task.consumed",
                "task_type", "VECTOR_UPSERT", "outcome", "dead_letter").count()).isEqualTo(1.0);
        assertThat(registry.timer("aimindmap.task.consume",
                "task_type", "VECTOR_UPSERT", "outcome", "dead_letter").count()).isEqualTo(1L);
    }

    @Test
    void consumeTimerRecordsElapsedTime() {
        long startedNanos = System.nanoTime() - Duration.ofMillis(120).toNanos();

        metrics.recordConsumed(TaskType.SESSION_SUMMARY, MqTaskMetrics.ConsumeOutcome.SUCCESS, startedNanos);

        Timer timer = registry.timer("aimindmap.task.consume",
                "task_type", "SESSION_SUMMARY", "outcome", "success");
        assertThat(timer.count()).isEqualTo(1L);
        assertThat(timer.totalTime(TimeUnit.MILLISECONDS)).isGreaterThanOrEqualTo(120d);
    }

    @Test
    void futureStartPointIsClampedToZeroAndNeverNegative() {
        long futureNanos = System.nanoTime() + Duration.ofSeconds(5).toNanos();

        metrics.recordConsumed(TaskType.VECTOR_DELETE, MqTaskMetrics.ConsumeOutcome.SKIPPED, futureNanos);

        Timer timer = registry.timer("aimindmap.task.consume",
                "task_type", "VECTOR_DELETE", "outcome", "skipped");
        assertThat(timer.totalTime(TimeUnit.NANOSECONDS)).isZero();
    }

    @Test
    void everyOutcomeAndReasonEnumValueIsRecordable() {
        for (MqTaskMetrics.ConsumeOutcome outcome : MqTaskMetrics.ConsumeOutcome.values()) {
            metrics.recordConsumed(TaskType.PDF_IMPORT, outcome, metrics.startTimer());
        }
        for (MqTaskMetrics.DeadLetterReason reason : MqTaskMetrics.DeadLetterReason.values()) {
            metrics.recordDeadLettered(TaskType.PDF_IMPORT, reason, metrics.startTimer());
        }

        assertThat(tagValues("outcome")).containsExactlyInAnyOrder(
                Arrays.stream(MqTaskMetrics.ConsumeOutcome.values())
                        .map(MqTaskMetrics.ConsumeOutcome::tagValue).toArray(String[]::new));
        assertThat(tagValues("reason")).containsExactlyInAnyOrder(
                Arrays.stream(MqTaskMetrics.DeadLetterReason.values())
                        .map(MqTaskMetrics.DeadLetterReason::tagValue).toArray(String[]::new));
    }

    /**
     * 埋点铁律自查：tag 键只允许 task_type / outcome / reason 三个枚举级维度，
     * 严禁 userId / sessionId / jobId / taskId / messageId 这类高基数或隐私值。
     */
    @Test
    void tagKeysNeverCarryHighCardinalityOrPrivacyValues() {
        metrics.recordEnqueued(TaskType.PDF_IMPORT);
        metrics.recordConsumed(TaskType.MINDMAP_SYNC, MqTaskMetrics.ConsumeOutcome.SUCCESS, metrics.startTimer());
        metrics.recordDeadLettered(TaskType.VECTOR_UPSERT,
                MqTaskMetrics.DeadLetterReason.POISON_MESSAGE, metrics.startTimer());

        Set<String> tagKeys = registry.getMeters().stream()
                .flatMap(meter -> meter.getId().getTags().stream())
                .map(Tag::getKey)
                .collect(Collectors.toSet());

        assertThat(tagKeys).containsExactlyInAnyOrder("task_type", "outcome", "reason");
        assertThat(tagKeys).doesNotContain("user_id", "userId", "session_id", "sessionId",
                "job_id", "jobId", "task_id", "taskId", "message_id", "messageId", "uri", "ip");
        assertThat(tagValues("task_type")).isSubsetOf(Arrays.stream(TaskType.values())
                .map(TaskType::wireName).collect(Collectors.toSet()));
        assertThat(registry.getMeters()).allSatisfy(meter ->
                assertThat(meter.getId().getName()).startsWith("aimindmap.task."));
    }

    private Set<String> tagValues(String key) {
        List<String> values = registry.getMeters().stream()
                .flatMap(meter -> meter.getId().getTags().stream())
                .filter(tag -> tag.getKey().equals(key))
                .map(Tag::getValue)
                .collect(Collectors.toList());
        return Set.copyOf(values);
    }
}
