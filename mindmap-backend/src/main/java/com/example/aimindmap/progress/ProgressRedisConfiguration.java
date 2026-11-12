package com.example.aimindmap.progress;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * 阶段七-1：SSE 跨实例广播的 Redis 订阅装配（仅 redis-enabled=true/缺省时生效）。
 *
 * <p>每个应用实例一个 {@link RedisMessageListenerContainer}，对 {@code aimindmap:job:*}
 * 做模式订阅（PSUBSCRIBE），回流统一交给 {@link RedisPubSubBroadcaster}。
 *
 * <p><b>启动韧性（方案 §3.5：Redis 宕机不许级联雪崩）：</b>容器不随上下文自动启动
 * （{@link ManualStartRedisMessageListenerContainer#isAutoStartup()} 返回 false；
 * 容器 start() 首次会同步建连，自动启动会在 Redis 不可用时直接导致上下文刷新失败），
 * 改由 {@link ProgressRedisSubscriberStarter} 在应用就绪后于后台守护线程启动并无限重试。
 * Redis 在启动期不可用时上下文正常启动、本机内存 SSE 仍可用（发布走异常降级直投），
 * Redis 恢复后订阅自动建立。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "app.cache.redis-enabled", havingValue = "true", matchIfMissing = true)
public class ProgressRedisConfiguration {

    @Bean(destroyMethod = "stop")
    public RedisMessageListenerContainer progressRedisListenerContainer(
            RedisConnectionFactory connectionFactory,
            RedisPubSubBroadcaster broadcaster) {
        RedisMessageListenerContainer container = new ManualStartRedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(broadcaster,
                new PatternTopic(RedisPubSubBroadcaster.CHANNEL_PREFIX + "*"));
        return container;
    }

    /** 关闭 SmartLifecycle 自动启动：同步建连失败不阻断上下文刷新，启动交给后台 Starter。 */
    static class ManualStartRedisMessageListenerContainer extends RedisMessageListenerContainer {
        @Override
        public boolean isAutoStartup() {
            return false;
        }
    }
}
