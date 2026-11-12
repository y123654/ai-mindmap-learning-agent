package com.example.aimindmap.mindmap;

import com.example.aimindmap.metrics.MqTaskMetrics;
import com.example.aimindmap.mqsupport.MqTopology;
import com.example.aimindmap.mqsupport.RetriableTaskException;
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
 * 思维导图同步 MQ 消费者（方案《阶段六》6-1），监听 aimindmap.queue.MINDMAP_SYNC。
 *
 * <p>复用 {@link SyncJobService#processJob(Long)}（同步业务编排零复制）：READY/EARLY_EXIT/
 * FAILED_BUSINESS 都是正常终态（含任务已取消/业务失败已落 FAILED），落终态后 ack，不进 DLQ；
 * FAILED_CONFIGURATION（AI 未配置/4xx 配置类）任务已是 FAILED 终态，消息进 DLQ 仅作运维信号。
 * 消息头/正文交叉校验、反序列化失败、taskType/messageId 不符、任务行不存在等永久毒消息
 * 首次即 nack 进 DLQ；{@link RetriableTaskException}（AI 限流/超时/5xx、数据库瞬时异常）
 * 走三档延时重试（5s/30s/2m），耗尽进 DLQ，任务留中间态由 5 分钟对账复位续跑。
 *
 * <p>容器工厂复用阶段四已建好的 aiRabbitListenerContainerFactory（同步也是 AI 重调用，
 * 不新增工厂 Bean），独立 AI 连接、手动 ACK、并发 1~2。
 * 仅在 app.mq.enabled=true（缺省即 true）时装配；关闭时由 {@link SyncJobWorker} @Async 兜底。
 *
 * <p>SSE 进度由 processJob 经 ProgressBroadcaster 发布，推送序列与阶段 3 一致。
 * 阶段七-1 已实现：Redis Pub/Sub 跨实例广播（频道 aimindmap:job:{jobId}，各实例只下发本机
 * 持有的 emitter），消费者无需关心前端连的是哪个实例；app.cache.redis-enabled=false 时
 * 自动降级为本机内存直推。
 *
 * <p>阶段八-1：每个终态分支（ack / nack）在既有日志之后追加一次 {@link MqTaskMetrics} 记录，
 * 导出 {@code aimindmap.task.consumed}（tag task_type=MINDMAP_SYNC + outcome）、
 * {@code aimindmap.task.consume}（同 tag 的耗时 Timer）与转 DLQ 处的
 * {@code aimindmap.task.dead.letter}（tag reason）。<b>只加记录、不改任何分支与 ack 语义</b>，
 * tag 只用枚举级低基数值，不带 jobId / messageId。
 */
@Component
@ConditionalOnProperty(name = "app.mq.enabled", havingValue = "true", matchIfMissing = true)
public class SyncJobMessageConsumer {

    /** 三档重试上限：重试计数 0/1/2 转发 5s/30s/2m；计数 3 时第 4 次处理仍失败直接进 DLQ。 */
    static final int MAX_RETRY_COUNT = MqTopology.RetryTier.values().length;

    private static final Logger log = LoggerFactory.getLogger(SyncJobMessageConsumer.class);

    private final ObjectMapper objectMapper;
    private final RabbitTemplate rabbitTemplate;
    private final SyncJobService syncJobService;
    private final MqTaskMetrics mqTaskMetrics;

    public SyncJobMessageConsumer(ObjectMapper objectMapper,
                                  RabbitTemplate rabbitTemplate,
                                  SyncJobService syncJobService,
                                  MqTaskMetrics mqTaskMetrics) {
        this.objectMapper = objectMapper;
        this.rabbitTemplate = rabbitTemplate;
        this.syncJobService = syncJobService;
        this.mqTaskMetrics = mqTaskMetrics;
    }

    /** 同步工作队列入口；队列名由 MqTopology 常量经 SpEL 派生，禁止硬编码。 */
    @RabbitListener(
            queues = "#{T(com.example.aimindmap.mqsupport.MqTopology)"
                    + ".queueName(T(com.example.aimindmap.mqsupport.TaskType).MINDMAP_SYNC)}",
            containerFactory = "aiRabbitListenerContainerFactory")
    public void onMessage(Message amqpMessage, Channel channel,
                          @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) {
        String queue = MqTopology.queueName(TaskType.MINDMAP_SYNC);
        // 阶段八-1：消费耗时起点（每个终态分支交回，见各 record* 调用）
        long startedNanos = mqTaskMetrics.startTimer();

        TaskMessage message;
        try {
            message = objectMapper.readValue(
                    new String(amqpMessage.getBody(), StandardCharsets.UTF_8), TaskMessage.class);
        } catch (Exception e) {
            int bodyLength = amqpMessage.getBody() == null ? 0 : amqpMessage.getBody().length;
            log.error("同步任务消息反序列化失败，不可重试，进 DLQ queue={} deliveryTag={} bodyLength={}",
                    queue, deliveryTag, bodyLength, e);
            mqTaskMetrics.recordDeadLettered(TaskType.MINDMAP_SYNC,
                    MqTaskMetrics.DeadLetterReason.POISON_MESSAGE, startedNanos);
            nackNoRequeue(channel, deliveryTag);
            return;
        }

        MessageProperties properties = amqpMessage.getMessageProperties();
        String headerMessageId = properties.getMessageId();
        Long jobId = message.taskId();
        String expectedMessageId = TaskType.MINDMAP_SYNC.name() + ":" + jobId;
        if (message.taskType() != TaskType.MINDMAP_SYNC || jobId == null
                || headerMessageId == null || !headerMessageId.equals(expectedMessageId)) {
            log.error("同步任务消息头与正文交叉校验失败，不可重试，进 DLQ queue={} deliveryTag={} "
                            + "headerMessageId={} bodyTaskType={} bodyTaskId={}",
                    queue, deliveryTag, headerMessageId, message.taskType(), jobId);
            mqTaskMetrics.recordDeadLettered(TaskType.MINDMAP_SYNC,
                    MqTaskMetrics.DeadLetterReason.POISON_MESSAGE, startedNanos);
            nackNoRequeue(channel, deliveryTag);
            return;
        }

        boolean claimed;
        try {
            claimed = syncJobService.claimForMessage(jobId);
        } catch (DataAccessException e) {
            log.error("同步任务条件认领数据库异常，requeue 原消息待重投 jobId={} messageId={}",
                    jobId, headerMessageId, e);
            mqTaskMetrics.recordConsumed(TaskType.MINDMAP_SYNC,
                    MqTaskMetrics.ConsumeOutcome.REQUEUED, startedNanos);
            nackRequeue(channel, deliveryTag);
            return;
        }
        if (!claimed) {
            if (syncJobService.jobExists(jobId)) {
                // 幂等：0 行说明任务已终态或处理中（重复投递/重试竞态/用户已取消），直接 ack。
                // CANCELLED 终态无法与用户取消事务同写幂等行，即由本防线覆盖。
                log.info("同步任务条件认领 0 行，直接 ack（重复投递、已终态或已取消） jobId={} messageId={}",
                        jobId, headerMessageId);
            } else {
                log.error("同步消息指向的任务行不存在，不可重试，进 DLQ jobId={} messageId={}",
                        jobId, headerMessageId);
                mqTaskMetrics.recordDeadLettered(TaskType.MINDMAP_SYNC,
                        MqTaskMetrics.DeadLetterReason.TASK_MISSING, startedNanos);
                nackNoRequeue(channel, deliveryTag);
                return;
            }
            mqTaskMetrics.recordConsumed(TaskType.MINDMAP_SYNC,
                    MqTaskMetrics.ConsumeOutcome.DUPLICATE, startedNanos);
            ack(channel, deliveryTag);
            return;
        }

        SyncJobService.ProcessOutcome outcome;
        try {
            outcome = syncJobService.processJob(jobId);
        } catch (DataIntegrityViolationException e) {
            // 终态事务命中 idempotent_consumptions 唯一冲突：已有投递完成过本任务，直接 ack
            log.info("同步任务重复投递命中 idempotent_consumptions，直接 ack jobId={} messageId={}",
                    jobId, headerMessageId);
            mqTaskMetrics.recordConsumed(TaskType.MINDMAP_SYNC,
                    MqTaskMetrics.ConsumeOutcome.DUPLICATE, startedNanos);
            ack(channel, deliveryTag);
            return;
        } catch (RetriableTaskException e) {
            handleRetryableFailure(amqpMessage, channel, deliveryTag, jobId, headerMessageId,
                    e.getReason(), e, startedNanos);
            return;
        } catch (DataAccessException e) {
            handleRetryableFailure(amqpMessage, channel, deliveryTag, jobId, headerMessageId,
                    "DataAccessException: " + e.getMessage(), e, startedNanos);
            return;
        } catch (RuntimeException e) {
            log.error("同步任务发生未预期异常，进 DLQ jobId={} messageId={}",
                    jobId, headerMessageId, e);
            mqTaskMetrics.recordDeadLettered(TaskType.MINDMAP_SYNC,
                    MqTaskMetrics.DeadLetterReason.UNEXPECTED_ERROR, startedNanos);
            nackNoRequeue(channel, deliveryTag);
            return;
        }

        switch (outcome) {
            case READY, FAILED_BUSINESS, EARLY_EXIT -> {
                log.info("同步任务消费成功（业务终态） jobId={} messageId={} outcome={}",
                        jobId, headerMessageId, outcome);
                mqTaskMetrics.recordConsumed(TaskType.MINDMAP_SYNC,
                        metricOutcomeOf(outcome), startedNanos);
                ack(channel, deliveryTag);
            }
            case FAILED_CONFIGURATION -> {
                // 任务已是 FAILED 终态（AI 未配置/4xx 配置类），DLQ 仅作运维信号，不触发重试
                log.warn("同步任务配置类失败，任务已落 FAILED，消息进 DLQ 作运维信号 jobId={} messageId={}",
                        jobId, headerMessageId);
                mqTaskMetrics.recordDeadLettered(TaskType.MINDMAP_SYNC,
                        MqTaskMetrics.DeadLetterReason.CONFIGURATION_FAILURE, startedNanos);
                nackNoRequeue(channel, deliveryTag);
            }
        }
    }

    /**
     * 业务终态 → 指标 outcome 映射（阶段八-1）：READY=消费成功、FAILED_BUSINESS=任务已落 FAILED
     * 业务终态、EARLY_EXIT=业务跳过（任务已取消/无变化）、FAILED_CONFIGURATION=转 DLQ。
     * 只是 tag 取值映射，不参与任何业务判定。
     */
    private static MqTaskMetrics.ConsumeOutcome metricOutcomeOf(SyncJobService.ProcessOutcome outcome) {
        return switch (outcome) {
            case READY -> MqTaskMetrics.ConsumeOutcome.SUCCESS;
            case FAILED_BUSINESS -> MqTaskMetrics.ConsumeOutcome.FAILED_BUSINESS;
            case EARLY_EXIT -> MqTaskMetrics.ConsumeOutcome.SKIPPED;
            case FAILED_CONFIGURATION -> MqTaskMetrics.ConsumeOutcome.DEAD_LETTER;
        };
    }

    /**
     * AI 限流/超时/5xx、数据库等基础设施临时异常：按消息头重试计数选 5s/30s/2m 三档；
     * 三档耗尽进 DLQ（任务留中间态，由对账在基础设施恢复后续跑）。
     * 转发前先把任务行复位为 PENDING，重试消息到期才能被再次条件认领。
     */
    private void handleRetryableFailure(Message amqpMessage, Channel channel, long deliveryTag,
                                        Long jobId, String messageId, String reason, Exception cause,
                                        long startedNanos) {
        int retryCount = parseRetryCount(amqpMessage.getMessageProperties().getHeaders());
        if (retryCount >= MAX_RETRY_COUNT) {
            Object xDeath = amqpMessage.getMessageProperties().getHeaders().get("x-death");
            log.error("同步任务三档重试耗尽（第 {} 次处理仍失败），进 DLQ jobId={} messageId={} "
                            + "reason={} xDeath={}（任务行保持中间态，由对账在基础设施恢复后续跑）",
                    retryCount + 1, jobId, messageId, reason, xDeath, cause);
            mqTaskMetrics.recordDeadLettered(TaskType.MINDMAP_SYNC,
                    MqTaskMetrics.DeadLetterReason.RETRIES_EXHAUSTED, startedNanos);
            nackNoRequeue(channel, deliveryTag);
            return;
        }

        try {
            syncJobService.requeueForMessageRetry(jobId);
        } catch (DataAccessException e) {
            log.error("同步任务重试前任务行复位失败，requeue 原消息 jobId={} messageId={} retryCount={}",
                    jobId, messageId, retryCount, e);
            mqTaskMetrics.recordConsumed(TaskType.MINDMAP_SYNC,
                    MqTaskMetrics.ConsumeOutcome.REQUEUED, startedNanos);
            nackRequeue(channel, deliveryTag);
            return;
        }

        MqTopology.RetryTier tier = MqTopology.RetryTier.values()[retryCount];
        try {
            forwardToRetryQueue(amqpMessage, retryCount + 1, tier);
        } catch (AmqpException e) {
            log.error("同步任务转发延时重试队列失败，requeue 原消息 jobId={} messageId={} retryQueue={}",
                    jobId, messageId, MqTopology.retryQueueName(TaskType.MINDMAP_SYNC, tier), e);
            mqTaskMetrics.recordConsumed(TaskType.MINDMAP_SYNC,
                    MqTaskMetrics.ConsumeOutcome.REQUEUED, startedNanos);
            nackRequeue(channel, deliveryTag);
            return;
        }
        log.info("同步任务可重试失败，转发延时重试 jobId={} messageId={} retryCount={}->{} tier={} reason={}",
                jobId, messageId, retryCount, retryCount + 1, tier.suffix(), reason);
        mqTaskMetrics.recordConsumed(TaskType.MINDMAP_SYNC,
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
        rabbitTemplate.send("", MqTopology.retryQueueName(TaskType.MINDMAP_SYNC, tier), retryMessage);
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
            log.warn("同步任务重试计数头非法，按 0 处理 raw={}", raw);
            return 0;
        }
    }

    private void ack(Channel channel, long deliveryTag) {
        try {
            channel.basicAck(deliveryTag, false);
        } catch (IOException e) {
            log.error("同步任务 basicAck 失败 deliveryTag={}", deliveryTag, e);
        }
    }

    private void nackNoRequeue(Channel channel, long deliveryTag) {
        try {
            channel.basicNack(deliveryTag, false, false);
        } catch (IOException e) {
            log.error("同步任务 basicNack(no-requeue) 失败 deliveryTag={}", deliveryTag, e);
        }
    }

    private void nackRequeue(Channel channel, long deliveryTag) {
        try {
            channel.basicNack(deliveryTag, false, true);
        } catch (IOException e) {
            log.error("同步任务 basicNack(requeue) 失败 deliveryTag={}", deliveryTag, e);
        }
    }
}
