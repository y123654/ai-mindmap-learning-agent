package com.example.aimindmap.mqsupport.outbox;

import com.example.aimindmap.mqsupport.PublishOutcome;
import com.example.aimindmap.mqsupport.TaskMessage;
import com.example.aimindmap.mqsupport.TaskMessagePublisher;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Outbox 投递事务服务（方案《阶段四·B》）。
 *
 * <p>outbox 没有 PROCESSING 状态，因此「FOR UPDATE SKIP LOCKED 认领 + 投递 + 置状态」
 * 必须在同一个事务内完成，避免行锁提前释放被其他实例重复投递；由独立 Bean
 * {@link OutboxRelay} 调用，规避同类自调用导致 @Transactional 失效。
 *
 * <p>app.mq.enabled=false 时本 Bean 不装配：无 MQ 时事件保持 PENDING，由轮询兜底。
 */
@Service
@ConditionalOnProperty(name = "app.mq.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxRelayService {

    /** 每次认领上限：方案 B 节固定 100 条。 */
    static final int BATCH_SIZE = 100;

    /** SENT 行保留 7 天后清理（方案 B 节）；附录 A 无对应配置，代码常量。 */
    static final Duration SENT_RETENTION = Duration.ofDays(7);

    /** last_error 长度防护：DB 列为 TEXT，仍截断避免异常消息无限长。 */
    private static final int LAST_ERROR_MAX_CHARS = 2000;

    private static final Logger log = LoggerFactory.getLogger(OutboxRelayService.class);

    private final OutboxEventRepository repository;
    private final TaskMessagePublisher publisher;
    private final ObjectMapper objectMapper;
    private final MqProperties mqProperties;

    public OutboxRelayService(OutboxEventRepository repository,
                              TaskMessagePublisher publisher,
                              ObjectMapper objectMapper,
                              MqProperties mqProperties) {
        this.repository = repository;
        this.publisher = publisher;
        this.objectMapper = objectMapper;
        this.mqProperties = mqProperties;
    }

    /**
     * 认领并投递一批 PENDING；返回本次认领的行数。
     * 单行任何失败只影响本行（计数、可能置 DEAD），绝不中断整批。
     */
    @Transactional
    public int relayPendingBatch() {
        List<OutboxEventEntity> batch = repository.claimPending(BATCH_SIZE);
        if (batch.isEmpty()) {
            return 0;
        }
        for (OutboxEventEntity event : batch) {
            relayOne(event);
        }
        return batch.size();
    }

    private void relayOne(OutboxEventEntity event) {
        TaskMessage message;
        try {
            message = objectMapper.readValue(event.getPayload(), TaskMessage.class);
        } catch (Exception e) {
            markAttemptFailure(event, "payload 反序列化失败: " + e.getMessage(), e);
            return;
        }

        PublishOutcome outcome;
        try {
            outcome = publisher.publish(message);
        } catch (RuntimeException e) {
            log.error("outbox 投递发生未预期异常 taskType={} taskId={} outboxId={}",
                    event.getTaskType(), event.getTaskId(), event.getId(), e);
            markAttemptFailure(event, "publisher 异常: " + e.getMessage(), e);
            return;
        }

        switch (outcome) {
            case PublishOutcome.Acknowledged ignored -> {
                event.setStatus(OutboxEventStatus.SENT);
                event.setSentAt(Instant.now());
                repository.save(event);
                log.debug("outbox 投递成功 taskType={} taskId={} outboxId={}",
                        event.getTaskType(), event.getTaskId(), event.getId());
            }
            case PublishOutcome.Rejected rejected ->
                    markAttemptFailure(event, rejected.reason(), null);
            case PublishOutcome.Skipped skipped ->
                // 防御分支：开关开启时 publisher 不会返回 Skipped；行原样保留、不计数
                    log.debug("outbox 投递被跳过，行保持 PENDING taskType={} taskId={} outboxId={} reason={}",
                            event.getTaskType(), event.getTaskId(), event.getId(), skipped.reason());
        }
    }

    private void markAttemptFailure(OutboxEventEntity event, String reason, Exception cause) {
        int attempts = event.getAttempts() + 1;
        event.setAttempts(attempts);
        event.setLastError(truncate(reason));
        int maxAttempts = mqProperties.getOutboxMaxAttempts();
        if (attempts >= maxAttempts) {
            event.setStatus(OutboxEventStatus.DEAD);
            log.error("outbox 投递失败达上限，置 DEAD taskType={} taskId={} outboxId={} attempts={}/{} reason={}",
                    event.getTaskType(), event.getTaskId(), event.getId(), attempts, maxAttempts,
                    event.getLastError(), cause);
        } else {
            log.warn("outbox 投递失败，保持 PENDING 待下轮重试 taskType={} taskId={} outboxId={} attempts={}/{} reason={}",
                    event.getTaskType(), event.getTaskId(), event.getId(), attempts, maxAttempts,
                    event.getLastError());
        }
        repository.save(event);
    }

    /**
     * 清理 SENT 超过 7 天的行；返回删除条数。
     */
    @Transactional
    public int cleanupSent() {
        Instant cutoff = Instant.now().minus(SENT_RETENTION);
        int deleted = repository.deleteSentBefore(cutoff);
        if (deleted > 0) {
            log.info("outbox 清理过期 SENT 事件 count={} cutoff={}", deleted, cutoff);
        }
        return deleted;
    }

    private static String truncate(String text) {
        if (text == null) {
            return null;
        }
        return text.length() <= LAST_ERROR_MAX_CHARS
                ? text
                : text.substring(0, LAST_ERROR_MAX_CHARS) + "…";
    }
}
