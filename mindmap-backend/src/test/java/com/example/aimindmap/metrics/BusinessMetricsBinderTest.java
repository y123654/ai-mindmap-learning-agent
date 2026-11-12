package com.example.aimindmap.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.aimindmap.assistant.RedisChatRecentMessageCache;
import com.example.aimindmap.progress.LocalSseRegistry;
import com.example.aimindmap.ratelimit.RateLimitInterceptor;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * 阶段八-1：既有计数/状态导出为指标的单测（方案 325 行 ⑤缓存命中率、⑥限流拒绝数、⑦SSE 连接数）。
 *
 * <p>阶段七的 {@code RedisChatRecentMessageCache} 与 {@code RateLimitInterceptor} 一律用 mock
 * 提供只读计数（不连 Redis、不构造真实限流器），SSE 用真实 {@link LocalSseRegistry}
 * （subscribe 不需要 servlet 容器，与 {@code LocalSseRegistryTest} 同口径）。
 * 重点验证两件事：绑定是<b>活的</b>（数据源变化后指标跟着变，不是构造时快照），
 * 以及缓存 Bean 缺失（{@code app.cache.redis-enabled=false}）时序列仍存在且为 0。
 */
class BusinessMetricsBinderTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final LocalSseRegistry sseRegistry = new LocalSseRegistry();

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> providerOf(T instance) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(instance);
        return provider;
    }

    private BusinessMetricsBinder newBinder(RedisChatRecentMessageCache cache,
                                            RateLimitInterceptor interceptor) {
        return new BusinessMetricsBinder(registry, providerOf(cache), providerOf(interceptor), sseRegistry);
    }

    // ---------- ⑤ 缓存命中/未命中（阶段八-2 面板按 hits/(hits+misses) 算命中率） ----------

    @Test
    void cacheHitsAndMissesAreExportedAsFunctionCounters() {
        RedisChatRecentMessageCache cache = mock(RedisChatRecentMessageCache.class);
        when(cache.getHits()).thenReturn(3L);
        when(cache.getMisses()).thenReturn(7L);

        newBinder(cache, mock(RateLimitInterceptor.class));

        FunctionCounter hits = registry.get("aimindmap.chat.recent.cache.hits").functionCounter();
        FunctionCounter misses = registry.get("aimindmap.chat.recent.cache.misses").functionCounter();
        assertThat(hits.count()).isEqualTo(3.0);
        assertThat(misses.count()).isEqualTo(7.0);
        assertThat(hits.getId().getTags()).isEmpty();
    }

    @Test
    void cacheCountersFollowLiveCounterChanges() {
        RedisChatRecentMessageCache cache = mock(RedisChatRecentMessageCache.class);
        when(cache.getHits()).thenReturn(1L);
        when(cache.getMisses()).thenReturn(1L);
        newBinder(cache, mock(RateLimitInterceptor.class));

        when(cache.getHits()).thenReturn(9L);

        assertThat(registry.get("aimindmap.chat.recent.cache.hits").functionCounter().count())
                .isEqualTo(9.0);
        assertThat(registry.get("aimindmap.chat.recent.cache.misses").functionCounter().count())
                .isEqualTo(1.0);
    }

    @Test
    void missingCacheBeanStillRegistersZeroCounters() {
        newBinder(null, mock(RateLimitInterceptor.class));

        // app.cache.redis-enabled=false（全部测试上下文的默认值）装配的是 Disabled 空实现，
        // 容器里没有 Redis 实现；序列仍必须存在且为 0，否则阶段八-2 面板会因缺序列报错。
        assertThat(registry.get("aimindmap.chat.recent.cache.hits").functionCounter().count()).isZero();
        assertThat(registry.get("aimindmap.chat.recent.cache.misses").functionCounter().count()).isZero();
    }

    // ---------- ⑥ 限流拒绝数 ----------

    @Test
    void rateLimitRejectionsAreExportedAsFunctionCounter() {
        RateLimitInterceptor interceptor = mock(RateLimitInterceptor.class);
        when(interceptor.getRejections()).thenReturn(5L);

        newBinder(mock(RedisChatRecentMessageCache.class), interceptor);

        assertThat(registry.get("aimindmap.ratelimit.rejections").functionCounter().count())
                .isEqualTo(5.0);
    }

    @Test
    void missingInterceptorStillRegistersZeroCounter() {
        newBinder(mock(RedisChatRecentMessageCache.class), null);

        assertThat(registry.get("aimindmap.ratelimit.rejections").functionCounter().count()).isZero();
    }

    // ---------- ⑦ SSE 连接数 ----------

    @Test
    void sseConnectionsGaugeCountsActiveEmitters() {
        newBinder(mock(RedisChatRecentMessageCache.class), mock(RateLimitInterceptor.class));
        Gauge gauge = registry.get("aimindmap.sse.connections").gauge();
        assertThat(gauge.value()).isZero();

        sseRegistry.subscribe(7001L);
        sseRegistry.subscribe(7001L);
        sseRegistry.subscribe(7002L);

        assertThat(gauge.value()).isEqualTo(3.0);
    }

    @Test
    void sseConnectionsGaugeDropsAfterComplete() {
        newBinder(mock(RedisChatRecentMessageCache.class), mock(RateLimitInterceptor.class));
        sseRegistry.subscribe(7001L);
        sseRegistry.subscribe(7002L);

        sseRegistry.complete(7001L);

        assertThat(registry.get("aimindmap.sse.connections").gauge().value()).isEqualTo(1.0);
    }

    // ---------- 指标名与类型总览（阶段八-2 面板引用依据） ----------

    @Test
    void binderRegistersExactlyFourAimindmapMeters() {
        newBinder(mock(RedisChatRecentMessageCache.class), mock(RateLimitInterceptor.class));

        assertThat(registry.getMeters()).extracting(Meter::getId)
                .extracting(io.micrometer.core.instrument.Meter.Id::getName)
                .containsExactlyInAnyOrder("aimindmap.chat.recent.cache.hits",
                        "aimindmap.chat.recent.cache.misses",
                        "aimindmap.ratelimit.rejections",
                        "aimindmap.sse.connections");
        assertThat(registry.getMeters()).allSatisfy(meter ->
                assertThat(meter.getId().getType()).isIn(Meter.Type.GAUGE, Meter.Type.COUNTER));
    }
}
