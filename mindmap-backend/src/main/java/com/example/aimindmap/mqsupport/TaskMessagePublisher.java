package com.example.aimindmap.mqsupport;

/**
 * 后台任务消息发布抽象（方案《阶段四·A》，用于隔离 MQ 中间件）。
 *
 * <p>两个实现按 app.mq.enabled 条件装配：开启时 {@link RabbitTaskMessagePublisher}，
 * 关闭时 {@link NoopTaskMessagePublisher}。阶段 4-2 的 OutboxRelay 只依赖本接口。
 */
public interface TaskMessagePublisher {

    /**
     * 发布一条任务消息并同步等待 publisher confirm。
     *
     * <p>发送类失败不抛给调用方，统一封装为 {@link PublishOutcome.Rejected}，
     * 以便 OutboxRelay 无 try/catch 地按结果推进 outbox 状态；
     * 仅编程错误（如 message 为 null）可能抛运行时异常。
     *
     * @return 投递确认结果（ack / nack / skipped）
     */
    PublishOutcome publish(TaskMessage message);
}
