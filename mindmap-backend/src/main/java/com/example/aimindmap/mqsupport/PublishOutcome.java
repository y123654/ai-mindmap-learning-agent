package com.example.aimindmap.mqsupport;

/**
 * 消息投递确认结果（同步 publisher confirm 语义）。
 *
 * <p>留给阶段 4-2 OutboxRelay 的消费约定（本小阶段不实现 Relay）：
 * <ul>
 *   <li>{@link Acknowledged}：broker confirm ack，outbox 置 SENT；</li>
 *   <li>{@link Rejected}：broker nack / confirm 超时 / 发送或序列化异常，
 *       attempts+1 并记录 last_error，达到 outbox-max-attempts 后置 DEAD；</li>
 *   <li>{@link Skipped}：app.mq.enabled=false 时 Noop 退化，消息未发出但属预期降级，
 *       Relay 应保持 PENDING 且不累加失败次数，任务继续由现有轮询兜底执行。</li>
 * </ul>
 */
public sealed interface PublishOutcome
        permits PublishOutcome.Acknowledged, PublishOutcome.Rejected, PublishOutcome.Skipped {

    /** 幂等键，格式 taskType:taskId，与 AMQP messageId 保持一致。 */
    String messageId();

    /** broker 是否确认接收；仅 {@link Acknowledged} 为 true。 */
    boolean accepted();

    /** broker 已确认接收。 */
    record Acknowledged(String messageId) implements PublishOutcome {
        @Override
        public boolean accepted() {
            return true;
        }
    }

    /** broker 拒绝 / confirm 超时 / 发送异常；reason 写入 outbox.last_error。 */
    record Rejected(String messageId, String reason) implements PublishOutcome {
        @Override
        public boolean accepted() {
            return false;
        }
    }

    /** MQ 关闭时的预期跳过（Noop 退化，轮询兜底）。 */
    record Skipped(String messageId, String reason) implements PublishOutcome {
        @Override
        public boolean accepted() {
            return false;
        }
    }
}
