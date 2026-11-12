package com.example.aimindmap.vectorindex;

import com.example.aimindmap.metrics.MqTaskMetrics;
import com.example.aimindmap.mqsupport.MqTopology;
import com.example.aimindmap.mqsupport.TaskMessage;
import com.example.aimindmap.mqsupport.TaskType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/**
 * 向量任务 MQ 消费者（方案《阶段四·C/D》）。
 *
 * <p>两个监听方法分别消费 VECTOR_UPSERT / VECTOR_DELETE 工作队列，手动 ACK，
 * 复用 {@link VectorIndexTaskProcessor}（业务处理零重写）与任务表/幂等表。
 * 仅在 app.mq.enabled=true（缺省即 true）时装配；关闭时由 VectorIndexWorker 轮询兜底。
 *
 * <p>不打消息正文、不打任何凭据；ERROR 必带 taskType/taskId/messageId 等业务上下文。
 *
 * <p>阶段八-1：{@link #handle} 的每个终态分支在既有日志之后追加一次 {@link MqTaskMetrics} 记录，
 * task_type 取 {@code VECTOR_UPSERT} / {@code VECTOR_DELETE}（与队列同名，天然区分两类向量任务），
 * 只加记录、不改任何分支与 ack 语义，tag 只用枚举级低基数值，不带 taskId / resourceId。
 */
@Component
@ConditionalOnProperty(name = "app.mq.enabled", havingValue = "true", matchIfMissing = true)
public class VectorIndexMessageConsumer {

    /**
     * 延时重试最大次数：消息头重试计数 0/1/2 分别转发 5s/30s/2m 三档；
     * 计数达到 3 表示三档均已用完（本次是第 4 次处理），再失败直接 nack 进 DLQ。
     */
    static final int MAX_RETRY_COUNT = MqTopology.RetryTier.values().length;

    private static final Logger log = LoggerFactory.getLogger(VectorIndexMessageConsumer.class);

    private final ObjectMapper objectMapper;
    private final RabbitTemplate rabbitTemplate;
    private final VectorIndexTaskService taskService;
    private final VectorIndexTaskProcessor processor;
    private final MqTaskMetrics mqTaskMetrics;

    public VectorIndexMessageConsumer(ObjectMapper objectMapper,
                                      RabbitTemplate rabbitTemplate,
                                      VectorIndexTaskService taskService,
                                      VectorIndexTaskProcessor processor,
                                      MqTaskMetrics mqTaskMetrics) {
        this.objectMapper = objectMapper;
        this.rabbitTemplate = rabbitTemplate;
        this.taskService = taskService;
        this.processor = processor;
        this.mqTaskMetrics = mqTaskMetrics;
    }

    /** UPSERT 工作队列入口；队列名由 MqTopology 常量经 SpEL 派生，禁止硬编码。 */
    @RabbitListener(
            queues = "#{T(com.example.aimindmap.mqsupport.MqTopology)"
                    + ".queueName(T(com.example.aimindmap.mqsupport.TaskType).VECTOR_UPSERT)}",
            containerFactory = "vectorRabbitListenerContainerFactory")
    public void onUpsert(Message message, Channel channel,
                         @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) {
        handle(message, channel, deliveryTag, TaskType.VECTOR_UPSERT);
    }

    /** DELETE 工作队列入口。 */
    @RabbitListener(
            queues = "#{T(com.example.aimindmap.mqsupport.MqTopology)"
                    + ".queueName(T(com.example.aimindmap.mqsupport.TaskType).VECTOR_DELETE)}",
            containerFactory = "vectorRabbitListenerContainerFactory")
    public void onDelete(Message message, Channel channel,
                         @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) {
        handle(message, channel, deliveryTag, TaskType.VECTOR_DELETE);
    }

    private void handle(Message amqpMessage, Channel channel, long deliveryTag,
                        TaskType expectedTaskType) {
        String queue = MqTopology.queueName(expectedTaskType);
        // 阶段八-1：消费耗时起点（两个监听方法共用本 handle，每个终态分支交回）
        long startedNanos = mqTaskMetrics.startTimer();

        TaskMessage message;
        try {
            message = objectMapper.readValue(
                    new String(amqpMessage.getBody(), StandardCharsets.UTF_8), TaskMessage.class);
        } catch (Exception e) {
            int bodyLength = amqpMessage.getBody() == null ? 0 : amqpMessage.getBody().length;
            log.error("向量任务消息反序列化失败，不可重试，进 DLQ queue={} deliveryTag={} bodyLength={}",
                    queue, deliveryTag, bodyLength, e);
            mqTaskMetrics.recordDeadLettered(expectedTaskType,
                    MqTaskMetrics.DeadLetterReason.POISON_MESSAGE, startedNanos);
            nackNoRequeue(channel, deliveryTag);
            return;
        }

        MessageProperties properties = amqpMessage.getMessageProperties();
        String headerMessageId = properties.getMessageId();
        Long taskId = message.taskId();
        String expectedMessageId = expectedTaskType.name() + ":" + taskId;
        if (message.taskType() != expectedTaskType || taskId == null
                || headerMessageId == null || !headerMessageId.equals(expectedMessageId)) {
            log.error("向量任务消息头与正文交叉校验失败，不可重试，进 DLQ queue={} deliveryTag={} "
                            + "headerMessageId={} bodyTaskType={} bodyTaskId={}",
                    queue, deliveryTag, headerMessageId, message.taskType(), taskId);
            mqTaskMetrics.recordDeadLettered(expectedTaskType,
                    MqTaskMetrics.DeadLetterReason.POISON_MESSAGE, startedNanos);
            nackNoRequeue(channel, deliveryTag);
            return;
        }

        boolean claimed;
        try {
            claimed = taskService.claimForMessage(taskId);
        } catch (DataAccessException e) {
            log.error("向量任务条件认领数据库异常，requeue 原消息待重投 taskType={} taskId={} messageId={}",
                    expectedTaskType, taskId, headerMessageId, e);
            mqTaskMetrics.recordConsumed(expectedTaskType,
                    MqTaskMetrics.ConsumeOutcome.REQUEUED, startedNanos);
            nackRequeue(channel, deliveryTag);
            return;
        }
        if (!claimed) {
            // 幂等三件套之一：0 行说明任务已 SUCCEEDED/已被其他实例认领，直接 ack
            log.info("向量任务状态条件认领 0 行，直接 ack（重复投递或已完成） taskType={} taskId={} messageId={}",
                    expectedTaskType, taskId, headerMessageId);
            mqTaskMetrics.recordConsumed(expectedTaskType,
                    MqTaskMetrics.ConsumeOutcome.DUPLICATE, startedNanos);
            ack(channel, deliveryTag);
            return;
        }

        VectorIndexTaskService.TaskLoad task = taskService.loadTask(taskId);
        if (task == null) {
            log.error("向量任务条件认领成功但任务行不存在，进 DLQ taskType={} taskId={} messageId={}",
                    expectedTaskType, taskId, headerMessageId);
            mqTaskMetrics.recordDeadLettered(expectedTaskType,
                    MqTaskMetrics.DeadLetterReason.TASK_MISSING, startedNanos);
            nackNoRequeue(channel, deliveryTag);
            return;
        }

        VectorIndexTaskProcessor.ProcessResult result;
        try {
            result = processor.process(task);
        } catch (VectorIndexPermanentException | IllegalArgumentException | IllegalStateException e) {
            // 资源不存在/非法参数/格式错误：重试无意义，首次即进 DLQ
            log.error("向量任务永久失败，不可重试，进 DLQ taskType={} taskId={} messageId={} "
                            + "resourceType={} resourceId={} chunkIndex={} op={} reason={}",
                    expectedTaskType, taskId, headerMessageId,
                    task.resourceType().wireName(), task.resourceId(), task.chunkIndex(),
                    task.operation(), e.getMessage(), e);
            safeMarkFailed(task, e.getMessage());
            mqTaskMetrics.recordDeadLettered(expectedTaskType,
                    MqTaskMetrics.DeadLetterReason.UNEXPECTED_ERROR, startedNanos);
            nackNoRequeue(channel, deliveryTag);
            return;
        } catch (VectorIndexExternalException e) {
            handleRetryableFailure(amqpMessage, channel, deliveryTag, expectedTaskType,
                    headerMessageId, task, e, startedNanos);
            return;
        } catch (Exception e) {
            log.error("向量任务发生未预期异常，进 DLQ taskType={} taskId={} messageId={} "
                            + "resourceType={} resourceId={} chunkIndex={} op={}",
                    expectedTaskType, taskId, headerMessageId,
                    task.resourceType().wireName(), task.resourceId(), task.chunkIndex(),
                    task.operation(), e);
            safeMarkFailed(task, e.toString());
            mqTaskMetrics.recordDeadLettered(expectedTaskType,
                    MqTaskMetrics.DeadLetterReason.UNEXPECTED_ERROR, startedNanos);
            nackNoRequeue(channel, deliveryTag);
            return;
        }

        try {
            taskService.completeFromMessage(task, result, headerMessageId);
        } catch (DataIntegrityViolationException e) {
            // 幂等三件套之三：唯一冲突说明已有投递完成过本任务，直接 ack
            log.info("向量任务重复投递命中 idempotent_consumptions，直接 ack taskType={} taskId={} messageId={}",
                    expectedTaskType, taskId, headerMessageId);
            mqTaskMetrics.recordConsumed(expectedTaskType,
                    MqTaskMetrics.ConsumeOutcome.DUPLICATE, startedNanos);
            ack(channel, deliveryTag);
            return;
        } catch (DataAccessException e) {
            // 结果落库失败：消息重投后条件认领大概率 0 行直接 ack，卡死的 PROCESSING 行由 60s 对账兜底
            log.error("向量任务成功结果落库失败，requeue 原消息 taskType={} taskId={} messageId={}",
                    expectedTaskType, taskId, headerMessageId, e);
            mqTaskMetrics.recordConsumed(expectedTaskType,
                    MqTaskMetrics.ConsumeOutcome.REQUEUED, startedNanos);
            nackRequeue(channel, deliveryTag);
            return;
        }
        log.info("向量任务消费成功 taskType={} taskId={} messageId={} result={}",
                expectedTaskType, taskId, headerMessageId, result.getClass().getSimpleName());
        // Skipped = 内容哈希未变化的业务跳过（不调 embedding、不写 Qdrant），其余为真实成功
        mqTaskMetrics.recordConsumed(expectedTaskType,
                result instanceof VectorIndexTaskProcessor.ProcessResult.Skipped
                        ? MqTaskMetrics.ConsumeOutcome.SKIPPED
                        : MqTaskMetrics.ConsumeOutcome.SUCCESS,
                startedNanos);
        ack(channel, deliveryTag);
    }

    private void handleRetryableFailure(Message amqpMessage, Channel channel, long deliveryTag,
                                        TaskType taskType, String messageId,
                                        VectorIndexTaskService.TaskLoad task,
                                        VectorIndexExternalException cause, long startedNanos) {
        int retryCount = parseRetryCount(amqpMessage.getMessageProperties().getHeaders());
        if (retryCount >= MAX_RETRY_COUNT) {
            Object xDeath = amqpMessage.getMessageProperties().getHeaders().get("x-death");
            log.error("向量任务三档重试耗尽（第 {} 次处理仍失败），进 DLQ taskType={} taskId={} messageId={} "
                            + "resourceType={} resourceId={} reason={} xDeath={}",
                    retryCount + 1, taskType, task.taskId(), messageId,
                    task.resourceType().wireName(), task.resourceId(),
                    cause.getMessage(), xDeath, cause);
            safeMarkFailed(task, cause.getMessage());
            mqTaskMetrics.recordDeadLettered(taskType,
                    MqTaskMetrics.DeadLetterReason.RETRIES_EXHAUSTED, startedNanos);
            nackNoRequeue(channel, deliveryTag);
            return;
        }

        // 先把任务行复位为 PENDING，重试消息到期回来才能被再次条件认领
        try {
            taskService.requeueForMessageRetry(task.taskId(), cause.getMessage());
        } catch (DataAccessException e) {
            log.error("向量任务重试前任务行复位失败，requeue 原消息 taskType={} taskId={} messageId={} retryCount={}",
                    taskType, task.taskId(), messageId, retryCount, e);
            mqTaskMetrics.recordConsumed(taskType,
                    MqTaskMetrics.ConsumeOutcome.REQUEUED, startedNanos);
            nackRequeue(channel, deliveryTag);
            return;
        }

        MqTopology.RetryTier tier = MqTopology.RetryTier.values()[retryCount];
        try {
            forwardToRetryQueue(amqpMessage, taskType, retryCount + 1, tier);
        } catch (AmqpException e) {
            log.error("向量任务转发延时重试队列失败，requeue 原消息 taskType={} taskId={} messageId={} retryQueue={}",
                    taskType, task.taskId(), messageId,
                    MqTopology.retryQueueName(taskType, tier), e);
            mqTaskMetrics.recordConsumed(taskType,
                    MqTaskMetrics.ConsumeOutcome.REQUEUED, startedNanos);
            nackRequeue(channel, deliveryTag);
            return;
        }
        log.info("向量任务可重试失败，转发延时重试 taskType={} taskId={} messageId={} retryCount={}->{} tier={} reason={}",
                taskType, task.taskId(), messageId, retryCount, retryCount + 1,
                tier.suffix(), cause.getMessage());
        mqTaskMetrics.recordConsumed(taskType,
                MqTaskMetrics.ConsumeOutcome.RETRY_FORWARDED, startedNanos);
        ack(channel, deliveryTag);
    }

    /**
     * 经默认交换机（""）按重试队列名直投；保留原 body 与全部 headers（含 messageId、
     * x-death），仅覆盖重试计数头。TTL 到期后队列死信回 aimindmap.tasks 再入工作队列。
     */
    private void forwardToRetryQueue(Message original, TaskType taskType, int nextRetryCount,
                                     MqTopology.RetryTier tier) {
        MessageProperties source = original.getMessageProperties();
        MessageProperties retryProperties = new MessageProperties();
        retryProperties.setMessageId(source.getMessageId());
        if (source.getContentType() != null) {
            retryProperties.setContentType(source.getContentType());
        }
        if (source.getContentEncoding() != null) {
            retryProperties.setContentEncoding(source.getContentEncoding());
        }
        retryProperties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        if (source.getHeaders() != null) {
            retryProperties.getHeaders().putAll(source.getHeaders());
        }
        retryProperties.setHeader(MqTopology.RETRY_COUNT_HEADER, nextRetryCount);
        Message retryMessage = new Message(original.getBody(), retryProperties);
        rabbitTemplate.send("", MqTopology.retryQueueName(taskType, tier), retryMessage);
    }

    private void safeMarkFailed(VectorIndexTaskService.TaskLoad task, String error) {
        try {
            taskService.markFailed(task, error);
        } catch (DataAccessException e) {
            log.error("失败向量任务置 FAILED 又遇数据库异常 taskId={} resourceId={}",
                    task.taskId(), task.resourceId(), e);
        }
    }

    /** 读取已重试次数：缺失/非法按 0 处理（首次投递）。 */
    static int parseRetryCount(Map<String, Object> headers) {
        Object raw = headers.get(MqTopology.RETRY_COUNT_HEADER);
        if (raw == null) {
            return 0;
        }
        if (raw instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(raw.toString().trim());
        } catch (NumberFormatException e) {
            log.warn("向量任务重试计数头非法，按 0 处理 raw={}", raw);
            return 0;
        }
    }

    private void ack(Channel channel, long deliveryTag) {
        try {
            channel.basicAck(deliveryTag, false);
        } catch (IOException e) {
            log.error("向量任务 basicAck 失败 deliveryTag={}", deliveryTag, e);
        }
    }

    private void nackNoRequeue(Channel channel, long deliveryTag) {
        try {
            channel.basicNack(deliveryTag, false, false);
        } catch (IOException e) {
            log.error("向量任务 basicNack(no-requeue) 失败 deliveryTag={}", deliveryTag, e);
        }
    }

    private void nackRequeue(Channel channel, long deliveryTag) {
        try {
            channel.basicNack(deliveryTag, false, true);
        } catch (IOException e) {
            log.error("向量任务 basicNack(requeue) 失败 deliveryTag={}", deliveryTag, e);
        }
    }
}
