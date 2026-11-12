package com.example.aimindmap.agenttracing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.aimindmap.auth.ExternalIdCodec;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * P1-B2：{@code GET /api/agent/runs/{runId}} 的 {@code steps} 改为从 {@code agent_steps} 读取。
 *
 * <p>覆盖两点：① 仓储返回的步骤按 {@code step_index} 升序映射到 DTO；
 * ② 没有步骤时返回空数组（不 NPE）。归属校验与列表接口的既有用例在
 * {@link AgentRunControllerTest} 中，未改动。</p>
 */
class AgentRunQueryServiceTest {

    private static final long OWNER_USER_ID = 8L;
    private static final long RUN_ID = 5L;

    private final ExternalIdCodec idCodec = new ExternalIdCodec("agent-run-query-test-salt");

    private AgentRunRepository runRepository;
    private AgentStepRepository stepRepository;
    private AgentRunQueryService service;

    @BeforeEach
    void setUp() {
        runRepository = mock(AgentRunRepository.class);
        AgentToolCallRepository toolCallRepository = mock(AgentToolCallRepository.class);
        AgentEvidenceRepository evidenceRepository = mock(AgentEvidenceRepository.class);
        AgentCitationRepository citationRepository = mock(AgentCitationRepository.class);
        stepRepository = mock(AgentStepRepository.class);

        when(runRepository.findById(RUN_ID)).thenReturn(Optional.of(run()));
        when(evidenceRepository.findByRunIdOrderByEvidenceKeyAsc(RUN_ID)).thenReturn(List.of());
        when(citationRepository.findByRunIdOrderByEvidenceKeyAsc(RUN_ID)).thenReturn(List.of());
        when(toolCallRepository.findByRunIdOrderByIdAsc(RUN_ID)).thenReturn(List.of());

        service = new AgentRunQueryService(runRepository, toolCallRepository, evidenceRepository,
                citationRepository, stepRepository, idCodec);
    }

    @Test
    void stepsAreReadFromRepositoryAndSortedByStepIndex() {
        // 故意乱序返回，验证服务层仍按 step_index 升序
        when(stepRepository.findByRunIdOrderByStepIndexAsc(RUN_ID)).thenReturn(List.of(
                step(2, "RETRIEVE", "SUCCEED", "命中 1 条证据：E1"),
                step(1, "RETRIEVE_FALLBACK", "FAILED", "未命中证据：0 条")));

        AgentRunDtos.RunDetailResponse detail = service.getRun(OWNER_USER_ID,
                idCodec.encode(RUN_ID));

        verify(stepRepository).findByRunIdOrderByStepIndexAsc(RUN_ID);
        assertThat(detail.steps()).hasSize(2);
        assertThat(detail.steps()).extracting(AgentRunDtos.RunStepResponse::stepIndex)
                .containsExactly(1, 2);
        assertThat(detail.steps().get(0).stepType()).isEqualTo("RETRIEVE_FALLBACK");
        assertThat(detail.steps().get(0).status()).isEqualTo("FAILED");
        assertThat(detail.steps().get(0).outputSummary()).isEqualTo("未命中证据：0 条");
        assertThat(detail.steps().get(0).durationMs()).isEqualTo(11L);
        assertThat(detail.steps().get(1).stepType()).isEqualTo("RETRIEVE");
    }

    @Test
    void noStepsReturnsEmptyArrayWithoutNpe() {
        when(stepRepository.findByRunIdOrderByStepIndexAsc(RUN_ID)).thenReturn(List.of());

        AgentRunDtos.RunDetailResponse detail = service.getRun(OWNER_USER_ID,
                idCodec.encode(RUN_ID));

        assertThat(detail.steps()).isNotNull().isEmpty();
    }

    @Test
    void legacyConstructionWithoutStepRepositoryStillReturnsEmptySteps() {
        AgentRunQueryService legacy = new AgentRunQueryService(runRepository,
                mock(AgentToolCallRepository.class), mock(AgentEvidenceRepository.class),
                mock(AgentCitationRepository.class), idCodec);
        when(runRepository.findById(RUN_ID)).thenReturn(Optional.of(run()));

        AgentRunDtos.RunDetailResponse detail = legacy.getRun(OWNER_USER_ID,
                idCodec.encode(RUN_ID));

        assertThat(detail.steps()).isNotNull().isEmpty();
    }

    private static AgentRunEntity run() {
        AgentRunEntity run = new AgentRunEntity();
        setField(run, "id", RUN_ID);
        run.setUserId(OWNER_USER_ID);
        run.setSessionId(90L);
        run.setStatus(AgentRunStatus.SUCCEEDED);
        run.setModel("deepseek-chat");
        run.setUserMessage("时间片轮转是什么");
        run.setToolCallCount(0);
        run.setCacheHits(0);
        run.setStartedAt(Instant.parse("2026-10-27T03:00:00Z"));
        run.setCompletedAt(Instant.parse("2026-10-27T03:00:02Z"));
        return run;
    }

    private static AgentStepEntity step(int stepIndex, String stepType, String status,
                                        String outputSummary) {
        AgentStepEntity step = new AgentStepEntity();
        setField(step, "runId", RUN_ID);
        step.setStepIndex(stepIndex);
        step.setStepType(stepType);
        step.setName("步骤 " + stepIndex);
        step.setStatus(status);
        step.setInputSummary("你好");
        step.setOutputSummary(outputSummary);
        step.setDurationMs(11L);
        step.setCreatedAt(Instant.parse("2026-10-27T03:00:01Z"));
        return step;
    }

    private static void setField(Object target, String name, Object value) {
        try {
            var field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
