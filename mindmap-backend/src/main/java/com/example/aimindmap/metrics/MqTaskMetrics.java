package com.example.aimindmap.metrics;

import com.example.aimindmap.mqsupport.TaskType;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * 阶段八-1：MQ 后台任务业务指标（方案《学习Agent综合升级方案》325 行第 1 项
 * 「各任务入队/消费/失败/死信计数与耗时」的唯一埋点出口）。
 *
 * <p>集中式组件（方案交付建议）：五个消费者 + {@code OutboxEventService} 都注入本类，
 * 不在业务代码里散落 {@code MeterRegistry} 调用，指标名与 tag 只在这里定义一次。
 *
 * <p><b>指标清单（Prometheus 名 = 点名中的点换下划线，Counter 追加 {@code _total}）：</b>
 * <ul>
 *   <li>{@code aimindmap.task.enqueued}（Counter，tag {@code task_type}）：入队计数，
 *       埋点位置 {@code OutboxEventService#recordPending}（业务事务内写 outbox = 入队口径）；</li>
 *   <li>{@code aimindmap.task.consumed}（Counter，tag {@code task_type} + {@code outcome}）：
 *       消费结果计数，成功/业务跳过/业务失败/重复投递/转延时重试/requeue/转死信七态；</li>
 *   <li>{@code aimindmap.task.consume}（Timer，tag 同上）：单次消费耗时（P50/P95 用）；</li>
 *   <li>{@code aimindmap.task.dead.letter}（Counter，tag {@code task_type} + {@code reason}）：
 *       转 DLQ 计数，埋在每个 {@code basicNack(requeue=false)} 之前。</li>
 * </ul>
 *
 * <p><b>埋点铁律：</b>tag 只允许 {@code task_type}（{@link TaskType#wireName()}，6 个枚举值）、
 * {@code outcome}（7 个枚举值）、{@code reason}（5 个枚举值）这类低基数枚举值，
 * 严禁 taskId / jobId / messageId / userId 等高基数或隐私值；所有方法都是纯记录，
 * 不改变任何业务分支、不吞异常、不打印日志正文（指标失败也不能影响任务处理，
 * 因此这里刻意不加 try/catch —— micrometer 的记录路径不抛业务异常）。
 */
@Component
public class MqTaskMetrics {

    static final String ENQUEUED_NAME = "aimindmap.task.enqueued";
    static final String CONSUMED_NAME = "aimindmap.task.consumed";
    static final String CONSUME_DURATION_NAME = "aimindmap.task.consume";
    static final String DEAD_LETTER_NAME = "aimindmap.task.dead.letter";

    static final String TAG_TASK_TYPE = "task_type";
    static final String TAG_OUTCOME = "outcome";
    static final String TAG_REASON = "reason";

    private final MeterRegistry registry;

    public MqTaskMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /**
     * 消费结果（对应五个消费者里既有的终态分支，一一映射，不新增任何业务分支）。
     * <ul>
     *   <li>{@link #SUCCESS}：业务终态达成（READY / COMPLETED / DRAFT_READY / SUMMARIZED / 向量成功落库）；</li>
     *   <li>{@link #SKIPPED}：业务跳过（EARLY_EXIT、摘要 SKIPPED、向量内容未变化 Skipped）；</li>
     *   <li>{@link #FAILED_BUSINESS}：任务已落 FAILED 业务终态后 ack（同步/出题/PDF 的业务失败）；</li>
     *   <li>{@link #DUPLICATE}：重复投递被幂等防线拦下（条件认领 0 行 / 幂等行唯一冲突 / 摘要幂等预查命中）；</li>
     *   <li>{@link #RETRY_FORWARDED}：可重试失败已转发 5s/30s/2m 延时队列；</li>
     *   <li>{@link #REQUEUED}：基础设施异常，basicNack(requeue=true) 等 broker 重投原消息；</li>
     *   <li>{@link #DEAD_LETTER}：转 DLQ（由 {@link #recordDeadLettered} 记录，附带 reason）。</li>
     * </ul>
     */
    public enum ConsumeOutcome {
        SUCCESS,
        SKIPPED,
        FAILED_BUSINESS,
        DUPLICATE,
        RETRY_FORWARDED,
        REQUEUED,
        DEAD_LETTER;

        /** tag 值统一小写（Prometheus/Grafana 习惯），枚举名本身不变。 */
        public String tagValue() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** 转 DLQ 的原因（对应各消费者既有的 ERROR 日志分支，全部是固定枚举，无高基数值）。 */
    public enum DeadLetterReason {
        /** 反序列化失败 / 消息头与正文交叉校验不符：永久毒消息。 */
        POISON_MESSAGE,
        /** 消息指向的任务行不存在（或摘要触发消息不存在）。 */
        TASK_MISSING,
        /** 三档延时重试耗尽仍失败。 */
        RETRIES_EXHAUSTED,
        /** AI 未配置 / 4xx 配置类失败：任务已落 FAILED，DLQ 仅作运维信号。 */
        CONFIGURATION_FAILURE,
        /** 未预期异常（含向量任务的永久性业务异常）。 */
        UNEXPECTED_ERROR;

        public String tagValue() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** 计时起点（纳秒）：调用方在消费入口取一次，终态处交回，避免业务代码引入 micrometer 类型。 */
    public long startTimer() {
        return System.nanoTime();
    }

    /** 入队计数：业务事务内写 outbox 成功即计一次（325 行「入队」）。 */
    public void recordEnqueued(TaskType taskType) {
        registry.counter(ENQUEUED_NAME, TAG_TASK_TYPE, taskType.wireName()).increment();
    }

    /** 消费结果计数 + 消费耗时（325 行「消费/失败计数与耗时」）。 */
    public void recordConsumed(TaskType taskType, ConsumeOutcome outcome, long startedNanos) {
        String type = taskType.wireName();
        String outcomeTag = outcome.tagValue();
        registry.counter(CONSUMED_NAME, TAG_TASK_TYPE, type, TAG_OUTCOME, outcomeTag).increment();
        registry.timer(CONSUME_DURATION_NAME, TAG_TASK_TYPE, type, TAG_OUTCOME, outcomeTag)
                .record(elapsed(startedNanos));
    }

    /** 转 DLQ：既记 consumed{outcome=dead_letter} + 耗时，也记独立的 dead.letter{reason}（325 行「死信」）。 */
    public void recordDeadLettered(TaskType taskType, DeadLetterReason reason, long startedNanos) {
        recordConsumed(taskType, ConsumeOutcome.DEAD_LETTER, startedNanos);
        registry.counter(DEAD_LETTER_NAME, TAG_TASK_TYPE, taskType.wireName(),
                TAG_REASON, reason.tagValue()).increment();
    }

    private static Duration elapsed(long startedNanos) {
        return Duration.ofNanos(Math.max(0L, System.nanoTime() - startedNanos));
    }
}
