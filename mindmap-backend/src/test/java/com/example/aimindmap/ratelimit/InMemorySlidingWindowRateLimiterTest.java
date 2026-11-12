package com.example.aimindmap.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * 阶段七-3：单机内存滑动窗口限流器单测（假时钟，无真实 Redis、无 sleep）。
 *
 * <p>覆盖：阈值内放行 / 第 N+1 次拒绝、时间推进后窗口滑动恢复、阈值≤0 不限流、
 * 作用域与用户互相隔离、过期条目机会式清理、并发不超发。
 */
class InMemorySlidingWindowRateLimiterTest {

    private static final long BASE_NOW = 1_760_000_000_000L;
    private static final long USER_ID = 8L;

    private final AtomicLong now = new AtomicLong(BASE_NOW);
    private final InMemorySlidingWindowRateLimiter limiter =
            new InMemorySlidingWindowRateLimiter(now::get);

    @Test
    void allowsUpToLimitThenRejectsNextWithinSameWindow() {
        assertThat(limiter.tryAcquire(RateLimitScope.AI_CHAT, USER_ID, 3)).isTrue();
        now.addAndGet(100);
        assertThat(limiter.tryAcquire(RateLimitScope.AI_CHAT, USER_ID, 3)).isTrue();
        now.addAndGet(100);
        assertThat(limiter.tryAcquire(RateLimitScope.AI_CHAT, USER_ID, 3)).isTrue();
        // 第 N+1 次：同一窗口内已达阈值 3，拒绝且不写入
        now.addAndGet(100);
        assertThat(limiter.tryAcquire(RateLimitScope.AI_CHAT, USER_ID, 3)).isFalse();
        assertThat(limiter.tryAcquire(RateLimitScope.AI_CHAT, USER_ID, 3)).isFalse();
    }

    @Test
    void windowSlidesSoOldestEntryReleasesCapacity() {
        // t=0 / t=30s / t=59s 各一次，阈值 3
        assertThat(limiter.tryAcquire(RateLimitScope.PDF_UPLOAD, USER_ID, 3)).isTrue();
        now.set(BASE_NOW + 30_000L);
        assertThat(limiter.tryAcquire(RateLimitScope.PDF_UPLOAD, USER_ID, 3)).isTrue();
        now.set(BASE_NOW + 59_000L);
        assertThat(limiter.tryAcquire(RateLimitScope.PDF_UPLOAD, USER_ID, 3)).isTrue();
        // t=60s：窗口下界 = 0，t=0 那条被清出，只剩 2 条 → 放行（滑动而非固定窗口重置）
        now.set(BASE_NOW + 60_000L);
        assertThat(limiter.tryAcquire(RateLimitScope.PDF_UPLOAD, USER_ID, 3)).isTrue();
        // 但 t=30s 与 t=59s 仍在窗口内，加上刚放行的一条共 3 条 → 再请求即拒绝
        assertThat(limiter.tryAcquire(RateLimitScope.PDF_UPLOAD, USER_ID, 3)).isFalse();
    }

    @Test
    void capacityFullyRecoversAfterWholeWindowElapsed() {
        assertThat(limiter.tryAcquire(RateLimitScope.SYNC_TRIGGER, USER_ID, 2)).isTrue();
        assertThat(limiter.tryAcquire(RateLimitScope.SYNC_TRIGGER, USER_ID, 2)).isTrue();
        assertThat(limiter.tryAcquire(RateLimitScope.SYNC_TRIGGER, USER_ID, 2)).isFalse();

        now.addAndGet(RateLimiter.WINDOW_MILLIS + 1);

        assertThat(limiter.tryAcquire(RateLimitScope.SYNC_TRIGGER, USER_ID, 2)).isTrue();
        assertThat(limiter.tryAcquire(RateLimitScope.SYNC_TRIGGER, USER_ID, 2)).isTrue();
        assertThat(limiter.tryAcquire(RateLimitScope.SYNC_TRIGGER, USER_ID, 2)).isFalse();
    }

    @Test
    void nonPositiveLimitMeansUnlimitedAndTracksNothing() {
        for (int i = 0; i < 100; i++) {
            assertThat(limiter.tryAcquire(RateLimitScope.AI_CHAT, USER_ID, 0)).isTrue();
            assertThat(limiter.tryAcquire(RateLimitScope.PDF_UPLOAD, USER_ID, -1)).isTrue();
        }
        // 阈值≤0 = 一键关闭：不建队列、不占内存
        assertThat(limiter.trackedKeyCount()).isZero();
    }

    @Test
    void scopesAndUsersAreCountedSeparately() {
        assertThat(limiter.tryAcquire(RateLimitScope.AI_CHAT, USER_ID, 1)).isTrue();
        assertThat(limiter.tryAcquire(RateLimitScope.AI_CHAT, USER_ID, 1)).isFalse();

        // 同一用户换作用域：另有独立名额
        assertThat(limiter.tryAcquire(RateLimitScope.PDF_UPLOAD, USER_ID, 1)).isTrue();
        // 同一作用域换用户：另有独立名额
        assertThat(limiter.tryAcquire(RateLimitScope.AI_CHAT, USER_ID + 1, 1)).isTrue();
        assertThat(limiter.trackedKeyCount()).isEqualTo(3);
    }

    @Test
    void expiredEntriesAreSweptOpportunisticallyToBoundMemory() {
        for (long userId = 1; userId <= 3; userId++) {
            assertThat(limiter.tryAcquire(RateLimitScope.AI_CHAT, userId, 1)).isTrue();
        }
        assertThat(limiter.trackedKeyCount()).isEqualTo(3);

        // 整窗过去后这三条都已过期，但还没有触发清理
        now.addAndGet(RateLimiter.WINDOW_MILLIS + 1);
        assertThat(limiter.trackedKeyCount()).isEqualTo(3);

        // 再补 (SWEEP_INTERVAL - 3) 次判定，第 SWEEP_INTERVAL 次触发机会式清理
        for (int i = 3; i < InMemorySlidingWindowRateLimiter.SWEEP_INTERVAL; i++) {
            limiter.tryAcquire(RateLimitScope.AI_CHAT, 999L, 1);
        }

        // 只剩本轮仍在窗口内的那一个键，过期条目全部被丢弃
        assertThat(limiter.trackedKeyCount()).isEqualTo(1);
    }

    @Test
    void concurrentRequestsNeverExceedLimit() throws Exception {
        int threads = 8;
        int attemptsPerThread = 50;
        int limit = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startGate = new CountDownLatch(1);
        AtomicInteger allowed = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int t = 0; t < threads; t++) {
                futures.add(pool.submit(() -> {
                    awaitQuietly(startGate);
                    for (int i = 0; i < attemptsPerThread; i++) {
                        if (limiter.tryAcquire(RateLimitScope.AI_CHAT, USER_ID, limit)) {
                            allowed.incrementAndGet();
                        }
                    }
                }));
            }
            startGate.countDown();
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        // 同一窗口、同一用户：并发下严格不超发
        assertThat(allowed.get()).isEqualTo(limit);
    }

    @Test
    void limiterUsesInjectedClockNotSystemTime() {
        AtomicBoolean used = new AtomicBoolean(false);
        InMemorySlidingWindowRateLimiter custom = new InMemorySlidingWindowRateLimiter(() -> {
            used.set(true);
            return BASE_NOW;
        });

        assertThat(custom.tryAcquire(RateLimitScope.AI_CHAT, USER_ID, 1)).isTrue();
        assertThat(custom.tryAcquire(RateLimitScope.AI_CHAT, USER_ID, 1)).isFalse();
        assertThat(used.get()).isTrue();
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}