package com.example.aimindmap.ratelimit;

import java.util.List;
import java.util.UUID;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * 阶段七-3：Redis + Lua 原子滑动窗口限流器（方案 §阶段七 第 3 条）。
 *
 * <p><b>数据结构：</b>每个「作用域 × 用户」一个 ZSET，score = 请求发生的毫秒时间戳，
 * member = 唯一串（UUID）。一次判定在<b>单个 Lua 脚本内原子完成</b>：
 * ZREMRANGEBYSCORE 清掉窗口外成员 → ZCARD 计数 → 达阈值直接返回 0（拒绝、不写入）→
 * 未达阈值 ZADD(now, member) 并 PEXPIRE 键 = 窗口长，返回 1（放行）。
 * 因为整段脚本在 Redis 单线程内执行，多实例并发不会出现「同时读到 N-1 然后都放行」的竞态，
 * 这是选 Lua 而不是 ZREMRANGEBYSCORE + ZCARD + ZADD 三次往返的原因。
 *
 * <p><b>键布局：</b>严格 {@code {app.cache.key-prefix}ratelimit:{scope}:{userId}}
 * （前缀复用既有配置注入、默认 {@code aimindmap:}；本项目固定 Redis db2，见方案 §0 第 3 条）。
 * 窗口长度固定 {@value RateLimiter#WINDOW_MILLIS} 毫秒，与阈值单位「次/分钟」同口径。
 *
 * <p><b>时间源：</b>脚本里的 now 由应用侧传入（{@link LongSupplier}，可注入以便单测），
 * 不用 Redis TIME——省一次往返、也让降级路径与内存窗口共用同一时钟语义。
 * 多实例间的时钟偏差只要远小于 60 秒窗口就不影响正确性（本机部署偏差为毫秒级）。
 *
 * <p><b>降级（方案 321 行「Redis 宕机优雅降级」）：</b>任何 {@link DataAccessException}
 * 或脚本返回 null，一律 catch→warn→<b>本次判定改由单机内存窗口执行</b>。
 * 即：Redis 挂了限流退化为「每实例各自 60 秒窗口」，既不放行一切、也不把业务请求打成 500。
 *
 * <p><b>日志红线：</b>只打 scope / userId / 键名 / 阈值 / 计数，绝不打请求正文、令牌或密钥。
 */
public class RedisSlidingWindowRateLimiter implements RateLimiter {

    /**
     * 原子滑动窗口脚本原文（ARGV：1=now 毫秒、2=窗口毫秒、3=阈值、4=唯一 member）。
     *
     * <p>返回值：1 = 放行（已写入本次时间戳并续期），0 = 超限拒绝（不写入）。
     * score/member 都以字符串直接交给 Redis 解析，避免 Lua 数字格式化带来的精度疑虑；
     * 时间戳毫秒（约 1.7e12）远小于 double 的 2^53 精度上界，减法同样精确。
     */
    static final String SLIDING_WINDOW_LUA = """
            local windowStart = tonumber(ARGV[1]) - tonumber(ARGV[2])
            redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', windowStart)
            if redis.call('ZCARD', KEYS[1]) >= tonumber(ARGV[3]) then
              return 0
            end
            redis.call('ZADD', KEYS[1], ARGV[1], ARGV[4])
            redis.call('PEXPIRE', KEYS[1], tonumber(ARGV[2]))
            return 1
            """;

    private static final Logger log = LoggerFactory.getLogger(RedisSlidingWindowRateLimiter.class);

    private final RedisScript<Long> script;
    private final StringRedisTemplate redisTemplate;
    private final String keyPrefix;
    private final RateLimiter fallback;
    private final LongSupplier clock;

    public RedisSlidingWindowRateLimiter(StringRedisTemplate redisTemplate,
                                         String keyPrefix,
                                         RateLimiter fallback) {
        this(redisTemplate, keyPrefix, fallback, System::currentTimeMillis);
    }

    /** 测试用：注入假时钟与替身兜底限流器。 */
    public RedisSlidingWindowRateLimiter(StringRedisTemplate redisTemplate,
                                         String keyPrefix,
                                         RateLimiter fallback,
                                         LongSupplier clock) {
        this.redisTemplate = redisTemplate;
        this.keyPrefix = keyPrefix;
        this.fallback = fallback;
        this.clock = clock;
        DefaultRedisScript<Long> redisScript = new DefaultRedisScript<>(SLIDING_WINDOW_LUA, Long.class);
        this.script = redisScript;
    }

    @Override
    public boolean tryAcquire(RateLimitScope scope, long userId, int limitPerMinute) {
        if (limitPerMinute <= 0) {
            // 阈值≤0 = 该作用域不限流：连 Redis 都不碰，零往返零键
            return true;
        }
        String key = key(scope, userId);
        long now = clock.getAsLong();
        Long allowed;
        try {
            allowed = redisTemplate.execute(script, List.of(key),
                    String.valueOf(now),
                    String.valueOf(WINDOW_MILLIS),
                    String.valueOf(limitPerMinute),
                    UUID.randomUUID().toString());
        } catch (DataAccessException e) {
            log.warn("限流 Redis 判定失败，本次降级单机内存窗口 scope={} userId={} key={} limitPerMin={}",
                    scope.key(), userId, key, limitPerMinute, e);
            return fallback.tryAcquire(scope, userId, limitPerMinute);
        }
        if (allowed == null) {
            // 管道/事务等场景脚本可能不回值；与异常同等处理，保守降级到内存窗口
            log.warn("限流 Redis 脚本无返回值，本次降级单机内存窗口 scope={} userId={} key={}",
                    scope.key(), userId, key);
            return fallback.tryAcquire(scope, userId, limitPerMinute);
        }
        boolean pass = allowed == 1L;
        if (pass) {
            log.debug("限流放行 scope={} userId={} key={} limitPerMin={}",
                    scope.key(), userId, key, limitPerMinute);
        } else {
            log.warn("限流拒绝（Redis 窗口已满） scope={} userId={} key={} limitPerMin={}",
                    scope.key(), userId, key, limitPerMinute);
        }
        return pass;
    }

    /** 键名：{app.cache.key-prefix}ratelimit:{scope}:{userId}。 */
    private String key(RateLimitScope scope, long userId) {
        return keyPrefix + "ratelimit:" + scope.windowKey(userId);
    }
}