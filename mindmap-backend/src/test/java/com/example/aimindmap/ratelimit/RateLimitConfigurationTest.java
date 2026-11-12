package com.example.aimindmap.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;

/**
 * 阶段七-3：限流装配与阈值可配测试（{@code ApplicationContextRunner}，不起完整上下文、不连 Redis）。
 *
 * <p>覆盖方案 321 行验收「阈值可配且有测试」与 §3.5 第 125 行「关闭 Redis 时限流走单机内存窗口」：
 * redis-enabled 三种取值（true / 缺失 / false）各装配哪种实现、阈值来自 {@code app.ratelimit.*}
 * 且缺省回落附录 A 的 20/5/10、阈值≤0 即整体关闭限流；另覆盖
 * {@link RateLimitWebConfiguration} 在限流 Bean 缺失（{@code @WebMvcTest} 切片）时不注册拦截器、
 * 上下文不启动失败。
 */
class RateLimitConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(StringRedisTemplate.class, () -> mock(StringRedisTemplate.class))
            .withUserConfiguration(RateLimitConfiguration.class,
                    RateLimitThresholds.class, RateLimitWebConfiguration.class);

    @Test
    void redisEnabledWiresRedisLimiter() {
        runner.withPropertyValues("app.cache.redis-enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(RateLimiter.class);
                    assertThat(context.getBean(RateLimiter.class))
                            .isInstanceOf(RedisSlidingWindowRateLimiter.class);
                    assertThat(context.getBean(RateLimitInterceptor.class)).isNotNull();
                });
    }

    @Test
    void missingRedisSwitchDefaultsToRedisLimiter() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(RateLimiter.class))
                    .isInstanceOf(RedisSlidingWindowRateLimiter.class);
        });
    }

    @Test
    void redisDisabledWiresPureInMemoryWindowLimiter() {
        runner.withPropertyValues("app.cache.redis-enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(RateLimiter.class);
                    RateLimiter limiter = context.getBean(RateLimiter.class);
                    assertThat(limiter).isInstanceOf(InMemorySlidingWindowRateLimiter.class);

                    // 纯内存窗口模式下限流本身仍然生效：阈值 2，第 3 次拒绝
                    assertThat(limiter.tryAcquire(RateLimitScope.PDF_UPLOAD, 8L, 2)).isTrue();
                    assertThat(limiter.tryAcquire(RateLimitScope.PDF_UPLOAD, 8L, 2)).isTrue();
                    assertThat(limiter.tryAcquire(RateLimitScope.PDF_UPLOAD, 8L, 2)).isFalse();
                });
    }

    @Test
    void redisLimiterShortCircuitsUnlimitedScopeBeforeTouchingRedis() {
        runner.withPropertyValues("app.cache.redis-enabled=true").run(context -> {
            RateLimiter limiter = context.getBean(RateLimiter.class);
            // 阈值≤0 = 该作用域不限流（Redis 实现在触达 Redis 之前短路）
            for (int i = 0; i < 50; i++) {
                assertThat(limiter.tryAcquire(RateLimitScope.AI_CHAT, 8L, 0)).isTrue();
            }
        });
    }

    @Test
    void thresholdsAreConfigurable() {
        runner.withPropertyValues(
                        "app.ratelimit.ai-per-min=3",
                        "app.ratelimit.pdf-per-min=1",
                        "app.ratelimit.sync-per-min=7")
                .run(context -> {
                    RateLimitThresholds thresholds = context.getBean(RateLimitThresholds.class);
                    assertThat(thresholds.limitOf(RateLimitScope.AI_CHAT)).isEqualTo(3);
                    assertThat(thresholds.limitOf(RateLimitScope.PDF_UPLOAD)).isEqualTo(1);
                    assertThat(thresholds.limitOf(RateLimitScope.SYNC_TRIGGER)).isEqualTo(7);
                });
    }

    @Test
    void thresholdsFallBackToPlanDefaults() {
        runner.run(context -> {
            RateLimitThresholds thresholds = context.getBean(RateLimitThresholds.class);
            // 附录 A 第 363 行：20 / 5 / 10
            assertThat(thresholds.aiPerMinute()).isEqualTo(20);
            assertThat(thresholds.pdfPerMinute()).isEqualTo(5);
            assertThat(thresholds.syncPerMinute()).isEqualTo(10);
        });
    }

    @Test
    void zeroThresholdsDisableRateLimitingEntirely() {
        runner.withPropertyValues(
                        "app.ratelimit.ai-per-min=0",
                        "app.ratelimit.pdf-per-min=0",
                        "app.ratelimit.sync-per-min=0")
                .run(context -> {
                    RateLimiter limiter = context.getBean(RateLimiter.class);
                    RateLimitThresholds thresholds = context.getBean(RateLimitThresholds.class);
                    for (RateLimitScope scope : RateLimitScope.values()) {
                        assertThat(thresholds.limitOf(scope)).isZero();
                        for (int i = 0; i < 50; i++) {
                            assertThat(limiter.tryAcquire(scope, 8L, thresholds.limitOf(scope))).isTrue();
                        }
                    }
                });
    }

    @Test
    void webConfigurationRegistersInterceptorWhenBeanPresent() {
        runner.withPropertyValues("app.cache.redis-enabled=false").run(context -> {
            RecordingInterceptorRegistry registry = new RecordingInterceptorRegistry();
            context.getBean(RateLimitWebConfiguration.class).addInterceptors(registry);
            assertThat(registry.recorded).contains(context.getBean(RateLimitInterceptor.class));
        });
    }

    /**
     * {@code @WebMvcTest} 切片保护：切片会扫描 {@code WebMvcConfigurer} 类型的配置类，
     * 但没有 {@link RateLimitInterceptor} Bean。{@code ObjectProvider} 必须让注册静默跳过，
     * 上下文照常启动（{@code SecurityWebLayerTest} 零改动仍绿的前提）。
     */
    @Test
    void webConfigurationSurvivesMissingInterceptorBean() {
        new ApplicationContextRunner()
                .withUserConfiguration(RateLimitWebConfiguration.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    RecordingInterceptorRegistry registry = new RecordingInterceptorRegistry();
                    context.getBean(RateLimitWebConfiguration.class).addInterceptors(registry);
                    assertThat(registry.recorded).isEmpty();
                });
    }

    /** InterceptorRegistry#getInterceptors 是 protected 且存的是注册包装对象，这里改为记录入参。 */
    static class RecordingInterceptorRegistry extends InterceptorRegistry {

        final List<HandlerInterceptor> recorded = new ArrayList<>();

        @Override
        public InterceptorRegistration addInterceptor(HandlerInterceptor interceptor) {
            recorded.add(interceptor);
            return super.addInterceptor(interceptor);
        }
    }
}