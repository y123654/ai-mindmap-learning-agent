package com.example.aimindmap.importpdf;

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
 * PDF 导入 MQ 消费者（方案《阶段五》），监听 aimindmap.queue.PDF_IMPORT。
 *
 * <p>复用 {@link PdfImportService#process(Long)}（业务解析零复制）：DRAFT_READY/SCANNED/
 * FAILED（加密/损坏等）都是业务终态，落终态后 ack，绝不进 DLQ。
 * 消息头/正文交叉校验、反序列化错误、taskType/messageId 不符、任务行不存在等永久毒消息
 * 首次即 nack 进 DLQ；数据库临时异常走三档延时重试（5s/30s/2m），耗尽进 DLQ。
 * 仅在 app.mq.enabled=true（缺省即 true）时装配；关闭时由 {@link PdfImportWorker} 轮询兜底。
 * 容器并发严格为 1（pdfRabbitListenerContainerFactory），保证单并发与队列 FIFO。
 *
 * <p>阶段八-1：每个终态分支在既有日志之后追加一次 {@link MqTaskMetrics} 记录（task_type=PDF_IMPORT），
 * 只加记录、不改任何分支与 ack 语义，tag 只用枚举级低基数值，不带 jobId / messageId。
 */
@Component
@ConditionalOnProperty(name = "app.mq.enabled", havingValue = "true", matchIfMissing = true)
public class PdfImportMessageConsumer {

    /** 三档重试上限：重试计数 0/1/2 转发 5s/30s/2m；计数 3 时第 4 次处理仍失败直接进 DLQ。 */
    static final int MAX_RETRY_COUNT = MqTopology.RetryTier.values().length;

    private static final Logger log = LoggerFactory.getLogger(PdfImportMessageConsumer.class);

    private final ObjectMapper objectMapper;
    private final RabbitTemplate rabbitTemplate;
    private final PdfImportService pdfImportService;
    private final MqTaskMetrics mqTaskMetrics;

    public PdfImportMessageConsumer(ObjectMapper objectMapper,
                                    RabbitTemplate rabbitTemplate,
                                    PdfImportService pdfImportService,
                                    MqTaskMetrics mqTaskMetrics) {
        this.objectMapper = objectMapper;
        this.rabbitTemplate = rabbitTemplate;
        this.pdfImportService = pdfImportService;
        this.mqTaskMetrics = mqTaskMetrics;
    }

    /** PDF 工作队列入口；队列名由 MqTopology 常量经 SpEL 派生，禁止硬编码。 */
    @RabbitListener(
            queues = "#{T(com.example.aimindmap.mqsupport.MqTopology)"
                    + ".queueName(T(com.example.aimindmap.mqsupport.TaskType).PDF_IMPORT)}",
            containerFactory = "pdfRabbitListenerContainerFactory")
    public void onMessage(Message amqpMessage, Channel channel,
                          @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) {
        String queue = MqTopology.queueName(TaskType.PDF_IMPORT);
        // 阶段八-1：消费耗时起点（每个终态分支交回，见各 record* 调用）
        long startedNanos = mqTaskMetrics.startTimer();

        TaskMessage message;
        try {
            message = objectMapper.readValue(
                    new String(amqpMessage.getBody(), StandardCharsets.UTF_8), TaskMessage.class);
        } catch (Exception e) {
            int bodyLength = amqpMessage.getBody() == null ? 0 : amqpMessage.getBody().length;
            log.error("PDF 任务消息反序列化失败，不可重试，进 DLQ queue={} deliveryTag={} bodyLength={}",
                    queue, deliveryTag, bodyLength, e);
            mqTaskMetrics.recordDeadLettered(TaskType.PDF_IMPORT,
                    MqTaskMetrics.DeadLetterReason.POISON_MESSAGE, startedNanos);
            nackNoRequeue(channel, deliveryTag);
            return;
        }

        MessageProperties properties = amqpMessage.getMessageProperties();
        String headerMessageId = properties.getMessageId();
        Long jobId = message.taskId();
        String expectedMessageId = TaskType.PDF_IMPORT.name() + ":" + jobId;
        if (message.taskType() != TaskType.PDF_IMPORT || jobId == null
                || headerMessageId == null || !headerMessageId.equals(expectedMessageId)) {
            log.error("PDF 任务消息头与正文交叉校验失败，不可重试，进 DLQ queue={} deliveryTag={} "
                            + "headerMessageId={} bodyTaskType={} bodyTaskId={}",
                    queue, deliveryTag, headerMessageId, message.taskType(), jobId);
            mqTaskMetrics.recordDeadLettered(TaskType.PDF_IMPORT,
                    MqTaskMetrics.DeadLetterReason.POISON_MESSAGE, startedNanos);
            nackNoRequeue(channel, deliveryTag);
            return;
        }

        boolean claimed;
        try {
            claimed = pdfImportService.claimForMessage(jobId);
        } catch (DataAccessException e) {
            log.error("PDF 任务条件认领数据库异常，requeue 原消息待重投 jobId={} messageId={}",
                    jobId, headerMessageId, e);
            mqTaskMetrics.recordConsumed(TaskType.PDF_IMPORT,
                    MqTaskMetrics.ConsumeOutcome.REQUEUED, startedNanos);
            nackRequeue(channel, deliveryTag);
            return;
        }
        if (!claimed) {
            if (pdfImportService.jobExists(jobId)) {
                // 幂等：0 行说明任务已终态或处理中（重复投递/重试竞态），直接 ack
                log.info("PDF 任务条件认领 0 行，直接 ack（重复投递或已终态） jobId={} messageId={}",
                        jobId, headerMessageId);
            } else {
                log.error("PDF 消息指向的任务行不存在，不可重试，进 DLQ jobId={} messageId={}",
                        jobId, headerMessageId);
                mqTaskMetrics.recordDeadLettered(TaskType.PDF_IMPORT,
                        MqTaskMetrics.DeadLetterReason.TASK_MISSING, startedNanos);
                nackNoRequeue(channel, deliveryTag);
                return;
            }
            mqTaskMetrics.recordConsumed(TaskType.PDF_IMPORT,
                    MqTaskMetrics.ConsumeOutcome.DUPLICATE, startedNanos);
            ack(channel, deliveryTag);
            return;
        }

        PdfImportService.ProcessOutcome outcome;
        try {
            outcome = pdfImportService.process(jobId);
        } catch (DataIntegrityViolationException e) {
            // 终态事务命中 idempotent_consumptions 唯一冲突：已有投递完成过本任务，直接 ack
            log.info("PDF 任务重复投递命中 idempotent_consumptions，直接 ack jobId={} messageId={}",
                    jobId, headerMessageId);
            mqTaskMetrics.recordConsumed(TaskType.PDF_IMPORT,
                    MqTaskMetrics.ConsumeOutcome.DUPLICATE, startedNanos);
            ack(channel, deliveryTag);
            return;
        } catch (DataAccessException e) {
            handleRetryableFailure(amqpMessage, channel, deliveryTag, jobId, headerMessageId, e,
                    startedNanos);
            return;
        } catch (RuntimeException e) {
            log.error("PDF 任务发生未预期异常，进 DLQ jobId={} messageId={}",
                    jobId, headerMessageId, e);
            mqTaskMetrics.recordDeadLettered(TaskType.PDF_IMPORT,
                    MqTaskMetrics.DeadLetterReason.UNEXPECTED_ERROR, startedNanos);
            nackNoRequeue(channel, deliveryTag);
            return;
        }
        log.info("PDF 任务消费成功 jobId={} messageId={} outcome={}",
                jobId, headerMessageId, outcome);
        mqTaskMetrics.recordConsumed(TaskType.PDF_IMPORT, metricOutcomeOf(outcome), startedNanos);
        ack(channel, deliveryTag);
    }

    /**
     * 业务终态 → 指标 outcome 映射（阶段八-1）：DRAFT_READY=消费成功（草稿已生成），
     * SCANNED / FAILED=任务已落 FAILED 业务终态（扫描件、加密、损坏），二者都 ack 不进 DLQ。
     * 只是 tag 取值映射，不参与任何业务判定。
     */
    private static MqTaskMetrics.ConsumeOutcome metricOutcomeOf(PdfImportService.ProcessOutcome outcome) {
        return switch (outcome) {
            case DRAFT_READY -> MqTaskMetrics.ConsumeOutcome.SUCCESS;
            case SCANNED, FAILED -> MqTaskMetrics.ConsumeOutcome.FAILED_BUSINESS;
        };
    }

    /**
     * 数据库等基础设施临时异常：按消息头重试计数选 5s/30s/2m 三档；三档耗尽进 DLQ。
     * 业务解析失败不允许走到这里（process 内部已落 FAILED 终态）。
     */
    private void handleRetryableFailure(Message amqpMessage, Channel channel, long deliveryTag,
                                        Long jobId, String messageId, DataAccessException cause,
                                        long startedNanos) {
        int retryCount = parseRetryCount(amqpMessage.getMessageProperties().getHeaders());
        if (retryCount >= MAX_RETRY_COUNT) {
            Object xDeath = amqpMessage.getMessageProperties().getHeaders().get("x-death");
            log.error("PDF 任务三档重试耗尽（第 {} 次处理仍失败），进 DLQ jobId={} messageId={} "
                            + "reason={} xDeath={}（任务行保持 EXTRACTING，由对账在基础设施恢复后续跑）",
                    retryCount + 1, jobId, messageId, cause.getMessage(), xDeath, cause);
            mqTaskMetrics.recordDeadLettered(TaskType.PDF_IMPORT,
                    MqTaskMetrics.DeadLetterReason.RETRIES_EXHAUSTED, startedNanos);
            nackNoRequeue(channel, deliveryTag);
            return;
        }

        // 先把任务行复位为 PENDING，重试消息到期回来才能被再次条件认领
        try {
            pdfImportService.requeueForMessageRetry(jobId);
        } catch (DataAccessException e) {
            log.error("PDF 任务重试前任务行复位失败，requeue 原消息 jobId={} messageId={} retryCount={}",
                    jobId, messageId, retryCount, e);
            mqTaskMetrics.recordConsumed(TaskType.PDF_IMPORT,
                    MqTaskMetrics.ConsumeOutcome.REQUEUED, startedNanos);
            nackRequeue(channel, deliveryTag);
            return;
        }

        MqTopology.RetryTier tier = MqTopology.RetryTier.values()[retryCount];
        try {
            forwardToRetryQueue(amqpMessage, retryCount + 1, tier);
        } catch (AmqpException e) {
            log.error("PDF 任务转发延时重试队列失败，requeue 原消息 jobId={} messageId={} retryQueue={}",
                    jobId, messageId, MqTopology.retryQueueName(TaskType.PDF_IMPORT, tier), e);
            mqTaskMetrics.recordConsumed(TaskType.PDF_IMPORT,
                    MqTaskMetrics.ConsumeOutcome.REQUEUED, startedNanos);
            nackRequeue(channel, deliveryTag);
            return;
        }
        log.info("PDF 任务可重试失败，转发延时重试 jobId={} messageId={} retryCount={}->{} tier={} reason={}",
                jobId, messageId, retryCount, retryCount + 1, tier.suffix(), cause.getMessage());
        mqTaskMetrics.recordConsumed(TaskType.PDF_IMPORT,
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
        rabbitTemplate.send("", MqTopology.retryQueueName(TaskType.PDF_IMPORT, tier), retryMessage);
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
            log.warn("PDF 任务重试计数头非法，按 0 处理 raw={}", raw);
            return 0;
        }
    }

    private void ack(Channel channel, long deliveryTag) {
        try {
            channel.basicAck(deliveryTag, false);
        } catch (IOException e) {
            log.error("PDF 任务 basicAck 失败 deliveryTag={}", deliveryTag, e);
        }
    }

    private void nackNoRequeue(Channel channel, long deliveryTag) {
        try {
            channel.basicNack(deliveryTag, false, false);
        } catch (IOException e) {
            log.error("PDF 任务 basicNack(no-requeue) 失败 deliveryTag={}", deliveryTag, e);
        }
    }

    private void nackRequeue(Channel channel, long deliveryTag) {
        try {
            channel.basicNack(deliveryTag, false, true);
        } catch (IOException e) {
            log.error("PDF 任务 basicNack(requeue) 失败 deliveryTag={}", deliveryTag, e);
        }
    }
}
