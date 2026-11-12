package com.example.aimindmap.metrics;

import com.example.aimindmap.assistant.RedisChatRecentMessageCache;
import com.example.aimindmap.progress.LocalSseRegistry;
import com.example.aimindmap.ratelimit.RateLimitInterceptor;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * 阶段八-1：把阶段七已有的三个进程内计数/状态导出为 micrometer 指标
 * （方案 325 行第 1 项的 ⑤缓存命中率、⑥限流拒绝数、⑦SSE 连接数）。
 *
 * <p><b>零改动导出：</b>阶段七-2 的 {@code RedisChatRecentMessageCache#getHits()/getMisses()}、
 * 阶段七-3 的 {@code RateLimitInterceptor#getRejections()} 都是既有的只读 AtomicLong 计数器，
 * 这里用 {@link FunctionCounter} 直接绑定它们的读方法，<b>不改动那两个类的任何一行</b>
 * （计数逻辑、日志、降级行为全部原样保留）；SSE 连接数用 {@link Gauge} 绑定
 * {@code LocalSseRegistry} 本步新增的只读统计方法。
 *
 * <p><b>指标清单（Prometheus 名）：</b>
 * <ul>
 *   <li>{@code aimindmap_chat_recent_cache_hits_total} / {@code aimindmap_chat_recent_cache_misses_total}
 *       （FunctionCounter）：阶段八-2 的命中率面板按 {@code hits / (hits + misses)} 计算，
 *       本步只导出分子分母、不导出比值（比值交给 PromQL，避免进程内做无意义的除法）；
 *       {@code app.cache.redis-enabled=false} 时装配的是 Disabled 空实现、没有计数器，
 *       两条序列恒为 0（命中率面板显示无数据属预期，不是缺陷）；</li>
 *   <li>{@code aimindmap_ratelimit_rejections_total}（FunctionCounter）：限流累计拒绝数，
 *       无 tag（拦截器计数本身不分作用域，作用域维度看既有 warn 日志与
 *       {@code http_server_requests_seconds{status="429"}}）；</li>
 *   <li>{@code aimindmap_sse_connections}（Gauge）：本 JVM 当前活跃 SSE 连接数
 *       （多实例部署时各实例分别上报，面板用 sum 聚合）。</li>
 * </ul>
 *
 * <p><b>为什么状态对象传 {@code this}：</b>micrometer 的 FunctionCounter/Gauge 只持有状态对象的
 * <b>弱引用</b>，若直接传 lambda 捕获的局部对象可能被 GC 回收导致指标静默归零；
 * 传本单例 Bean 自身（由容器强引用）是官方推荐写法，读方法内再转发到真实数据源。
 *
 * <p><b>为什么用 ObjectProvider：</b>{@code RedisChatRecentMessageCache} 是
 * {@code app.cache.redis-enabled=true} 才装配的条件 Bean（测试上下文默认 false），
 * 直接注入会让上下文启动失败；{@code RateLimitInterceptor} 同理做防护。
 * 阶段七 7-1 的单轮工具结果缓存<b>不在这里绑定</b>：它的命中已落库
 * （{@code agent_tool_calls.from_cache} / {@code agent_runs.cache_hits}，V10 迁移），
 * 再埋一份指标属重复口径，命中率用 SQL 聚合即可。
 */
@Component
public class BusinessMetricsBinder {

    static final String CACHE_HITS_NAME = "aimindmap.chat.recent.cache.hits";
    static final String CACHE_MISSES_NAME = "aimindmap.chat.recent.cache.misses";
    static final String RATE_LIMIT_REJECTIONS_NAME = "aimindmap.ratelimit.rejections";
    static final String SSE_CONNECTIONS_NAME = "aimindmap.sse.connections";

    /** 可为 null：缓存关闭（redis-enabled=false）时容器里没有 Redis 实现。 */
    private final RedisChatRecentMessageCache chatRecentMessageCache;
    /** 可为 null：仅作防护，正常上下文里限流拦截器总是存在。 */
    private final RateLimitInterceptor rateLimitInterceptor;
    private final LocalSseRegistry localSseRegistry;

    public BusinessMetricsBinder(MeterRegistry registry,
                                 ObjectProvider<RedisChatRecentMessageCache> chatCacheProvider,
                                 ObjectProvider<RateLimitInterceptor> rateLimitInterceptorProvider,
                                 LocalSseRegistry localSseRegistry) {
        this.chatRecentMessageCache = chatCacheProvider.getIfAvailable();
        this.rateLimitInterceptor = rateLimitInterceptorProvider.getIfAvailable();
        this.localSseRegistry = localSseRegistry;

        FunctionCounter.builder(CACHE_HITS_NAME, this, BusinessMetricsBinder::cacheHits)
                .description("短期记忆缓存（阶段七-2）累计命中次数；命中率 = hits / (hits + misses)")
                .register(registry);
        FunctionCounter.builder(CACHE_MISSES_NAME, this, BusinessMetricsBinder::cacheMisses)
                .description("短期记忆缓存（阶段七-2）累计未命中次数（含缓存故障与坏 JSON 按未命中）")
                .register(registry);
        FunctionCounter.builder(RATE_LIMIT_REJECTIONS_NAME, this, BusinessMetricsBinder::rateLimitRejections)
                .description("接口限流（阶段七-3）累计拒绝次数")
                .register(registry);
        Gauge.builder(SSE_CONNECTIONS_NAME, this, BusinessMetricsBinder::sseConnections)
                .description("本机当前活跃 SSE 连接数（阶段七-1 跨实例广播下每实例分别上报）")
                .register(registry);
    }

    private double cacheHits() {
        return chatRecentMessageCache == null ? 0d : chatRecentMessageCache.getHits();
    }

    private double cacheMisses() {
        return chatRecentMessageCache == null ? 0d : chatRecentMessageCache.getMisses();
    }

    private double rateLimitRejections() {
        return rateLimitInterceptor == null ? 0d : rateLimitInterceptor.getRejections();
    }

    private double sseConnections() {
        return localSseRegistry.getActiveConnectionCount();
    }
}
