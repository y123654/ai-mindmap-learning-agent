package com.example.aimindmap.agentruntime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * P1-D2b：run 级预算对象的纯时间语义测试（不涉及 Spring、不涉及网络）。
 *
 * <p>这里只断言“时间在走”这一层可稳定观察的性质：剩余单调不增、耗尽后不为负、
 * 过期判定在截止后为 true；不假设机器速度，因此容差都取“≥ / ≤”而非精确值。
 */
class AgentRunBudgetTest {

    @Test
    void startingNowKeepsAlmostFullTimeout() {
        AgentRunBudget budget = AgentRunBudget.startingNow(Duration.ofSeconds(5));

        assertThat(budget.timeoutNanos()).isEqualTo(Duration.ofSeconds(5).toNanos());
        assertThat(budget.remainingMillis()).isGreaterThan(0L).isLessThanOrEqualTo(5_000L);
        assertThat(budget.remainingNanos()).isLessThanOrEqualTo(budget.timeoutNanos());
        assertThat(budget.expired()).isFalse();
    }

    @Test
    void remainingDecreasesMonotonicallyAndStaysNonNegative() throws Exception {
        AgentRunBudget budget = new AgentRunBudget(System.nanoTime(), Duration.ofSeconds(5));

        long first = budget.remainingMillis();
        Thread.sleep(30L);
        long second = budget.remainingMillis();

        assertThat(first).isGreaterThan(0L).isLessThanOrEqualTo(5_000L);
        assertThat(second).isLessThan(first);
        assertThat(second).isGreaterThanOrEqualTo(0L);
        // 第 3 次读也不能变大（预算不回收）
        assertThat(budget.remainingMillis()).isLessThanOrEqualTo(second);
    }

    @Test
    void expiredTurnsTrueAfterTimeoutAndRemainingIsClampedToZero() throws Exception {
        AgentRunBudget budget = new AgentRunBudget(System.nanoTime(), Duration.ofMillis(120L));
        assertThat(budget.expired()).isFalse();

        Thread.sleep(300L);

        assertThat(budget.expired()).isTrue();
        assertThat(budget.remainingMillis()).isZero();
        assertThat(budget.remainingNanos()).isZero();
        assertThat(budget.remainingDuration()).isEqualTo(Duration.ZERO);
    }

    @Test
    void budgetStartedInThePastIsAlreadyExpiredButNeverNegative() {
        long startedTenSecondsAgo = System.nanoTime() - Duration.ofSeconds(10).toNanos();

        AgentRunBudget budget = new AgentRunBudget(startedTenSecondsAgo, Duration.ofSeconds(1));

        assertThat(budget.expired()).isTrue();
        assertThat(budget.remainingMillis()).isZero();
        assertThat(budget.remainingDuration().isNegative()).isFalse();
    }

    @Test
    void zeroTimeoutIsExhaustedImmediately() {
        AgentRunBudget budget = AgentRunBudget.startingNow(Duration.ZERO);

        assertThat(budget.expired()).isTrue();
        assertThat(budget.remainingMillis()).isZero();
        assertThat(budget.remainingDuration()).isEqualTo(Duration.ZERO);
    }

    @Test
    void negativeOrNullTimeoutIsRejected() {
        assertThatThrownBy(() -> new AgentRunBudget(System.nanoTime(), Duration.ofMillis(-1L)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不能为负");
        assertThatThrownBy(() -> new AgentRunBudget(System.nanoTime(), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不能为 null");
    }
}
