package com.example.aimindmap.agenttracing;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * P1-D1（方案 §P1-4）：软降级名额获取。
 *
 * <p>与 {@link AgentToolCallGuardTest} 分开写，是为了保证既有测试文件一行不动：
 * {@code tryAcquire()} 的硬中止语义（超限那次仍自增）必须原样保留。</p>
 */
class AgentToolCallGuardSoftTest {

    @Test
    void softAcquireAllowsExactlyUpToLimitAndRejectedCallsDoNotCount() {
        AgentToolCallGuard guard = new AgentToolCallGuard(3);

        assertThat(guard.tryAcquireSoft()).isTrue();
        assertThat(guard.tryAcquireSoft()).isTrue();
        assertThat(guard.tryAcquireSoft()).isTrue();
        assertThat(guard.tryAcquireSoft()).isFalse();
        // 关键断言：软降级被拒绝时不推高计数
        assertThat(guard.acquiredCount()).isEqualTo(3);
        assertThat(guard.tryAcquireSoft()).isFalse();
        assertThat(guard.acquiredCount()).isEqualTo(3);
    }

    @Test
    void softAcquireDoesNotLeakAcrossInstances() {
        AgentToolCallGuard first = new AgentToolCallGuard(1);
        first.tryAcquireSoft();

        AgentToolCallGuard second = new AgentToolCallGuard(1);

        assertThat(second.acquiredCount()).isZero();
        assertThat(second.tryAcquireSoft()).isTrue();
    }

    @Test
    void softAndHardAcquireShareOneCounter() {
        AgentToolCallGuard guard = new AgentToolCallGuard(3);

        assertThat(guard.tryAcquire()).isTrue();
        assertThat(guard.tryAcquireSoft()).isTrue();
        assertThat(guard.tryAcquireSoft()).isTrue();
        // 第 4 次无论走哪个入口都不给名额；软入口不加计数，计数停在 3
        assertThat(guard.tryAcquireSoft()).isFalse();
        assertThat(guard.acquiredCount()).isEqualTo(3);
    }

    @Test
    void softAcquireRejectsWhenLimitIsZero() {
        AgentToolCallGuard guard = new AgentToolCallGuard(0);

        assertThat(guard.tryAcquireSoft()).isFalse();
        assertThat(guard.acquiredCount()).isZero();
    }
}
