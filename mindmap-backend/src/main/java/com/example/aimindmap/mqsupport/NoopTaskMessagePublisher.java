package com.example.aimindmap.mqsupport;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * app.mq.enabled=false 时的退化实现：不连接 RabbitMQ（§3.5 开关与降级）。
 *
 * <p>消息不发出，后台任务继续由现有轮询机制兜底执行；返回 {@link PublishOutcome.Skipped}
 * 而不是 Acknowledged，避免阶段 4-2 OutboxRelay 误把未投递消息标记为 SENT。
 * 日志只打 taskType/taskId，不打消息正文。
 */
@Component
@ConditionalOnProperty(name = "app.mq.enabled", havingValue = "false")
public class NoopTaskMessagePublisher implements TaskMessagePublisher {

    private static final Logger log = LoggerFactory.getLogger(NoopTaskMessagePublisher.class);

    @Override
    public PublishOutcome publish(TaskMessage message) {
        String messageId = message.taskType().name() + ":" + message.taskId();
        log.info("MQ 已关闭，跳过消息投递，任务由轮询兜底 taskType={} taskId={} messageId={}",
                message.taskType(), message.taskId(), messageId);
        return new PublishOutcome.Skipped(messageId,
                "mq disabled (app.mq.enabled=false), polling fallback");
    }
}
