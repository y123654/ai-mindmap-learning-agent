package com.example.aimindmap.assistant;

import com.example.aimindmap.assistant.SessionSummaryService.SummaryOutcome;
import com.example.aimindmap.metrics.MqTaskMetrics;
import com.example.aimindmap.mqsupport.MqTopology;
import com.example.aimindmap.mqsupport.RetriableTaskException;
import com.example.aimindmap.mqsupport.TaskMessage;
import com.example.aimindmap.mqsupport.TaskType;
import com.example.aimindmap.mqsupport.outbox.IdempotentConsumptionRepository;
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
 * 会话摘要任务 MQ 消费者（方案《阶段六》6-3），监听 aimindmap.queue.SESSION_SUMMARY。
 *
 * <p>处理结果由 {@link SessionSummaryService#handleSummaryMessage(long)} 返回：
 * SUMMARIZED/SKIPPED 为正常终态（幂等行已由 service 同事务写入），ack 不进 DLQ；
 * TRIGGER_MISSING（触发助手消息行已不存在，永久毒消息）error 后 nackNoRequeue 进 DLQ；
 * CONFIGURATION（NonTransientAiException / 4xx 配置类，含未配 Key 的 401/403）不落任何
 * 业务状态，warn 后 nackNoRequeue 进 DLQ 仅作运维信号。消息头/正文交叉校验失败、
 * 反序列化失败等永久毒消息首次即 nack 进 DLQ；{@link RetriableTaskException}
 * （TransientAiException、WebClient 429/5xx、数据库瞬时异常）走三档延时重试
 * （5s/30s/2m），耗尽进 DLQ。
 *
 * <p><b>本链路无任务表，设计取舍（所有者已确认）：</b>
 * <ul>
 *   <li>没有 Reconciler、没有 CAS/状态列：漏消费靠 outbox relay 兜底投递；
 *       处理失败靠三档重试 + DLQ；</li>
 *   <li>DLQ 耗尽后该轮摘要缺失由"下一轮成功触发时增量摘要天然补齐"自我愈合
 *       （批处理选取所有 id &gt; summarized_until_message_id 的旧消息）；</li>
 *   <li>消息永不 requeue 做状态复位（没有状态可复位）；仅在转发重试队列的 AMQP
 *       操作本身失败时 nackRequeue 原消息，等待 broker 重投。</li>
 * </ul>
 *
 * <p><b>"低优先级队列"落地方式：</b>使用阶段四已建好的 SESSION_SUMMARY 独立队列与
 * aiRabbitConnectionFactory/aiRabbitListenerContainerFactory（手动 ACK、并发 1~2），
 * 摘要积压天然不阻塞同步/出题/向量队列（各自独立消费者与连接通道）；不引入
 * x-max-priority——既有持久队列加优先级参数需要删队列重建，属拓扑变更，本阶段禁止。
 *
 * <p>仅在 app.mq.enabled=true（缺省即 true）时装配；关闭时由 {@link SessionSummaryWorker}
 * @Async + AFTER_COMMIT 事件兜底，行为与阶段 4 一致。
 *
 * <p>阶段八-1：每个终态分支在既有日志之后追加一次 {@link MqTaskMetrics} 记录
 * （task_type=SESSION_SUMMARY），只加记录、不改任何分支与 ack 语义，
 * tag 只用枚举级低基数值，不带 triggerMessageId / messageId。
 */
@Component
@ConditionalOnProperty(name = "app.mq.enabled", havingValue = "true", matchIfMissing = true)
public class SessionSummaryMessageConsumer {

    /** 三档重试上限：重试计数 0/1/2 转发 5s/30s/2m；计数 3 时第 4 次处理仍失败直接进 DLQ。 */
    static final int MAX_RETRY_COUNT = MqTopology.RetryTier.values().length;

    private static final Logger log = LoggerFactory.getLogger(SessionSummaryMessageConsumer.class);

    private final ObjectMapper objectMapper;
    private final RabbitTemplate rabbitTemplate;
    private final SessionSummaryService sessionSummaryService;
    private final IdempotentConsumptionRepository idempotentConsumptionRepository;
    private final MqTaskMetrics mqTaskMetrics;

    public SessionSummaryMessageConsumer(ObjectMapper objectMapper,
                                         RabbitTemplate rabbitTemplate,
                                         SessionSummaryService sessionSummaryService,
                                         IdempotentConsumptionRepository idempotentConsumptionRepository,
                                         MqTaskMetrics mqTaskMetrics) {
        this.objectMapper = objectMapper;
        this.rabbitTemplate = rabbitTemplate;
        this.sessionSummaryService = sessionSummaryService;
        this.idempotentConsumptionRepository = idempotentConsumptionRepository;
        this.mqTaskMetrics = mqTaskMetrics;
    }

    /** 摘要工作队列入口；队列名由 MqTopology 常量经 SpEL 派生，禁止硬编码。 */
    @RabbitListener(
            queues = "#{T(com.example.aimindmap.mqsupport.MqTopology)"
                    + ".queueName(T(com.example.aimindmap.mqsupport.TaskType).SESSION_SUMMARY)}",
            containerFactory = "aiRabbitListenerContainerFactory")
    public void onMessage(Message amqpMessage, Channel channel,
                          @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) {
        String queue = MqTopology.queueName(TaskType.SESSION_SUMMARY);
        // 阶段八-1：消费耗时起点（每个终态分支交回，见各 record* 调用）
        long startedNanos = mqTaskMetrics.startTimer();

        TaskMessage message;
        try {
            message = objectMapper.readValue(
                    new String(amqpMessage.getBody(), StandardCharsets.UTF_8), TaskMessage.class);
        } catch (Exception e) {
            int bodyLength = amqpMessage.getBody() == null ? 0 : amqpMessage.getBody().length;
            log.error("摘要任务消息反序列化失败，不可重试，进 DLQ queue={} deliveryTag={} bodyLength={}",
                    queue, deliveryTag, bodyLength, e);
            mqTaskMetrics.recordDeadLettered(TaskType.SESSION_SUMMARY,
                    MqTaskMetrics.DeadLetterReason.POISON_MESSAGE, startedNanos);
            nackNoRequeue(channel, deliveryTag);
            return;
        }

        MessageProperties properties = amqpMessage.getMessageProperties();
        String headerMessageId = properties.getMessageId();
        Long triggerMessageId = message.taskId();
        String expectedMessageId = TaskType.SESSION_SUMMARY.name() + ":" + triggerMessageId;
        if (message.taskType() != TaskType.SESSION_SUMMARY || triggerMessageId == null
                || headerMessageId == null || !headerMessageId.equals(expectedMessageId)) {
            log.error("摘要任务消息头与正文交叉校验失败，不可重试，进 DLQ queue={} deliveryTag={} "
                            + "headerMessageId={} bodyTaskType={} bodyTaskId={}",
                    queue, deliveryTag, headerMessageId, message.taskType(), triggerMessageId);
            mqTaskMetrics.recordDeadLettered(TaskType.SESSION_SUMMARY,
                    MqTaskMetrics.DeadLetterReason.POISON_MESSAGE, startedNanos);
            nackNoRequeue(channel, deliveryTag);
            return;
        }

        boolean alreadyConsumed;
        try {
            // 幂等预查：重复投递的核心防线，命中直接 ack，不调业务。
            alreadyConsumed = idempotentConsumptionRepository.existsById(expectedMessageId);
        } catch (DataAccessException e) {
            log.error("摘要任务幂等预查数据库异常，requeue 原消息待重投 triggerMessageId={} messageId={}",
                    triggerMessageId, headerMessageId, e);
            mqTaskMetrics.recordConsumed(TaskType.SESSION_SUMMARY,
                    MqTaskMetrics.ConsumeOutcome.REQUEUED, startedNanos);
            nackRequeue(channel, deliveryTag);
            return;
        }
        if (alreadyConsumed) {
            log.info("摘要任务幂等预查命中，直接 ack（重复投递） triggerMessageId={} messageId={}",
                    triggerMessageId, headerMessageId);
            mqTaskMetrics.recordConsumed(TaskType.SESSION_SUMMARY,
                    MqTaskMetrics.ConsumeOutcome.DUPLICATE, startedNanos);
            ack(channel, deliveryTag);
            return;
        }

        SummaryOutcome outcome;
        try {
            outcome = sessionSummaryService.handleSummaryMessage(triggerMessageId);
        } catch (DataIntegrityViolationException e) {
            // service 写幂等行唯一冲突：已有投递完成过本任务，直接 ack（重复投递竞态）。
            log.info("摘要任务重复投递命中 idempotent_consumptions，直接 ack triggerMessageId={} messageId={}",
                    triggerMessageId, headerMessageId);
            mqTaskMetrics.recordConsumed(TaskType.SESSION_SUMMARY,
                    MqTaskMetrics.ConsumeOutcome.DUPLICATE, startedNanos);
            ack(channel, deliveryTag);
            return;
        } catch (RetriableTaskException e) {
            handleRetryableFailure(amqpMessage, channel, deliveryTag, triggerMessageId,
                    headerMessageId, e.getReason(), e, startedNanos);
            return;
        } catch (DataAccessException e) {
            handleRetryableFailure(amqpMessage, channel, deliveryTag, triggerMessageId,
                    headerMessageId, "DataAccessException: " + e.getMessage(), e, startedNanos);
            return;
        } catch (RuntimeException e) {
            log.error("摘要任务发生未预期异常，进 DLQ triggerMessageId={} messageId={}",
                    triggerMessageId, headerMessageId, e);
            mqTaskMetrics.recordDeadLettered(TaskType.SESSION_SUMMARY,
                    MqTaskMetrics.DeadLetterReason.UNEXPECTED_ERROR, startedNanos);
            nackNoRequeue(channel, deliveryTag);
            return;
        }

        switch (outcome) {
            case SUMMARIZED, SKIPPED -> {
                log.info("摘要任务消费完成 triggerMessageId={} messageId={} outcome={}",
                        triggerMessageId, headerMessageId, outcome);
                mqTaskMetrics.recordConsumed(TaskType.SESSION_SUMMARY,
                        metricOutcomeOf(outcome), startedNanos);
                ack(channel, deliveryTag);
            }
            case TRIGGER_MISSING -> {
                // 指向的助手消息行已不存在：永久毒消息，重试无意义，进 DLQ 待运维排查。
                log.error("摘要任务触发消息不存在，不可重试，进 DLQ triggerMessageId={} messageId={}",
                        triggerMessageId, headerMessageId);
                mqTaskMetrics.recordDeadLettered(TaskType.SESSION_SUMMARY,
                        MqTaskMetrics.DeadLetterReason.TASK_MISSING, startedNanos);
                nackNoRequeue(channel, deliveryTag);
            }
            case CONFIGURATION -> {
                // AI 配置类失败（未配 Key/4xx）：不落业务状态、不重试，DLQ 仅作运维信号。
                log.warn("摘要任务配置类失败，消息进 DLQ 作运维信号 triggerMessageId={} messageId={}",
                        triggerMessageId, headerMessageId);
                mqTaskMetrics.recordDeadLettered(TaskType.SESSION_SUMMARY,
                        MqTaskMetrics.DeadLetterReason.CONFIGURATION_FAILURE, startedNanos);
                nackNoRequeue(channel, deliveryTag);
            }
        }
    }

    /**
     * 摘要终态 → 指标 outcome 映射（阶段八-1）：SUMMARIZED=消费成功、SKIPPED=业务跳过
     * （不足阈值/空白批次）、CONFIGURATION 与 TRIGGER_MISSING=转 DLQ。
     * 只是 tag 取值映射，不参与任何业务判定。
     */
    private static MqTaskMetrics.ConsumeOutcome metricOutcomeOf(SummaryOutcome outcome) {
        return switch (outcome) {
            case SUMMARIZED -> MqTaskMetrics.ConsumeOutcome.SUCCESS;
            case SKIPPED -> MqTaskMetrics.ConsumeOutcome.SKIPPED;
            case CONFIGURATION, TRIGGER_MISSING -> MqTaskMetrics.ConsumeOutcome.DEAD_LETTER;
        };
    }

    /**
     * AI 限流/5xx、数据库等基础设施临时异常：按消息头重试计数选 5s/30s/2m 三档；
     * 三档耗尽进 DLQ。摘要链路没有任务行，不做"转发前状态复位"，也永不 requeue 原消息
     * 做状态复位；仅在转发 AMQP 操作本身失败时 requeue 原消息等待 broker 重投。
     */
    private void handleRetryableFailure(Message amqpMessage, Channel channel, long deliveryTag,
                                        Long triggerMessageId, String messageId,
                                        String reason, Exception cause, long startedNanos) {
        int retryCount = parseRetryCount(amqpMessage.getMessageProperties().getHeaders());
        if (retryCount >= MAX_RETRY_COUNT) {
            Object xDeath = amqpMessage.getMessageProperties().getHeaders().get("x-death");
            log.error("摘要任务三档重试耗尽（第 {} 次处理仍失败），进 DLQ triggerMessageId={} messageId={} "
                            + "reason={} xDeath={}（本轮摘要缺失由下一轮成功触发时增量摘要天然补齐）",
                    retryCount + 1, triggerMessageId, messageId, reason, xDeath, cause);
            mqTaskMetrics.recordDeadLettered(TaskType.SESSION_SUMMARY,
                    MqTaskMetrics.DeadLetterReason.RETRIES_EXHAUSTED, startedNanos);
            nackNoRequeue(channel, deliveryTag);
            return;
        }

        MqTopology.RetryTier tier = MqTopology.RetryTier.values()[retryCount];
        try {
            forwardToRetryQueue(amqpMessage, retryCount + 1, tier);
        } catch (AmqpException e) {
            log.error("摘要任务转发延时重试队列失败，requeue 原消息 triggerMessageId={} messageId={} retryQueue={}",
                    triggerMessageId, messageId,
                    MqTopology.retryQueueName(TaskType.SESSION_SUMMARY, tier), e);
            mqTaskMetrics.recordConsumed(TaskType.SESSION_SUMMARY,
                    MqTaskMetrics.ConsumeOutcome.REQUEUED, startedNanos);
            nackRequeue(channel, deliveryTag);
            return;
        }
        log.info("摘要任务可重试失败，转发延时重试 triggerMessageId={} messageId={} retryCount={}->{} tier={} reason={}",
                triggerMessageId, messageId, retryCount, retryCount + 1, tier.suffix(), reason);
        mqTaskMetrics.recordConsumed(TaskType.SESSION_SUMMARY,
                MqTaskMetrics.ConsumeOutcome.RETRY_FORWARDED, startedNanos);
        ack(channel, deliveryTag);
    }

    /**
     * 经默认交换机（""）按重试队列名直投；保留原 body 与全部 headers（含 messageId、
     * x-death），仅覆盖重试计数头。TTL 到期后队列死信回 aimindmap.tasks 再入工作队列。
     */
    private void forwardToRetryQueue(Message original, int nextRetryCount,
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
        rabbitTemplate.send("", MqTopology.retryQueueName(TaskType.SESSION_SUMMARY, tier), retryMessage);
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
            log.warn("摘要任务重试计数头非法，按 0 处理 raw={}", raw);
            return 0;
        }
    }

    private void ack(Channel channel, long deliveryTag) {
        try {
            channel.basicAck(deliveryTag, false);
        } catch (IOException e) {
            log.error("摘要任务 basicAck 失败 deliveryTag={}", deliveryTag, e);
        }
    }

    private void nackNoRequeue(Channel channel, long deliveryTag) {
        try {
            channel.basicNack(deliveryTag, false, false);
        } catch (IOException e) {
            log.error("摘要任务 basicNack(no-requeue) 失败 deliveryTag={}", deliveryTag, e);
        }
    }

    private void nackRequeue(Channel channel, long deliveryTag) {
        try {
            channel.basicNack(deliveryTag, false, true);
        } catch (IOException e) {
            log.error("摘要任务 basicNack(requeue) 失败 deliveryTag={}", deliveryTag, e);
        }
    }
}
