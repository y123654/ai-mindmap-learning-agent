package com.example.aimindmap.mqsupport;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * RabbitMQ 发布实现（方案《阶段四·A》）。
 *
 * <p>消息发往持久化 topic 交换机 {@link MqTopology#TASK_EXCHANGE}，
 * routing key 为 aimindmap.task.&lt;TaskType 原名&gt;；消息体 Jackson JSON（UTF-8），
 * deliveryMode=2 持久化，messageId 固定为 "taskType:taskId"（阶段 4-C 幂等键）。
 *
 * <p>confirm 语义：依赖 spring.rabbitmq.publisher-confirm-type=correlated，
 * 用 {@link CorrelationData} 同步等待 broker ack/nack；ack 返回
 * {@link PublishOutcome.Acknowledged}，nack/超时/发送异常返回
 * {@link PublishOutcome.Rejected}（不抛异常），阶段 4-2 OutboxRelay 据此置
 * SENT 或累加 attempts。日志只打 taskType/taskId/routingKey/reason，不打消息正文。
 */
@Component
@ConditionalOnProperty(name = "app.mq.enabled", havingValue = "true", matchIfMissing = true)
public class RabbitTaskMessagePublisher implements TaskMessagePublisher {

    /**
     * confirm 同步等待上限。附录 A 配置清单没有该项，故用代码常量，不进 application.yml；
     * 包内构造器允许测试传入更短超时。
     */
    static final Duration DEFAULT_CONFIRM_TIMEOUT = Duration.ofSeconds(5);

    private static final Logger log = LoggerFactory.getLogger(RabbitTaskMessagePublisher.class);

    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;
    private final Duration confirmTimeout;

    @Autowired
    public RabbitTaskMessagePublisher(RabbitTemplate rabbitTemplate, ObjectMapper objectMapper) {
        this(rabbitTemplate, objectMapper, DEFAULT_CONFIRM_TIMEOUT);
    }

    RabbitTaskMessagePublisher(RabbitTemplate rabbitTemplate, ObjectMapper objectMapper,
                               Duration confirmTimeout) {
        this.rabbitTemplate = rabbitTemplate;
        this.objectMapper = objectMapper;
        this.confirmTimeout = confirmTimeout;
    }

    @Override
    public PublishOutcome publish(TaskMessage taskMessage) {
        TaskType taskType = taskMessage.taskType();
        Long taskId = taskMessage.taskId();
        String messageId = taskType.name() + ":" + taskId;
        String routingKey = MqTopology.routingKey(taskType);

        Message message;
        try {
            byte[] body = objectMapper.writeValueAsBytes(taskMessage);
            MessageProperties properties = new MessageProperties();
            properties.setMessageId(messageId);
            properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
            properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
            properties.setContentEncoding(StandardCharsets.UTF_8.name());
            message = new Message(body, properties);
        } catch (JsonProcessingException e) {
            log.error("MQ 消息序列化失败，未投递 taskType={} taskId={} routingKey={}",
                    taskType, taskId, routingKey, e);
            return new PublishOutcome.Rejected(messageId, "serialize failed: " + e.getMessage());
        }

        CorrelationData correlationData = new CorrelationData(messageId);
        try {
            rabbitTemplate.send(MqTopology.TASK_EXCHANGE, routingKey, message, correlationData);
        } catch (AmqpException e) {
            log.error("MQ 消息发送异常 taskType={} taskId={} routingKey={} messageId={}",
                    taskType, taskId, routingKey, messageId, e);
            return new PublishOutcome.Rejected(messageId, "send failed: " + e.getMessage());
        }

        try {
            CorrelationData.Confirm confirm = correlationData.getFuture()
                    .get(confirmTimeout.toMillis(), TimeUnit.MILLISECONDS);
            if (confirm != null && confirm.isAck()) {
                log.debug("MQ 消息 confirm ack taskType={} taskId={} messageId={}",
                        taskType, taskId, messageId);
                return new PublishOutcome.Acknowledged(messageId);
            }
            String reason = confirm == null
                    ? "broker confirm returned null"
                    : "broker nack" + (confirm.getReason() == null ? "" : ": " + confirm.getReason());
            log.warn("MQ 消息 confirm 拒绝 taskType={} taskId={} messageId={} reason={}",
                    taskType, taskId, messageId, reason);
            return new PublishOutcome.Rejected(messageId, reason);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("等待 MQ confirm 被中断 taskType={} taskId={} messageId={}",
                    taskType, taskId, messageId, e);
            return new PublishOutcome.Rejected(messageId, "confirm interrupted");
        } catch (ExecutionException e) {
            log.error("等待 MQ confirm 异常 taskType={} taskId={} messageId={}",
                    taskType, taskId, messageId, e);
            return new PublishOutcome.Rejected(messageId, "confirm failed: " + e.getMessage());
        } catch (TimeoutException e) {
            log.warn("等待 MQ confirm 超时（{}ms） taskType={} taskId={} messageId={}",
                    confirmTimeout.toMillis(), taskType, taskId, messageId);
            return new PublishOutcome.Rejected(messageId,
                    "confirm timeout after " + confirmTimeout.toMillis() + "ms");
        }
    }
}
