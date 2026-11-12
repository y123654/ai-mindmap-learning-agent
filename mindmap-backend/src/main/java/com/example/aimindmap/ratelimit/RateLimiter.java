package com.example.aimindmap.ratelimit;

/**
 * 阶段七-3：滑动窗口限流器接口（方案 §阶段七 第 3 条「Redis + Lua 原子滑动窗口」）。
 *
 * <p>两个实现，由 {@code app.cache.redis-enabled} 条件装配（镜像阶段七-1/-2 的开关模式，
 * 装配点见 {@link RateLimitConfiguration}）：
 * <ul>
 *   <li>{@link RedisSlidingWindowRateLimiter}：缺省（true / 属性缺失）装配；ZSET + Lua 原子判定，
 *       多实例共享同一窗口计数；任何 Redis 异常降级到单机内存窗口执行本次判定；</li>
 *   <li>{@link InMemorySlidingWindowRateLimiter}：redis-enabled=false 时装配（方案 §3.5 第 125 行
 *       「限流走单机内存窗口」）；同时也是 Redis 实现的降级兜底。</li>
 * </ul>
 *
 * <p><b>阈值语义（方案 321 行「阈值可配」）：</b>{@code limitPerMinute} 由
 * {@link RateLimitThresholds} 从 {@code app.ratelimit.*} 读入；<b>阈值 &le; 0 视为该作用域不限流</b>
 * （直接放行且不产生任何窗口数据）。把附录 A 的三个阈值同时设为 0，即等价于「一键关闭限流」，
 * 这样既满足 §3.5「所有新能力默认可一键关闭」，又不新增附录 A 之外的配置键。
 *
 * <p><b>绝不抛异常：</b>限流是旁路能力，实现必须把外部故障吞成降级判定，不许让 Redis 宕机
 * 把正常业务请求变成 500（方案 §3.5 第 127 行「不许级联雪崩」）。
 */
public interface RateLimiter {

    /** 滑动窗口长度，固定 60 秒（阈值单位为「次/分钟」，两者必须同口径）。 */
    long WINDOW_MILLIS = 60_000L;

    /**
     * 尝试占用一个窗口名额。
     *
     * @param scope          作用域（决定键名与阈值来源）
     * @param userId         登录用户 ID（限流粒度 = 作用域 × 用户，不做全局/IP 粒度）
     * @param limitPerMinute 窗口内允许的最大请求数；&le; 0 表示该作用域不限流，恒返回 true
     * @return true = 放行；false = 超限（调用方抛 {@code RateLimitExceededException} 转 429）
     */
    boolean tryAcquire(RateLimitScope scope, long userId, int limitPerMinute);
}