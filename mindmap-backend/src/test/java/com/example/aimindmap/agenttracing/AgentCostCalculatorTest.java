package com.example.aimindmap.agenttracing;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** P0-E1：估算成本口径（纯函数，不依赖 Spring）。 */
class AgentCostCalculatorTest {

    private final AgentTracingProperties.Cost defaults = new AgentTracingProperties.Cost();

    @Test
    void defaultPriceMatchesFormulaAndKeepsSixDecimals() {
        // 123/1000*0.002 + 45/1000*0.008 = 0.000246 + 0.00036 = 0.000606
        BigDecimal cost = AgentCostCalculator.estimate(123, 45, defaults);

        assertThat(cost).isEqualByComparingTo("0.000606");
        assertThat(cost.scale()).isEqualTo(6);
    }

    @Test
    void oneThousandTokensEachCostsInputPlusOutputRate() {
        BigDecimal cost = AgentCostCalculator.estimate(1000, 1000, defaults);

        assertThat(cost).isEqualByComparingTo("0.010000");
    }

    @Test
    void missingCompletionTokensYieldsNullCost() {
        assertThat(AgentCostCalculator.estimate(123, null, defaults)).isNull();
        assertThat(AgentCostCalculator.estimate(123, 0, defaults)).isNull();
    }

    @Test
    void missingPromptTokensYieldsNullCost() {
        assertThat(AgentCostCalculator.estimate(null, 45, defaults)).isNull();
        assertThat(AgentCostCalculator.estimate(0, 45, defaults)).isNull();
    }

    @Test
    void customPriceIsRespected() {
        AgentTracingProperties.Cost custom = new AgentTracingProperties.Cost();
        custom.setInputPer1kTokens(0.01d);
        custom.setOutputPer1kTokens(0.02d);

        // 2000/1000*0.01 + 500/1000*0.02 = 0.02 + 0.01 = 0.03
        assertThat(AgentCostCalculator.estimate(2000, 500, custom)).isEqualByComparingTo("0.030000");
    }

    @Test
    void ratesAreRoundedHalfUpToSixDecimals() {
        // 1/1000*0.002 + 1/1000*0.008 = 0.000002 + 0.000008 = 0.000010
        assertThat(AgentCostCalculator.estimate(1, 1, defaults)).isEqualByComparingTo("0.000010");
    }
}
