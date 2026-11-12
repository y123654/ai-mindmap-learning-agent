package com.example.aimindmap.agenttracing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** P0-E1：四类指标可被 MeterRegistry 观测（用 SimpleMeterRegistry，无需起 Spring）。 */
class AgentMetricsTest {

    private SimpleMeterRegistry registry;
    private AgentMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new AgentMetrics(registry);
    }

    @Test
    void runDurationIsTaggedByOutcome() {
        metrics.recordRunDuration(Duration.ofMillis(120), true);
        metrics.recordRunDuration(Duration.ofMillis(80), false);
        metrics.recordRunDuration(Duration.ofMillis(40), false);

        Timer success = registry.get(AgentMetrics.RUN_DURATION)
                .tag(AgentMetrics.TAG_OUTCOME, AgentMetrics.OUTCOME_SUCCESS).timer();
        Timer error = registry.get(AgentMetrics.RUN_DURATION)
                .tag(AgentMetrics.TAG_OUTCOME, AgentMetrics.OUTCOME_ERROR).timer();

        assertThat(success.count()).isEqualTo(1);
        assertThat(success.totalTime(java.util.concurrent.TimeUnit.MILLISECONDS))
                .isEqualTo(120d);
        assertThat(error.count()).isEqualTo(2);
        assertThat(error.totalTime(java.util.concurrent.TimeUnit.MILLISECONDS))
                .isEqualTo(120d);
    }

    @Test
    void toolCallsAreCountedPerToolAndPhase() {
        metrics.recordToolCall("search_my_documents", true);
        metrics.recordToolCall("search_my_documents", true);
        metrics.recordToolCall("read_my_document", false);

        Counter searchSuccess = registry.get(AgentMetrics.TOOL_CALLS)
                .tag(AgentMetrics.TAG_TOOL, "search_my_documents")
                .tag(AgentMetrics.TAG_PHASE, AgentMetrics.PHASE_SUCCESS)
                .counter();
        Counter readFailed = registry.get(AgentMetrics.TOOL_CALLS)
                .tag(AgentMetrics.TAG_TOOL, "read_my_document")
                .tag(AgentMetrics.TAG_PHASE, AgentMetrics.PHASE_FAILED)
                .counter();

        assertThat(searchSuccess.count()).isEqualTo(2d);
        assertThat(readFailed.count()).isEqualTo(1d);
        assertThat(registry.get(AgentMetrics.TOOL_CALLS).meters()).hasSize(2);
    }

    @Test
    void blankToolNameFallsBackToUnknownTag() {
        metrics.recordToolCall("  ", true);

        assertThat(registry.get(AgentMetrics.TOOL_CALLS)
                .tag(AgentMetrics.TAG_TOOL, AgentMetrics.UNKNOWN_TOOL)
                .counter().count()).isEqualTo(1d);
    }

    @Test
    void citationCoverageIsRecordedAsPerRunSamplesAndClamped() {
        metrics.recordCitationCoverage(1.0d);
        metrics.recordCitationCoverage(0.5d);
        metrics.recordCitationCoverage(2.0d);
        metrics.recordCitationCoverage(-1.0d);

        DistributionSummary summary = registry.get(AgentMetrics.CITATION_COVERAGE).summary();
        assertThat(summary.count()).isEqualTo(4);
        assertThat(summary.totalAmount()).isEqualTo(1.0d + 0.5d + 1.0d + 0d);
    }

    @Test
    void groundedCounterSplitsByTag() {
        metrics.recordGrounded(true);
        metrics.recordGrounded(false);
        metrics.recordGrounded(false);

        assertThat(registry.get(AgentMetrics.GROUNDED)
                .tag(AgentMetrics.TAG_GROUNDED, "true").counter().count()).isEqualTo(1d);
        assertThat(registry.get(AgentMetrics.GROUNDED)
                .tag(AgentMetrics.TAG_GROUNDED, "false").counter().count()).isEqualTo(2d);
    }

    @Test
    void nullDurationIsIgnored() {
        metrics.recordRunDuration(null, true);

        assertThat(registry.find(AgentMetrics.RUN_DURATION).timer()).isNull();
    }

    @Test
    void noopImplementationNeverThrowsAndRegistersNothing() {
        AgentMetrics noop = AgentMetrics.noop();

        assertThat(noop.enabled()).isFalse();
        assertThat(metrics.enabled()).isTrue();
        assertThatCode(() -> {
            noop.recordRunDuration(Duration.ofMillis(5), true);
            noop.recordToolCall("search_my_documents", true);
            noop.recordCitationCoverage(1.0d);
            noop.recordGrounded(true);
        }).doesNotThrowAnyException();
    }
}
