package com.example.aimindmap.agenttracing;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 单次请求级工具调用计数器（方案 §4.3）。
 * 每次问答新建一个，绝不跨请求复用；第 maxToolCalls+1 次调用被拦截。
 *
 * <p>P1-D1（方案 §P1-4）：新增 {@link #tryAcquireSoft()}。
 * 硬中止模式继续用 {@link #tryAcquire()}（超限那次仍自增，保留历史计数口径）；
 * 软降级模式用 {@code tryAcquireSoft()}——超限返回 false 且<b>不增加计数</b>，
 * 这样 {@code guard.acquiredCount()} 始终等于"真实占用名额的次数"，
 * 不会因为反复被拦而把 {@code agent_runs} 的工具调用数推高。</p>
 */
public final class AgentToolCallGuard {

    private final int maxToolCalls;
    private final AtomicInteger acquired = new AtomicInteger();

    public AgentToolCallGuard(int maxToolCalls) {
        this.maxToolCalls = maxToolCalls;
    }

    /**
     * 尝试占用一次工具调用名额（硬中止模式）。
     *
     * @return true 允许执行；false 已超过上限，调用方必须拦截并中止
     */
    public boolean tryAcquire() {
        return acquired.incrementAndGet() <= maxToolCalls;
    }

    /**
     * 尝试占用一次工具调用名额（软降级模式）。
     *
     * <p>语义：还有名额 → 占用并返回 true；已用满 → 返回 false 且<b>不增加计数</b>。
     * 用 CAS 循环实现，保证并发下也不会把计数推过 maxToolCalls。</p>
     *
     * @return true 允许执行；false 预算已用完（调用方返回固定提示文本，不抛异常）
     */
    public boolean tryAcquireSoft() {
        while (true) {
            int current = acquired.get();
            if (current >= maxToolCalls) {
                return false;
            }
            if (acquired.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    public int acquiredCount() {
        return acquired.get();
    }
}
