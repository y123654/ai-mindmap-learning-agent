package com.example.aimindmap.agentruntime;

import java.time.Duration;

/**
 * P1-D2b：单次 Agent run 的<b>唯一</b>总预算。
 *
 * <p>由「起点 {@code startedNanos} + 总时长 {@code timeout}」构造，之后只读：
 * 本类<b>不可变、无状态</b>，每次询问剩余时间都以「当前时刻」重新计算，
 * 因此同一个实例可以被第一轮流式、计划编排（Planner / Executor / Synthesizer）、
 * B2 零调用补检等多个阶段共享——<b>所有阶段都从这一份预算里扣时间</b>，
 * 不再各自新开 {@code app.agent.timeout-seconds}。
 *
 * <p>取舍（为什么不做成“剩余毫秒数”传参）：剩余量会随时间变化，传一次数字就等于冻结了快照；
 * 传对象才能保证“越晚问、剩得越少”。本类不持有任何可写字段，多线程读取安全。
 *
 * <p>边界约定：
 * <ul>
 *   <li>{@link #remainingMillis()} / {@link #remainingDuration()} / {@link #remainingNanos()}
 *       <b>永不为负</b>，耗尽后统一返回 0（调用方不需要再判负数）；</li>
 *   <li>{@link #expired()} 在剩余为 0 时为 {@code true}；</li>
 *   <li>总时长为 0 的预算构造出来即已耗尽（用于“这一轮没有预算”的极端场景）。</li>
 * </ul>
 *
 * <p>本类只做时间计算，不读配置、不写库、不打日志、不包含任何密钥。
 */
public final class AgentRunBudget {

    /** 预算起点（{@link System#nanoTime()}）。 */
    private final long startedNanos;

    /** 预算总时长（纳秒），一定 ≥ 0。 */
    private final long timeoutNanos;

    /**
     * @param startedNanos 预算起点，必须来自 {@link System#nanoTime()}
     *                     （只有同一时钟源才能与 {@link System#nanoTime()} 相减）
     * @param timeout      预算总时长，不能为 null / 负数（0 表示“立即耗尽”）
     * @throws IllegalArgumentException 时长为 null 或负数
     */
    public AgentRunBudget(long startedNanos, Duration timeout) {
        if (timeout == null) {
            throw new IllegalArgumentException("预算时长不能为 null");
        }
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("预算时长不能为负：" + timeout);
        }
        this.startedNanos = startedNanos;
        this.timeoutNanos = timeout.toNanos();
    }

    /** 以「现在」为起点创建预算（生产入口的便捷写法）。 */
    public static AgentRunBudget startingNow(Duration timeout) {
        return new AgentRunBudget(System.nanoTime(), timeout);
    }

    /** 预算截止时刻（纳秒时间轴，仅供同进程内比较，不是墙上时间）。 */
    public long deadlineNanos() {
        return startedNanos + timeoutNanos;
    }

    /** 预算总时长（纳秒）。 */
    public long timeoutNanos() {
        return timeoutNanos;
    }

    /** 剩余纳秒数，不小于 0。 */
    public long remainingNanos() {
        return Math.max(0L, deadlineNanos() - System.nanoTime());
    }

    /** 剩余毫秒数（向下取整），不小于 0；耗尽后为 0。 */
    public long remainingMillis() {
        return remainingNanos() / 1_000_000L;
    }

    /** 剩余时长（{@link Duration#ZERO} 起底），可直接交给超时执行器。 */
    public Duration remainingDuration() {
        return Duration.ofNanos(remainingNanos());
    }

    /** 预算是否已耗尽（剩余 ≤ 0）。 */
    public boolean expired() {
        return remainingNanos() <= 0L;
    }
}
