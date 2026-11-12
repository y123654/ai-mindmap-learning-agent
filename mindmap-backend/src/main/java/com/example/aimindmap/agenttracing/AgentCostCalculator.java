package com.example.aimindmap.agenttracing;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * P0-E1：按配置单价把 token 折算成估算成本（纯函数，无状态，便于单测）。
 *
 * <p><b>这不是账单</b>：单价来自 {@code app.agent.cost.*}（默认按 2026-10 公开价），
 * 不含缓存折扣、阶梯价与汇率波动；只用于纵向比较"哪一轮更贵"。</p>
 *
 * <p>口径：{@code cost = prompt/1000*input + completion/1000*output}，保留 6 位小数
 * （与 {@code agent_runs.estimated_cost NUMERIC(12,6)} 一致）。
 * prompt 与 completion 任一缺失（null 或 ≤0）都返回 null，不用估算值凑成本，
 * 避免把"没有真实用量"的 run 记成"花了 0 元"。</p>
 */
public final class AgentCostCalculator {

    /** 计价单位：千 token。 */
    private static final BigDecimal TOKENS_PER_UNIT = BigDecimal.valueOf(1000);
    /** 与 agent_runs.estimated_cost 的小数位一致。 */
    private static final int SCALE = 6;
    /** 中间计算保留位数，避免先舍入再相加产生偏差。 */
    private static final int INTERMEDIATE_SCALE = 12;

    private AgentCostCalculator() {
    }

    /**
     * @param promptTokens     真实输入 token（null/非正数视为缺失）
     * @param completionTokens 真实输出 token（null/非正数视为缺失）
     * @param cost             单价配置
     * @return 估算成本（元，6 位小数）；任一侧 token 缺失返回 null
     */
    public static BigDecimal estimate(Integer promptTokens, Integer completionTokens,
                                      AgentTracingProperties.Cost cost) {
        if (promptTokens == null || completionTokens == null
                || promptTokens <= 0 || completionTokens <= 0) {
            return null;
        }
        double inputPer1k = cost == null ? 0d : cost.getInputPer1kTokens();
        double outputPer1k = cost == null ? 0d : cost.getOutputPer1kTokens();
        BigDecimal inputCost = BigDecimal.valueOf(promptTokens)
                .multiply(BigDecimal.valueOf(inputPer1k))
                .divide(TOKENS_PER_UNIT, INTERMEDIATE_SCALE, RoundingMode.HALF_UP);
        BigDecimal outputCost = BigDecimal.valueOf(completionTokens)
                .multiply(BigDecimal.valueOf(outputPer1k))
                .divide(TOKENS_PER_UNIT, INTERMEDIATE_SCALE, RoundingMode.HALF_UP);
        return inputCost.add(outputCost).setScale(SCALE, RoundingMode.HALF_UP);
    }
}
