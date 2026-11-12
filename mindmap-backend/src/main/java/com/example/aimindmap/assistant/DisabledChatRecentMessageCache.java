package com.example.aimindmap.assistant;

import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 短期记忆缓存关闭实现（{@code app.cache.redis-enabled=false} 时装配，方案 §3.5 降级开关；
 * 镜像阶段七-1 InMemoryProgressBroadcaster 的条件装配模式）。
 *
 * <p>全部 no-op：get 恒返回 null（调用方直接回源查库）、put/evictSession 空操作。
 * 聊天行为与引入缓存前的现状完全一致，不触碰任何 Redis 连接；
 * 测试上下文默认装配本实现（见 TestRedisDisableEnvironmentPostProcessor）。
 */
@Component
@ConditionalOnProperty(name = "app.cache.redis-enabled", havingValue = "false")
public class DisabledChatRecentMessageCache implements ChatRecentMessageCache {

    @Override
    public List<CachedMessage> get(Long sessionId, int limit) {
        return null;
    }

    @Override
    public void put(Long sessionId, int limit, List<CachedMessage> messages) {
        // no-op：关闭缓存时不回填
    }

    @Override
    public void evictSession(Long sessionId) {
        // no-op：关闭缓存时无键可失效
    }
}
