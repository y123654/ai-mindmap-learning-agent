package com.example.aimindmap.agenttracing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * P0-E1：{@link AgentTracingService} 的真实 token/成本落库口径 + 工具调用指标埋点。
 *
 * <p>用 mock 仓储 + 真实 {@link AgentMetrics}（SimpleMeterRegistry），不依赖 Docker。</p>
 */
class AgentTracingServiceObservabilityTest {

    private AgentRunRepository runRepository;
    private AgentToolCallRepository toolCallRepository;
    private AgentTracingProperties properties;
    private SimpleMeterRegistry registry;
    private AgentTracingService service;

    private final AtomicLong runIds = new AtomicLong();
    private final AtomicLong toolCallIds = new AtomicLong();
    private final Map<Long, AgentRunEntity> runIndex = new HashMap<>();
    private final Map<Long, AgentToolCallEntity> toolCallIndex = new HashMap<>();

    @BeforeEach
    void setUp() {
        runRepository = Mockito.mock(AgentRunRepository.class);
        toolCallRepository = Mockito.mock(AgentToolCallRepository.class);
        when(runRepository.save(any(AgentRunEntity.class))).thenAnswer(invocation -> {
            AgentRunEntity entity = invocation.getArgument(0);
            setId(entity, runIds.incrementAndGet());
            return entity;
        });
        when(toolCallRepository.save(any(AgentToolCallEntity.class))).thenAnswer(invocation -> {
            AgentToolCallEntity entity = invocation.getArgument(0);
            setToolCallId(entity, toolCallIds.incrementAndGet());
            return entity;
        });
        when(runRepository.findById(any())).thenAnswer(invocation ->
                Optional.ofNullable(runIndex.get(invocation.getArgument(0))));
        when(toolCallRepository.findById(any())).thenAnswer(invocation ->
                Optional.ofNullable(toolCallIndex.get(invocation.getArgument(0))));

        properties = new AgentTracingProperties();
        registry = new SimpleMeterRegistry();
        service = new AgentTracingService(runRepository, toolCallRepository,
                Mockito.mock(AgentEvidenceRepository.class), Mockito.mock(AgentCitationRepository.class),
                properties, new AgentMetrics(registry));
    }

    // ===== 真实 token 与成本 =====

    @Test
    void realTokensOverwriteEstimateAndCostIsComputed() {
        AgentRunEntity run = service.startRun(5L, 90L, "deepseek-chat", "问题", 100, 567);

        service.recordTokenUsage(run.getId(), 123, 45);

        assertThat(run.getPromptTokens()).isEqualTo(123);
        assertThat(run.getCompletionTokens()).isEqualTo(45);
        assertThat(run.getEstimatedCost()).isEqualByComparingTo(new BigDecimal("0.000606"));
        assertThat(run.getEstimatedCost().scale()).isEqualTo(6);
    }

    @Test
    void missingCompletionTokensKeepsCompletionNullAndCostNull() {
        AgentRunEntity run = service.startRun(5L, 90L, "deepseek-chat", "问题", 100, 567);

        service.recordTokenUsage(run.getId(), 123, null);

        assertThat(run.getPromptTokens()).isEqualTo(123);
        assertThat(run.getCompletionTokens()).isNull();
        assertThat(run.getEstimatedCost()).isNull();
    }

    @Test
    void missingRealPromptKeepsEstimateAndLeavesCostNull() {
        AgentRunEntity run = service.startRun(5L, 90L, "deepseek-chat", "问题", 100, 567);

        service.recordTokenUsage(run.getId(), null, 45);

        // 估算值不被空值覆盖；completion 有真实值但 prompt 缺失 → 成本仍为 null（不拿估算凑）
        assertThat(run.getPromptTokens()).isEqualTo(567);
        assertThat(run.getCompletionTokens()).isEqualTo(45);
        assertThat(run.getEstimatedCost()).isNull();
    }

    @Test
    void zeroOrNegativeRealTokensAreTreatedAsMissing() {
        AgentRunEntity run = service.startRun(5L, 90L, "deepseek-chat", "问题", 100, 567);

        service.recordTokenUsage(run.getId(), 0, -1);

        assertThat(run.getPromptTokens()).isEqualTo(567);
        assertThat(run.getCompletionTokens()).isNull();
        assertThat(run.getEstimatedCost()).isNull();
    }

    @Test
    void customPriceIsUsedForCost() {
        properties.getCost().setInputPer1kTokens(0.1d);
        properties.getCost().setOutputPer1kTokens(0.2d);
        AgentRunEntity run = service.startRun(5L, 90L, "deepseek-chat", "问题", 100, 567);

        service.recordTokenUsage(run.getId(), 1000, 500);

        // 1000/1000*0.1 + 500/1000*0.2 = 0.1 + 0.1 = 0.2
        assertThat(run.getEstimatedCost()).isEqualByComparingTo(new BigDecimal("0.200000"));
    }

    // ===== agent.tool.calls =====

    @Test
    void succeededToolCallCountsOnceAsSuccess() {
        AgentRunEntity run = service.startRun(5L, 90L, "deepseek-chat", "问题", 100, null);
        Long toolCallId = service.startToolCall(run.getId(), 5L, "search_my_documents", "{}");

        service.succeedToolCall(toolCallId, "片段内容", 12L);

        assertThat(toolCalls("search_my_documents", AgentMetrics.PHASE_SUCCESS)).isEqualTo(1d);
        assertThat(toolCalls("search_my_documents", AgentMetrics.PHASE_FAILED)).isZero();
    }

    @Test
    void failedToolCallCountsAsFailedWithoutTouchingSuccess() {
        AgentRunEntity run = service.startRun(5L, 90L, "deepseek-chat", "问题", 100, null);
        Long toolCallId = service.startToolCall(run.getId(), 5L, "read_my_document", "{}");

        service.failToolCall(toolCallId, "拒绝访问", 3L);

        assertThat(toolCalls("read_my_document", AgentMetrics.PHASE_FAILED)).isEqualTo(1d);
        assertThat(toolCalls("read_my_document", AgentMetrics.PHASE_SUCCESS)).isZero();
    }

    @Test
    void cacheHitToolCallCountsAsSuccess() {
        AgentRunEntity run = service.startRun(5L, 90L, "deepseek-chat", "问题", 100, null);

        service.recordCacheHitToolCall(run.getId(), 5L, "search_mindmap_nodes", "{}", "命中");

        assertThat(toolCalls("search_mindmap_nodes", AgentMetrics.PHASE_SUCCESS)).isEqualTo(1d);
    }

    private double toolCalls(String tool, String phase) {
        Counter counter = registry.find(AgentMetrics.TOOL_CALLS)
                .tags(AgentMetrics.TAG_TOOL, tool, AgentMetrics.TAG_PHASE, phase)
                .counter();
        return counter == null ? 0d : counter.count();
    }

    private void setId(AgentRunEntity entity, Long id) {
        setField(entity, AgentRunEntity.class, "id", id);
        runIndex.put(id, entity);
    }

    private void setToolCallId(AgentToolCallEntity entity, Long id) {
        setField(entity, AgentToolCallEntity.class, "id", id);
        toolCallIndex.put(id, entity);
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
}
