package com.example.aimindmap.ratelimit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 阶段七-3：限流装配（镜像阶段七-1 {@code ProgressRedisConfiguration} / 阶段七-2
 * {@code RedisChatRecentMessageCache} 的 {@code app.cache.redis-enabled} 条件装配模式）。
 *
 * <ul>
 *   <li>{@code redis-enabled=true}（缺省，matchIfMissing）→ {@link RedisSlidingWindowRateLimiter}
 *       为主，内部持有单机内存窗口作为 Redis 异常时的兜底；</li>
 *   <li>{@code redis-enabled=false} → {@link InMemorySlidingWindowRateLimiter} 纯内存窗口
 *       （方案 §3.5 第 125 行）。</li>
 * </ul>
 * 两个 {@code @Bean} 方法条件互斥，容器内有且仅有一个 {@link RateLimiter}，注入点无需 {@code @Primary}。
 *
 * <p><b>为什么本类刻意不实现 {@code WebMvcConfigurer}：</b>{@code @WebMvcTest} 切片
 * （如 {@code SecurityWebLayerTest}）会扫描 {@code WebMvcConfigurer} 类型的 {@code @Configuration}，
 * 但切片上下文里没有 {@link RateLimiter} 这类业务 Bean。拦截器注册放在
 * {@link RateLimitWebConfiguration} 并用 {@code ObjectProvider} 防护，本类保持普通配置类即可被切片排除，
 * 既有切片测试零改动仍绿。
 *
 * <p>不引入任何新依赖：Lua 经既有 spring-data-redis 的 {@code DefaultRedisScript} 执行，
 * 连接复用既有 {@code spring.data.redis.*}（本项目 db2）。
 */
@Configuration(proxyBeanMethods = false)
public class RateLimitConfiguration {

    @Bean
    @ConditionalOnProperty(name = "app.cache.redis-enabled", havingValue = "true", matchIfMissing = true)
    public RateLimiter redisSlidingWindowRateLimiter(
            StringRedisTemplate redisTemplate,
            @Value("${app.cache.key-prefix:aimindmap:}") String keyPrefix) {
        return new RedisSlidingWindowRateLimiter(redisTemplate, keyPrefix,
                new InMemorySlidingWindowRateLimiter());
    }

    @Bean
    @ConditionalOnProperty(name = "app.cache.redis-enabled", havingValue = "false")
    public RateLimiter inMemorySlidingWindowRateLimiter() {
        return new InMemorySlidingWindowRateLimiter();
    }

    @Bean
    public RateLimitInterceptor rateLimitInterceptor(RateLimiter rateLimiter,
                                                     RateLimitThresholds thresholds) {
        return new RateLimitInterceptor(rateLimiter, thresholds);
    }
}