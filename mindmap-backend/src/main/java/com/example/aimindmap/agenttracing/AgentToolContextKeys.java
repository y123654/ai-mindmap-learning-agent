package com.example.aimindmap.agenttracing;

/** ToolContext 键名：当前用户与本次 run 只通过 ToolContext 透传（R-03）。 */
public final class AgentToolContextKeys {

    public static final String USER_ID = "userId";
    public static final String RUN_ID = "runId";
    /** 单次 run 的证据台账（com.example.aimindmap.agentruntime.EvidenceLedger）。 */
    public static final String EVIDENCE_LEDGER = "evidenceLedger";
    /** 单次 run 的工具过程事件出口（com.example.aimindmap.agenttracing.AgentToolEventSink）。 */
    public static final String TOOL_EVENT_SINK = "toolEventSink";

    /**
     * P1-D2b：本轮 run 的剩余总预算（毫秒，{@link Long} 快照）。
     *
     * <p>由 Streamer 在装配 {@code ToolContext} 时放入（值 = {@code AgentRunBudget.remainingMillis()}）；
     * {@link TracingToolCallback} 重试前读它决定“剩余预算够不够再跑一次完整尝试”。
     * <b>不存在该 key 时保持旧行为</b>（重试 1 次），因此老调用方与单测无需改动。
     *
     * <p>注意：这是快照值，装配后不再更新，越晚读越乐观（详见 P1-D2b 完成汇报的待注意点）。
     */
    public static final String REMAINING_BUDGET_MS = "remainingBudgetMs";

    private AgentToolContextKeys() {
    }
}
