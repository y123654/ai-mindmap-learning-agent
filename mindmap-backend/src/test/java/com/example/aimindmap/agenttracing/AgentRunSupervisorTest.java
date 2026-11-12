package com.example.aimindmap.agenttracing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class AgentRunSupervisorTest {

    private final AgentRunSupervisor supervisor = new AgentRunSupervisor();

    @Test
    void returnsTaskResult() {
        String result = supervisor.executeWithTimeout(Duration.ofSeconds(2), () -> "done");

        assertThat(result).isEqualTo("done");
    }

    @Test
    void abortsWithStableCodeOnTimeout() {
        assertThatThrownBy(() -> supervisor.executeWithTimeout(Duration.ofMillis(200), () -> {
                    Thread.sleep(2000);
                    return null;
                }))
                .isInstanceOf(AgentRunAbortException.class)
                .satisfies(ex -> assertThat(((AgentRunAbortException) ex).code())
                        .isEqualTo("AGENT_RUN_TIMEOUT"))
                .hasMessageContaining("超时");
    }

    @Test
    void propagatesRuntimeExceptionFromTask() {
        IllegalStateException failure = new IllegalStateException("boom");

        assertThatThrownBy(() -> supervisor.executeWithTimeout(Duration.ofSeconds(2), () -> {
                    throw failure;
                }))
                .isSameAs(failure);
    }
}
