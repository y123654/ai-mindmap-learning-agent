package com.example.aimindmap.agenttracing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.aimindmap.auth.AuthUser;
import com.example.aimindmap.auth.ExternalIdCodec;
import com.example.aimindmap.common.error.GlobalExceptionHandler;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.MethodParameter;
import org.springframework.data.domain.Pageable;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * P0-E1：{@code GET /api/agent/runs/{runId}} 与 {@code GET /api/agent/runs?sessionId=&limit=}。
 *
 * <p>用真实 {@link AgentRunQueryService} + mock 仓储，覆盖路由、归属校验、limit 归一与明细返回。</p>
 */
class AgentRunControllerTest {

    /** 当前登录用户（AuthUser 由下面的 resolver 固定注入）。 */
    private static final long CURRENT_USER_ID = 8L;
    private static final long OTHER_USER_ID = 9L;
    private static final long SESSION_ID = 90L;

    private final ExternalIdCodec idCodec = new ExternalIdCodec("agent-run-controller-test-salt");

    private AgentRunRepository runRepository;
    private AgentToolCallRepository toolCallRepository;
    private AgentEvidenceRepository evidenceRepository;
    private AgentCitationRepository citationRepository;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        runRepository = mock(AgentRunRepository.class);
        toolCallRepository = mock(AgentToolCallRepository.class);
        evidenceRepository = mock(AgentEvidenceRepository.class);
        citationRepository = mock(AgentCitationRepository.class);
        AgentRunQueryService service = new AgentRunQueryService(
                runRepository, toolCallRepository, evidenceRepository, citationRepository, idCodec);
        mockMvc = MockMvcBuilders.standaloneSetup(new AgentRunController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new FixedAuthUserResolver())
                .build();
    }

    @Test
    void ownerGetsRunWithEvidenceCitationsAndToolCalls() throws Exception {
        AgentRunEntity run = run(5L, CURRENT_USER_ID, SESSION_ID, AgentRunStatus.SUCCEEDED);
        when(runRepository.findById(5L)).thenReturn(Optional.of(run));
        when(evidenceRepository.findByRunIdOrderByEvidenceKeyAsc(5L))
                .thenReturn(List.of(evidence(5L, "E1", "document_chunk", "888901")));
        when(citationRepository.findByRunIdOrderByEvidenceKeyAsc(5L))
                .thenReturn(List.of(citation(5L, "E1", true), citation(5L, "E9", false)));
        when(toolCallRepository.findByRunIdOrderByIdAsc(5L))
                .thenReturn(List.of(toolCall(5L, "search_my_documents", AgentToolCallStatus.SUCCEEDED)));

        mockMvc.perform(get("/api/agent/runs/{runId}", idCodec.encode(5L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.run.runId").value(idCodec.encode(5L)))
                .andExpect(jsonPath("$.run.sessionId").value(idCodec.encode(SESSION_ID)))
                .andExpect(jsonPath("$.run.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.run.completionTokens").value(45))
                .andExpect(jsonPath("$.run.estimatedCost").exists())
                // Jackson 按 BigDecimal.toString 输出，保留 6 位小数（NUMERIC(12,6) 口径）
                .andExpect(content().string(containsString("\"estimatedCost\":0.000606")))
                .andExpect(jsonPath("$.run.citationCount").value(1))
                .andExpect(jsonPath("$.run.grounded").value(true))
                .andExpect(jsonPath("$.run.durationMs").value(1200))
                // P0-E1 没有任何写 agent_steps 的代码，恒为空数组（P1 才有数据）
                .andExpect(jsonPath("$.steps").isArray())
                .andExpect(jsonPath("$.steps").isEmpty())
                .andExpect(jsonPath("$.evidence[0].evidenceKey").value("E1"))
                .andExpect(jsonPath("$.evidence[0].resourceType").value("document_chunk"))
                .andExpect(jsonPath("$.evidence[0].resourceId").value("888901"))
                .andExpect(jsonPath("$.citations[0].valid").value(true))
                .andExpect(jsonPath("$.citations[1].valid").value(false))
                .andExpect(jsonPath("$.toolCalls[0].toolName").value("search_my_documents"))
                .andExpect(jsonPath("$.toolCalls[0].status").value("SUCCEEDED"));
    }

    @Test
    void otherUsersRunIsRejectedWith404AndNoDetails() throws Exception {
        AgentRunEntity run = run(6L, OTHER_USER_ID, SESSION_ID, AgentRunStatus.SUCCEEDED);
        when(runRepository.findById(6L)).thenReturn(Optional.of(run));

        mockMvc.perform(get("/api/agent/runs/{runId}", idCodec.encode(6L)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        // 归属校验在查明细之前就拦掉，绝不读别人的证据/工具明细
        verify(evidenceRepository, never()).findByRunIdOrderByEvidenceKeyAsc(any());
        verify(citationRepository, never()).findByRunIdOrderByEvidenceKeyAsc(any());
        verify(toolCallRepository, never()).findByRunIdOrderByIdAsc(any());
    }

    @Test
    void unknownRunIdReturns404() throws Exception {
        when(runRepository.findById(404L)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/agent/runs/{runId}", idCodec.encode(404L)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void forgedRunIdReturns404() throws Exception {
        mockMvc.perform(get("/api/agent/runs/{runId}", "not-an-encoded-id"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        verify(runRepository, never()).findById(any());
    }

    @Test
    void listReturnsOnlyCurrentUsersRunsAndUsesDefaultLimit() throws Exception {
        when(runRepository.findByUserIdAndSessionIdOrderByStartedAtDescIdDesc(
                eq(CURRENT_USER_ID), eq(SESSION_ID), any(Pageable.class)))
                .thenReturn(List.of(
                        run(7L, CURRENT_USER_ID, SESSION_ID, AgentRunStatus.SUCCEEDED),
                        run(6L, CURRENT_USER_ID, SESSION_ID, AgentRunStatus.FAILED)));

        mockMvc.perform(get("/api/agent/runs").param("sessionId", idCodec.encode(SESSION_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].runId").value(idCodec.encode(7L)))
                .andExpect(jsonPath("$[1].status").value("FAILED"));

        // 查询条件自带 userId：别人的 session 只可能返回空数组，不会查出他人 run
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(runRepository).findByUserIdAndSessionIdOrderByStartedAtDescIdDesc(
                eq(CURRENT_USER_ID), eq(SESSION_ID), pageableCaptor.capture());
        assertThat(pageableCaptor.getValue().getPageSize())
                .isEqualTo(AgentRunQueryService.DEFAULT_LIMIT);
    }

    @Test
    void listClampsLimitToUpperBound() throws Exception {
        when(runRepository.findByUserIdAndSessionIdOrderByStartedAtDescIdDesc(
                eq(CURRENT_USER_ID), eq(SESSION_ID), any(Pageable.class)))
                .thenReturn(List.of());

        mockMvc.perform(get("/api/agent/runs")
                        .param("sessionId", idCodec.encode(SESSION_ID))
                        .param("limit", "999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(runRepository).findByUserIdAndSessionIdOrderByStartedAtDescIdDesc(
                eq(CURRENT_USER_ID), eq(SESSION_ID), pageableCaptor.capture());
        assertThat(pageableCaptor.getValue().getPageSize())
                .isEqualTo(AgentRunQueryService.MAX_LIMIT);
    }

    @Test
    void nonPositiveLimitFallsBackToDefault() throws Exception {
        when(runRepository.findByUserIdAndSessionIdOrderByStartedAtDescIdDesc(
                eq(CURRENT_USER_ID), eq(SESSION_ID), any(Pageable.class)))
                .thenReturn(List.of());

        mockMvc.perform(get("/api/agent/runs")
                        .param("sessionId", idCodec.encode(SESSION_ID))
                        .param("limit", "0"))
                .andExpect(status().isOk());

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(runRepository).findByUserIdAndSessionIdOrderByStartedAtDescIdDesc(
                eq(CURRENT_USER_ID), eq(SESSION_ID), pageableCaptor.capture());
        assertThat(pageableCaptor.getValue().getPageSize())
                .isEqualTo(AgentRunQueryService.DEFAULT_LIMIT);
    }

    @Test
    void listWithoutSessionIdReturns400() throws Exception {
        mockMvc.perform(get("/api/agent/runs"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("AGENT_SESSION_REQUIRED"));

        verify(runRepository, never())
                .findByUserIdAndSessionIdOrderByStartedAtDescIdDesc(any(), any(), any());
    }

    @Test
    void listWithForgedSessionIdReturns404() throws Exception {
        mockMvc.perform(get("/api/agent/runs").param("sessionId", "forged-session"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    private AgentRunEntity run(Long id, Long userId, Long sessionId, AgentRunStatus status) {
        AgentRunEntity run = new AgentRunEntity();
        setField(run, AgentRunEntity.class, "id", id);
        run.setUserId(userId);
        run.setSessionId(sessionId);
        run.setStatus(status);
        run.setModel("deepseek-chat");
        run.setUserMessage("时间片轮转是什么");
        run.setAnswerSummary("时间片轮转是一种调度算法");
        run.setToolCallCount(1);
        run.setCacheHits(0);
        run.setPromptChars(120);
        run.setPromptTokens(123);
        run.setCompletionTokens(45);
        run.setEstimatedCost(new BigDecimal("0.000606"));
        run.setCitationCount(1);
        run.setGrounded(true);
        run.setStartedAt(Instant.parse("2026-10-26T13:00:00Z"));
        run.setCompletedAt(Instant.parse("2026-10-26T13:00:01.200Z"));
        return run;
    }

    private static AgentEvidenceEntity evidence(Long runId, String key, String type, String resourceId) {
        AgentEvidenceEntity entity = new AgentEvidenceEntity();
        entity.setRunId(runId);
        entity.setEvidenceKey(key);
        entity.setResourceType(type);
        entity.setResourceId(resourceId);
        entity.setChunkIndex(0);
        entity.setScore(0.71f);
        entity.setTitle("操作系统第三章");
        entity.setSnippet("进程是资源分配的基本单位");
        entity.setSuspicious(false);
        entity.setCreatedAt(Instant.parse("2026-10-26T13:00:01Z"));
        return entity;
    }

    private static AgentCitationEntity citation(Long runId, String key, boolean valid) {
        AgentCitationEntity entity = new AgentCitationEntity();
        entity.setRunId(runId);
        entity.setEvidenceKey(key);
        entity.setValid(valid);
        entity.setCreatedAt(Instant.parse("2026-10-26T13:00:01Z"));
        return entity;
    }

    private static AgentToolCallEntity toolCall(Long runId, String toolName, AgentToolCallStatus status) {
        AgentToolCallEntity entity = new AgentToolCallEntity();
        entity.setRunId(runId);
        entity.setUserId(CURRENT_USER_ID);
        entity.setToolName(toolName);
        entity.setArguments("{\"query\":\"时间片轮转\"}");
        entity.setResultSummary("命中 3 条");
        entity.setStatus(status);
        entity.setDurationMs(820L);
        entity.setCreatedAt(Instant.parse("2026-10-26T13:00:00.400Z"));
        return entity;
    }

    private static void setField(Object target, Class<?> type, String name, Object value) {
        try {
            var field = type.getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static final class FixedAuthUserResolver implements HandlerMethodArgumentResolver {

        @Override
        public boolean supportsParameter(MethodParameter parameter) {
            return parameter.getParameterType().equals(AuthUser.class);
        }

        @Override
        public Object resolveArgument(MethodParameter parameter,
                                      ModelAndViewContainer mavContainer,
                                      NativeWebRequest webRequest,
                                      WebDataBinderFactory binderFactory) {
            return new AuthUser(CURRENT_USER_ID, "alice");
        }
    }
}
