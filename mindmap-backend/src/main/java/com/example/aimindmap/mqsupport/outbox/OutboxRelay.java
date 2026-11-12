package com.example.aimindmap.mqsupport.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Outbox 定时调度入口（方案《阶段四·B》）。
 *
 * <p>仅做轻量调度，事务边界在独立 Bean {@link OutboxRelayService}（避免自调用导致
 * @Transactional 失效）。app.mq.enabled=false 时整个 Relay 不装配，事件留在 PENDING，
 * 任务由现有轮询机制兜底。
 */
@Component
@EnableScheduling
@EnableConfigurationProperties(MqProperties.class)
@ConditionalOnProperty(name = "app.mq.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxRelay {

    /** SENT 清理间隔 1 小时；附录 A 无对应配置项，用代码常量，不进 application.yml。 */
    static final long CLEANUP_FIXED_DELAY_MS = 3_600_000L;

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxRelayService relayService;

    public OutboxRelay(OutboxRelayService relayService) {
        this.relayService = relayService;
    }

    /** 每 1 秒（默认）认领一批 PENDING 投递；间隔可由 APP_MQ_OUTBOX_FIXED_DELAY_MS 覆盖。 */
    @Scheduled(fixedDelayString = "${app.mq.outbox-fixed-delay-ms:1000}")
    public void relayPending() {
        int count = relayService.relayPendingBatch();
        if (count == 0) {
            return;
        }
        log.info("outbox relay 认领并投递任务 count={}", count);
    }

    /** 每小时清理一次 7 天前的 SENT 行。 */
    @Scheduled(fixedDelay = CLEANUP_FIXED_DELAY_MS)
    public void cleanupSent() {
        relayService.cleanupSent();
    }
}
