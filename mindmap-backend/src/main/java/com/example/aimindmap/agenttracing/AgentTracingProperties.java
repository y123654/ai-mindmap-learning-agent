package com.example.aimindmap.agenttracing;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Agent 受控限制与追踪摘要配置（方案 §4.3 / §10.3 / §10.4）。
 * 全部可被环境变量覆盖；不包含任何密钥。
 */
@ConfigurationProperties(prefix = "app.agent")
public class AgentTracingProperties {

    /** 单次请求最多工具调用次数（方案 §4.3 建议值 3）。 */
    private int maxToolCalls = 3;
    /**
     * P1-D1（方案 §P1-4）：预算超限时是否软降级。
     * true（默认）= 返回固定提示文本让模型基于已有结果作答，不中止整轮；
     * false = 恢复"第 maxToolCalls+1 次直接抛 AgentRunAbortException"的硬中止行为。
     */
    private boolean toolBudgetSoft = true;
    /** P1-D1（方案 §P1-4）：单次工具调用超时秒数，超时按 AGENT_TOOL_TIMEOUT（retryable）处理。 */
    private int toolTimeoutSeconds = 10;
    /** 工具单次返回字符上限，超出截断并给出提示。 */
    private int toolResultMaxChars = 2500;
    /** Agent 整体执行超时秒数（方案 §4.3 建议 60~90）。 */
    private int timeoutSeconds = 75;
    /** agent_runs.user_message 摘要长度。 */
    private int userMessageSummaryChars = 200;
    /** agent_runs.answer_summary 摘要长度。 */
    private int answerSummaryChars = 500;
    /** agent_tool_calls.arguments 摘要长度。 */
    private int argumentSummaryChars = 500;
    /** agent_tool_calls.result_summary 摘要长度。 */
    private int resultSummaryChars = 500;
    /** agent_tool_calls.error_message 摘要长度。 */
    private int errorMessageChars = 500;
    /** read_mindmap 精简结构最多包含的节点数。 */
    private int readMindmapMaxNodes = 200;
    /** 单轮工具结果缓存配置（综合升级方案 第一部分 阶段一）。 */
    private ToolCache toolCache = new ToolCache();
    /** P0-E1：估算成本单价（app.agent.cost.*）。 */
    private Cost cost = new Cost();
    /** P1-E3b：提示注入防护（app.agent.security.*）。 */
    private Security security = new Security();

    public int getMaxToolCalls() {
        return maxToolCalls;
    }

    public void setMaxToolCalls(int maxToolCalls) {
        this.maxToolCalls = maxToolCalls;
    }

    public boolean isToolBudgetSoft() {
        return toolBudgetSoft;
    }

    public void setToolBudgetSoft(boolean toolBudgetSoft) {
        this.toolBudgetSoft = toolBudgetSoft;
    }

    public int getToolTimeoutSeconds() {
        return toolTimeoutSeconds;
    }

    public void setToolTimeoutSeconds(int toolTimeoutSeconds) {
        this.toolTimeoutSeconds = toolTimeoutSeconds;
    }

    public int getToolResultMaxChars() {
        return toolResultMaxChars;
    }

    public void setToolResultMaxChars(int toolResultMaxChars) {
        this.toolResultMaxChars = toolResultMaxChars;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    public int getUserMessageSummaryChars() {
        return userMessageSummaryChars;
    }

    public void setUserMessageSummaryChars(int userMessageSummaryChars) {
        this.userMessageSummaryChars = userMessageSummaryChars;
    }

    public int getAnswerSummaryChars() {
        return answerSummaryChars;
    }

    public void setAnswerSummaryChars(int answerSummaryChars) {
        this.answerSummaryChars = answerSummaryChars;
    }

    public int getArgumentSummaryChars() {
        return argumentSummaryChars;
    }

    public void setArgumentSummaryChars(int argumentSummaryChars) {
        this.argumentSummaryChars = argumentSummaryChars;
    }

    public int getResultSummaryChars() {
        return resultSummaryChars;
    }

    public void setResultSummaryChars(int resultSummaryChars) {
        this.resultSummaryChars = resultSummaryChars;
    }

    public int getErrorMessageChars() {
        return errorMessageChars;
    }

    public void setErrorMessageChars(int errorMessageChars) {
        this.errorMessageChars = errorMessageChars;
    }

    public int getReadMindmapMaxNodes() {
        return readMindmapMaxNodes;
    }

    public void setReadMindmapMaxNodes(int readMindmapMaxNodes) {
        this.readMindmapMaxNodes = readMindmapMaxNodes;
    }

    public ToolCache getToolCache() {
        return toolCache;
    }

    public void setToolCache(ToolCache toolCache) {
        this.toolCache = toolCache;
    }

    public Cost getCost() {
        return cost;
    }

    public void setCost(Cost cost) {
        this.cost = cost;
    }

    public Security getSecurity() {
        return security;
    }

    public void setSecurity(Security security) {
        this.security = security;
    }

    /**
     * P1-E3b（方案 §P1-7）：提示注入防护是否生效。
     *
     * <p>为 {@code null}（旧单测直接 {@code new} 后把 security 置空）时按<b>开</b>处理，
     * 避免“忘了配置 = 静默关掉防护”。
     */
    public boolean isEvidenceGuardEnabled() {
        return security == null || security.isEvidenceGuardEnabled();
    }

    /**
     * 提示注入防护（{@code app.agent.security.*}，方案 §P1-7）。
     *
     * <p>{@link #evidenceGuardEnabled} 默认 <b>true</b>：证据包装 {@code <evidence id=… source=…>}、
     * 内容转义、{@code EvidenceSanitizer} 命中标记与安全提示行、输出侧“系统提示词泄露”检查
     * 全部生效。置 {@code false} 是<b>整段回滚开关</b>：工具输出与 P1-E3b 之前逐字节一致
     * （不包装、不转义、不加提示行、不标 suspicious、不做泄露检查），用环境变量
     * {@code AGENT_SECURITY_EVIDENCE_GUARD_ENABLED=false} 一键关闭。
     */
    public static class Security {

        /** 证据防护总开关；默认 true。 */
        private boolean evidenceGuardEnabled = true;

        public boolean isEvidenceGuardEnabled() {
            return evidenceGuardEnabled;
        }

        public void setEvidenceGuardEnabled(boolean evidenceGuardEnabled) {
            this.evidenceGuardEnabled = evidenceGuardEnabled;
        }
    }

    /** 单 run 内工具结果缓存开关；关闭后工具装饰链退回现状。 */
    public static class ToolCache {

        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    /**
     * P0-E1：token 单价（人民币元 / 千 token）。
     *
     * <p><b>按 2026-10 公开价配置，可改；不是账单。</b>
     * 默认值 input 0.002、output 0.008 对应 DeepSeek 公开价（输入约 ¥2/百万 token、
     * 输出约 ¥8/百万 token）；价格随时会变，且不含缓存折扣、阶梯价与汇率，
     * 因此 {@code agent_runs.estimated_cost} 只能用于"哪一轮更贵"的相对比较，
     * 不能当对账依据。真实计费请以平台账单为准。</p>
     */
    public static class Cost {

        /** 输入（prompt）单价：元 / 千 token。 */
        private double inputPer1kTokens = 0.002d;
        /** 输出（completion）单价：元 / 千 token。 */
        private double outputPer1kTokens = 0.008d;

        public double getInputPer1kTokens() {
            return inputPer1kTokens;
        }

        public void setInputPer1kTokens(double inputPer1kTokens) {
            this.inputPer1kTokens = inputPer1kTokens;
        }

        public double getOutputPer1kTokens() {
            return outputPer1kTokens;
        }

        public void setOutputPer1kTokens(double outputPer1kTokens) {
            this.outputPer1kTokens = outputPer1kTokens;
        }
    }
}
