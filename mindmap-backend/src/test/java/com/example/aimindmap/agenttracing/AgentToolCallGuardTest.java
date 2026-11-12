package com.example.aimindmap.agenttracing;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AgentToolCallGuardTest {

    @Test
    void allowsExactlyUpToLimitThenRejects() {
        AgentToolCallGuard guard = new AgentToolCallGuard(3);

        assertThat(guard.tryAcquire()).isTrue();
        assertThat(guard.tryAcquire()).isTrue();
        assertThat(guard.tryAcquire()).isTrue();
        assertThat(guard.tryAcquire()).isFalse();
        assertThat(guard.acquiredCount()).isEqualTo(4);
    }

    @Test
    void counterDoesNotLeakAcrossInstances() {
        AgentToolCallGuard first = new AgentToolCallGuard(3);
        first.tryAcquire();

        AgentToolCallGuard second = new AgentToolCallGuard(3);

        assertThat(second.acquiredCount()).isZero();
        assertThat(second.tryAcquire()).isTrue();
    }
}
