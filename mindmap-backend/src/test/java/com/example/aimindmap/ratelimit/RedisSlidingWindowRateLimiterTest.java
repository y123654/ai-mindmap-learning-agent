package com.example.aimindmap.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * 阶段七-3：Redis + Lua 滑动窗口限流器单测（纯 Mockito，严禁真实 Redis；
 * 真实往返见 {@code integration/RateLimitIntegrationTest}）。
 *
 * <p>覆盖：脚本返回 1/0 的放行与拒绝、键名格式（前缀 + ratelimit + scope + userId）、
 * 脚本入参（now / 窗口 / 阈值 / 唯一 member）、Lua 脚本原文的四步原子操作、
 * Redis 异常与空返回值降级到单机内存窗口、阈值≤0 完全不碰 Redis。
 */
class RedisSlidingWindowRateLimiterTest {

    private static final long BASE_NOW = 1_760_000_000_000L;
    private static final long USER_ID = 7L;
    private static final String KEY_AI = "aimindmap:ratelimit:ai-chat:7";

    private final AtomicLong now = new AtomicLong(BASE_NOW);
    private StringRedisTemplate redisTemplate;
    private InMemorySlidingWindowRateLimiter fallback;
    private RedisSlidingWindowRateLimiter limiter;
    private List<Object[]> invocations;

    @BeforeEach
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        fallback = new InMemorySlidingWindowRateLimiter(now::get);
        limiter = new RedisSlidingWindowRateLimiter(redisTemplate, "aimindmap:", fallback, now::get);
        invocations = new ArrayList<>();
    }

    @Test
    void scriptReturningOneAllowsRequest() {
        stubScript(1L);

        assertThat(limiter.tryAcquire(RateLimitScope.AI_CHAT, USER_ID, 20)).isTrue();
    }

    @Test
    void scriptReturningZeroRejectsRequest() {
        stubScript(0L);

        assertThat(limiter.tryAcquire(RateLimitScope.AI_CHAT, USER_ID, 20)).isFalse();
    }

    @Test
    void keyFollowsPrefixScopeAndUserId() {
        stubScript(1L);

        limiter.tryAcquire(RateLimitScope.AI_CHAT, USER_ID, 20);
        limiter.tryAcquire(RateLimitScope.PDF_UPLOAD, 9L, 5);
        limiter.tryAcquire(RateLimitScope.SYNC_TRIGGER, 9L, 10);

        assertThat(keys()).containsExactly(
                KEY_AI,
                "aimindmap:ratelimit:pdf-upload:9",
                "aimindmap:ratelimit:sync-trigger:9");
    }

    @Test
    void keyPrefixFollowsInjectedConfiguration() {
        RedisSlidingWindowRateLimiter custom = new RedisSlidingWindowRateLimiter(
                redisTemplate, "test-prefix:", fallback, now::get);
        stubScript(1L);

        custom.tryAcquire(RateLimitScope.SYNC_TRIGGER, USER_ID, 10);

        assertThat(keys()).containsExactly("test-prefix:ratelimit:sync-trigger:7");
    }

    @Test
    void scriptReceivesNowWindowLimitAndUniqueMember() {
        stubScript(1L);

        limiter.tryAcquire(RateLimitScope.AI_CHAT, USER_ID, 20);
        limiter.tryAcquire(RateLimitScope.AI_CHAT, USER_ID, 20);

        assertThat(invocations).hasSize(2);
        Object[] first = invocations.get(0);
        // 参数顺序：now 毫秒、窗口毫秒、阈值、唯一 member
        assertThat(first[2]).isEqualTo(String.valueOf(BASE_NOW));
        assertThat(first[3]).isEqualTo(String.valueOf(RateLimiter.WINDOW_MILLIS));
        assertThat(first[4]).isEqualTo("20");
        assertThat((String) first[5]).isNotBlank();
        // member 必须唯一，否则 ZADD 会覆盖既有成员导致 ZCARD 不增长（限流失效）
        assertThat(first[5]).isNotEqualTo(invocations.get(1)[5]);
    }

    @Test
    void scriptIsAtomicSlidingWindowLuaReturningLong() {
        stubScript(1L);

        limiter.tryAcquire(RateLimitScope.AI_CHAT, USER_ID, 20);

        RedisScript<?> script = (RedisScript<?>) invocations.get(0)[0];
        assertThat(script.getResultType()).isEqualTo(Long.class);
        String lua = script.getScriptAsString();
        // 清窗口外成员 → 计数 → 达限返回 0 → 未达限 ZADD + PEXPIRE → 返回 1
        assertThat(lua)
                .contains("ZREMRANGEBYSCORE")
                .contains("ZCARD")
                .contains("return 0")
                .contains("ZADD")
                .contains("PEXPIRE")
                .contains("return 1");
        assertThat(RedisSlidingWindowRateLimiter.SLIDING_WINDOW_LUA).isEqualTo(lua);
    }

    @Test
    void redisFailureDegradesToInMemoryWindowForThisDecision() {
        when(redisTemplate.execute(ArgumentMatchers.<RedisScript<Long>>any(), anyList(),
                any(), any(), any(), any()))
                .thenThrow(new RedisSystemException("redis down", null));

        // 降级不是「放行一切」也不是「拒绝服务」：退单机窗口，阈值 2 仍然生效
        assertThat(limiter.tryAcquire(RateLimitScope.AI_CHAT, USER_ID, 2)).isTrue();
        assertThat(limiter.tryAcquire(RateLimitScope.AI_CHAT, USER_ID, 2)).isTrue();
        assertThat(limiter.tryAcquire(RateLimitScope.AI_CHAT, USER_ID, 2)).isFalse();
        assertThatCode(() -> limiter.tryAcquire(RateLimitScope.AI_CHAT, USER_ID, 2))
                .doesNotThrowAnyException();

        // Redis 恢复后窗口继续滑动：整窗过去后名额恢复
        now.addAndGet(RateLimiter.WINDOW_MILLIS + 1);
        assertThat(limiter.tryAcquire(RateLimitScope.AI_CHAT, USER_ID, 2)).isTrue();
    }

    @Test
    void nullScriptResultDegradesToInMemoryWindow() {
        stubScript(null);

        assertThat(limiter.tryAcquire(RateLimitScope.PDF_UPLOAD, USER_ID, 1)).isTrue();
        assertThat(limiter.tryAcquire(RateLimitScope.PDF_UPLOAD, USER_ID, 1)).isFalse();
    }

    @Test
    void nonPositiveLimitNeverTouchesRedis() {
        assertThat(limiter.tryAcquire(RateLimitScope.AI_CHAT, USER_ID, 0)).isTrue();
        assertThat(limiter.tryAcquire(RateLimitScope.AI_CHAT, USER_ID, -5)).isTrue();

        verifyNoInteractions(redisTemplate);
        assertThat(fallback.trackedKeyCount()).isZero();
    }

    @Test
    void redisFailureWithUnlimitedThresholdStillAllows() {
        when(redisTemplate.execute(ArgumentMatchers.<RedisScript<Long>>any(), anyList(),
                any(), any(), any(), any()))
                .thenThrow(new RedisSystemException("redis down", null));

        assertThat(limiter.tryAcquire(RateLimitScope.SYNC_TRIGGER, USER_ID, 0)).isTrue();
    }

    private void stubScript(Long result) {
        when(redisTemplate.execute(ArgumentMatchers.<RedisScript<Long>>any(), anyList(),
                any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    invocations.add(invocation.getArguments());
                    return result;
                });
    }

    private List<String> keys() {
        List<String> keys = new ArrayList<>();
        for (Object[] invocation : invocations) {
            @SuppressWarnings("unchecked")
            List<String> callKeys = (List<String>) invocation[1];
            keys.addAll(callKeys);
        }
        return keys;
    }
}