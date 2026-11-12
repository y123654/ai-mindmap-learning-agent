package com.example.aimindmap.ratelimit;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 阶段七-3：单机内存滑动窗口限流器。
 *
 * <p>两种用途：
 * <ol>
 *   <li>{@code app.cache.redis-enabled=false} 时的唯一实现（方案 §3.5 第 125 行「限流走单机内存窗口」）；</li>
 *   <li>{@link RedisSlidingWindowRateLimiter} 的降级兜底：Redis 异常时本次判定改由它执行
 *       （方案 321 行「Redis 宕机优雅降级」= 退单机窗口，既不是无脑放行、也不是拒绝服务）。</li>
 * </ol>
 *
 * <p><b>判定：</b>每个 {@code {scope}:{userId}} 一个时间戳队列（毫秒，升序）。
 * 一次判定 = 队首出队直到时间戳 &gt; now-窗口 → 剩余条数即窗口内请求数 →
 * 达阈值拒绝、未达阈值把 now 入队并放行。队列的加锁与「清理 + 计数 + 入队」在同一临界区内完成，
 * 因此并发下不会超发（同 JVM 内严格不超阈值）。
 *
 * <p><b>时间源可注入：</b>构造器接受 {@link LongSupplier}（默认 {@link System#currentTimeMillis()}），
 * 单元测试用假时钟推进时间即可验证窗口滑动，无需真实 sleep。
 *
 * <p><b>内存有界（取舍说明）：</b>
 * <ul>
 *   <li>单个队列长度天然 &le; 阈值（拒绝路径不入队），所以单键占用有上界；</li>
 *   <li>键条目数没有天然上界（用户 × 作用域），因此做了两层防膨胀：
 *       ① 判定后若队列已空则用 {@code remove(key, deque)} 精确摘除条目；
 *       ② <b>机会式清理</b>：每 {@value #SWEEP_INTERVAL} 次判定、或条目数超过
 *       {@value #MAX_TRACKED_KEYS} 时，全量扫一遍并丢弃「最后时间戳已落在窗口外」的条目。</li>
 * </ul>
 * 取舍：不为限流引入额外的 {@code @Scheduled} 调度线程（阶段七不扩编排面），代价是过期条目
 * 最坏会多存活 {@value #SWEEP_INTERVAL} 次判定；按当前阈值（20/5/10 每分钟）与单机用户量，
 * 条目数与内存占用都在千级以内，可接受。降级期间它是临时兜底，Redis 恢复后计数回到 Redis 侧。
 */
public class InMemorySlidingWindowRateLimiter implements RateLimiter {

    /** 机会式清理触发间隔（判定次数）。 */
    static final int SWEEP_INTERVAL = 512;

    /** 键条目数上限：超过即立刻触发一次全量清理。 */
    static final int MAX_TRACKED_KEYS = 10_000;

    private static final Logger log = LoggerFactory.getLogger(InMemorySlidingWindowRateLimiter.class);

    private final Map<String, Deque<Long>> windows = new ConcurrentHashMap<>();
    private final AtomicLong decisions = new AtomicLong();
    private final LongSupplier clock;

    /** 生产/装配用：真实系统时间。 */
    public InMemorySlidingWindowRateLimiter() {
        this(System::currentTimeMillis);
    }

    /** 测试用：注入假时钟，靠推进时间验证窗口滑动。 */
    public InMemorySlidingWindowRateLimiter(LongSupplier clock) {
        this.clock = clock;
    }

    @Override
    public boolean tryAcquire(RateLimitScope scope, long userId, int limitPerMinute) {
        if (limitPerMinute <= 0) {
            // 阈值≤0 = 该作用域不限流：不建队列、不占内存，直接放行
            return true;
        }
        String key = scope.windowKey(userId);
        long now = clock.getAsLong();
        long windowStart = now - WINDOW_MILLIS;
        sweepIfNeeded(windowStart);

        Deque<Long> timestamps = windows.computeIfAbsent(key, ignored -> new ArrayDeque<>());
        boolean allowed;
        synchronized (timestamps) {
            while (!timestamps.isEmpty() && timestamps.peekFirst() <= windowStart) {
                timestamps.pollFirst();
            }
            allowed = timestamps.size() < limitPerMinute;
            if (allowed) {
                timestamps.addLast(now);
            } else if (timestamps.isEmpty()) {
                // 理论不可达（limitPerMinute>0 时空队列必然放行）；防御性摘除空条目
                windows.remove(key, timestamps);
            }
        }
        if (!allowed) {
            log.debug("单机内存窗口限流拒绝 scope={} userId={} limitPerMin={} windowCount={}",
                    scope.key(), userId, limitPerMinute, limitPerMinute);
        }
        return allowed;
    }

    /** 机会式清理：按判定次数或条目数上限触发，丢弃最后时间戳已出窗口的条目。 */
    private void sweepIfNeeded(long windowStart) {
        if (decisions.incrementAndGet() % SWEEP_INTERVAL != 0 && windows.size() <= MAX_TRACKED_KEYS) {
            return;
        }
        int before = windows.size();
        windows.forEach((key, timestamps) -> {
            synchronized (timestamps) {
                Long last = timestamps.peekLast();
                if (last == null || last <= windowStart) {
                    windows.remove(key, timestamps);
                }
            }
        });
        log.debug("单机内存窗口机会式清理完成 entriesBefore={} entriesAfter={}", before, windows.size());
    }

    /** 当前跟踪的窗口条目数（只读，测试与排查用）。 */
    int trackedKeyCount() {
        return windows.size();
    }
}