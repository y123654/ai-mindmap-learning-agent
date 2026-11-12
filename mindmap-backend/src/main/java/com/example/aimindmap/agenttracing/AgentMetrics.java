package com.example.aimindmap.agenttracing;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * P0-E1：Agent 链路的四类 Micrometer 指标（真实埋点，无重复计数）。
 *
 * <table>
 *   <tr><th>指标</th><th>类型</th><th>tag</th><th>埋点位置</th></tr>
 *   <tr><td>{@code agent.run.duration}</td><td>Timer</td><td>outcome=success/error</td>
 *       <td>{@link com.example.aimindmap.assistant.KnowledgeAssistantStreamer} 的 finally（每 run 恰好 1 次）</td></tr>
 *   <tr><td>{@code agent.tool.calls}</td><td>Counter</td><td>tool、phase=success/failed</td>
 *       <td>{@code AgentTracingService} 的工具调用落库节点（每工具调用恰好 1 次）</td></tr>
 *   <tr><td>{@code agent.citation.coverage}</td><td>DistributionSummary</td><td>无</td>
 *       <td>流结束后引用校验结果（每 run 1 个样本，0~1）</td></tr>
 *   <tr><td>{@code agent.grounded}</td><td>Counter</td><td>grounded=true/false</td>
 *       <td>同上，每 run 恰好 1 次</td></tr>
 * </table>
 *
 * <p>标签基数控制：tool 只有固定的 6 个工具名，outcome/phase/grounded 都是枚举值，
 * 不含 userId / sessionId / runId / 正文，与阶段八-1 的"低基数 tag"约定一致。</p>
 *
 * <p>空实现 {@link #noop()} 用于不装配 Spring 的单测/旧调用方（只保留旧构造器），
 * 生产环境永远由 Spring 注入真实 {@link MeterRegistry}（此时
 * {@code /actuator/prometheus} 可直接抓到上述指标）。</p>
 */
@Component
public class AgentMetrics {

    public static final String RUN_DURATION = "agent.run.duration";
    public static final String TOOL_CALLS = "agent.tool.calls";
    public static final String CITATION_COVERAGE = "agent.citation.coverage";
    public static final String GROUNDED = "agent.grounded";

    public static final String TAG_OUTCOME = "outcome";
    public static final String OUTCOME_SUCCESS = "success";
    public static final String OUTCOME_ERROR = "error";

    public static final String TAG_TOOL = "tool";
    public static final String TAG_PHASE = "phase";
    public static final String PHASE_SUCCESS = "success";
    public static final String PHASE_FAILED = "failed";

    public static final String TAG_GROUNDED = "grounded";

    /** 工具名缺失时的兜底 tag 值，避免空 tag 造成诡异序列。 */
    static final String UNKNOWN_TOOL = "unknown";

    private static final AgentMetrics NOOP = new AgentMetrics();

    /** null 表示空实现（不注册任何 meter）。 */
    private final MeterRegistry registry;

    public AgentMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    private AgentMetrics() {
        this.registry = null;
    }

    /** 空实现：指标调用全部短路，供不装配 Spring 的单测/旧构造器使用。 */
    public static AgentMetrics noop() {
        return NOOP;
    }

    /**
     * 一次 run 的总耗时。成功/失败都必须调用，且每 run 只调用一次（由 Streamer 的 finally 保证）。
     *
     * @param duration 从 run 开始到本次记录的真实耗时
     * @param success  run 是否成功（决定 outcome tag）
     */
    public void recordRunDuration(Duration duration, boolean success) {
        if (registry == null || duration == null) {
            return;
        }
        Timer timer = Timer.builder(RUN_DURATION)
                .description("Agent run 端到端耗时")
                .tag(TAG_OUTCOME, success ? OUTCOME_SUCCESS : OUTCOME_ERROR)
                .register(registry);
        timer.record(duration);
    }

    /**
     * 一次工具调用结果。成功（含缓存命中）与失败各记一次，不重复计数。
     *
     * @param toolName 工具名（固定 6 个之一）
     * @param success  是否成功
     */
    public void recordToolCall(String toolName, boolean success) {
        if (registry == null) {
            return;
        }
        String tool = toolName == null || toolName.isBlank() ? UNKNOWN_TOOL : toolName;
        Counter counter = Counter.builder(TOOL_CALLS)
                .description("Agent 工具调用次数（按工具与结果）")
                .tag(TAG_TOOL, tool)
                .tag(TAG_PHASE, success ? PHASE_SUCCESS : PHASE_FAILED)
                .register(registry);
        counter.increment();
    }

    /**
     * 一次 run 的引用覆盖率（0~1，按 run 记录一个样本，可求平均与分位）。
     * 超出 [0,1] 的值夹紧后再记录，防止脏数据把分布拉偏。
     */
    public void recordCitationCoverage(double coverage) {
        if (registry == null) {
            return;
        }
        double clamped = Double.isNaN(coverage) ? 0d : Math.max(0d, Math.min(1d, coverage));
        DistributionSummary summary = DistributionSummary.builder(CITATION_COVERAGE)
                .description("Agent 引用覆盖率（有合法引用的事实段落 / 事实段落总数）")
                .baseUnit("ratio")
                .register(registry);
        summary.record(clamped);
    }

    /** 一次 run 是否有至少一条合法引用；每 run 恰好调用一次。 */
    public void recordGrounded(boolean grounded) {
        if (registry == null) {
            return;
        }
        Counter counter = Counter.builder(GROUNDED)
                .description("Agent 回答是否有合法引用")
                .tag(TAG_GROUNDED, Boolean.toString(grounded))
                .register(registry);
        counter.increment();
    }

    /** 仅用于诊断与单测：false 表示空实现（生产永远为 true）。 */
    public boolean enabled() {
        return registry != null;
    }
}
