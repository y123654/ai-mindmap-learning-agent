package com.example.aimindmap.mqsupport;

import java.time.Duration;

/**
 * MQ 拓扑命名规则（方案《阶段四·A》）。
 *
 * <p>交换机/队列统一 aimindmap. 前缀；每类任务一个工作队列、一条 routing key。
 * 本类只放命名常量与派生规则，不声明 Bean；4-3 消费者按这里的规则定位队列。
 *
 * <p>三档重试队列命名见 {@link #retryQueueName}（2026-10-16 所有者拍板方案 A：
 * 每类任务 3 个专属重试队列，队列名自带任务与档位）。
 */
public final class MqTopology {

    /** 任务交换机：持久化 topic，6 个工作队列按 routing key 绑定。 */
    public static final String TASK_EXCHANGE = "aimindmap.tasks";

    /** 统一死信交换机：持久化 fanout，不可重试消息的最终路由。 */
    public static final String DEAD_LETTER_EXCHANGE = "aimindmap.dlx";

    /** 统一死信队列：aimindmap.dlx 的唯一绑定队列。 */
    public static final String DEAD_LETTER_QUEUE = "aimindmap.dlq";

    /**
     * 消费者消息头：本消息已经走过的延时重试次数（方案《阶段四·C》4-3）。
     * 首次投递没有该头，按 0 处理；每转发一次延时重试队列就 +1。
     * 消费者据此选档：0→{@link RetryTier#FIRST}（5s）、1→{@link RetryTier#SECOND}（30s）、
     * 2→{@link RetryTier#THIRD}（2m）；达到 3 表示三档都已用完，第 4 次处理仍失败时
     * nack(requeue=false) 经工作队列 DLX 进 DLQ。转发时保留原 body 与全部其他 headers。
     */
    public static final String RETRY_COUNT_HEADER = "x-aimindmap-retry-count";

    private static final String QUEUE_PREFIX = "aimindmap.queue.";
    private static final String ROUTING_KEY_PREFIX = "aimindmap.task.";
    private static final String RETRY_QUEUE_PREFIX = "aimindmap.retry.";

    private MqTopology() {
    }

    /** 工作队列名，如 aimindmap.queue.VECTOR_UPSERT。 */
    public static String queueName(TaskType taskType) {
        return QUEUE_PREFIX + taskType.wireName();
    }

    /** 绑定/发布 routing key，如 aimindmap.task.VECTOR_UPSERT。 */
    public static String routingKey(TaskType taskType) {
        return ROUTING_KEY_PREFIX + taskType.wireName();
    }

    /**
     * 重试队列名（方案 A：每类任务 3 个专属重试队列），
     * 如 aimindmap.retry.VECTOR_UPSERT.5s，队列名自带任务与档位信息。
     */
    public static String retryQueueName(TaskType taskType, RetryTier tier) {
        return RETRY_QUEUE_PREFIX + taskType.wireName() + "." + tier.suffix;
    }

    /**
     * 三档延时重试（方案《阶段四·A》：TTL + 死信转发，最多 3 档后进 DLQ）。
     * 4-3 消费者按消息已重试次数选档；本小阶段只用于声明拓扑。
     */
    public enum RetryTier {

        FIRST("5s", Duration.ofSeconds(5)),
        SECOND("30s", Duration.ofSeconds(30)),
        THIRD("2m", Duration.ofMinutes(2));

        private final String suffix;
        private final Duration delay;

        RetryTier(String suffix, Duration delay) {
            this.suffix = suffix;
            this.delay = delay;
        }

        /** 队列名后缀。 */
        public String suffix() {
            return suffix;
        }

        /** x-message-ttl 参数值（毫秒）。 */
        public long ttlMillis() {
            return delay.toMillis();
        }
    }
}
