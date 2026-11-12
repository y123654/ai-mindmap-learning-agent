package com.example.aimindmap.progress;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;

/**
 * 应用就绪后在后台启动 Redis 订阅容器（阶段七-1 启动韧性，方案 §3.5）。
 *
 * <p>{@link RedisMessageListenerContainer#start()} 首次会同步建立连接，Redis 不可用时
 * 直接让整个应用上下文启动失败；因此容器本身关闭自动启动（见
 * {@link ProgressRedisConfiguration.ManualStartRedisMessageListenerContainer}），
 * 由本组件在 {@link ApplicationReadyEvent} 后用守护线程启动：连接失败只 warn，
 * 按 {@value #RETRY_INTERVAL_MILLIS}ms 固定退避无限重试。
 *
 * <p>效果：Redis 宕机时上下文照常启动、本机 SSE 经发布异常降级直投仍然可用；Redis 恢复后
 * 订阅自动建立。容器一旦启动成功，运行期断连由 Spring Data Redis 监听容器自身重连。
 */
@Component
@ConditionalOnProperty(name = "app.cache.redis-enabled", havingValue = "true", matchIfMissing = true)
public class ProgressRedisSubscriberStarter
        implements ApplicationListener<ApplicationReadyEvent> {

    private static final Logger log = LoggerFactory.getLogger(ProgressRedisSubscriberStarter.class);
    private static final long RETRY_INTERVAL_MILLIS = 3000;

    private final RedisMessageListenerContainer container;
    private volatile boolean active = true;
    private Thread worker;

    public ProgressRedisSubscriberStarter(RedisMessageListenerContainer container) {
        this.container = container;
    }

    @Override
    public synchronized void onApplicationEvent(ApplicationReadyEvent event) {
        if (worker != null) {
            return;
        }
        worker = new Thread(this::startWithRetry, "progress-redis-subscriber");
        worker.setDaemon(true);
        worker.start();
    }

    private void startWithRetry() {
        while (active) {
            try {
                container.start();
                log.info("SSE 跨实例广播 Redis 订阅已建立 pattern={}*",
                        RedisPubSubBroadcaster.CHANNEL_PREFIX);
                return;
            } catch (Exception e) {
                quietlyResetAfterFailedStart();
                log.warn("SSE 跨实例广播 Redis 订阅暂不可用，{}ms 后后台重试（本机 SSE 走降级直投）：{}",
                        RETRY_INTERVAL_MILLIS, e.getMessage());
                try {
                    Thread.sleep(RETRY_INTERVAL_MILLIS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /**
     * start() 失败后容器内部 running 标记可能仍为 true，先 stop 复位，
     * 否则下一轮 start() 会被当作已启动而空转。
     */
    private void quietlyResetAfterFailedStart() {
        try {
            container.stop();
        } catch (Exception ignored) {
            // 启动中途失败后的复位，忽略
        }
    }

    @PreDestroy
    void shutdown() {
        active = false;
        if (worker != null) {
            worker.interrupt();
        }
        try {
            container.stop();
        } catch (Exception e) {
            log.debug("关闭 Redis 订阅容器时忽略异常：{}", e.toString());
        }
    }
}
