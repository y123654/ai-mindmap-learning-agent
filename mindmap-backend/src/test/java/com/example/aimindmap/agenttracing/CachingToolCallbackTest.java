package com.example.aimindmap.agenttracing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.aimindmap.agenttools.AgentToolFailureException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.execution.ToolExecutionException;

class CachingToolCallbackTest {

    private AgentTracingService tracingService;
    private AgentTracingProperties properties;

    @BeforeEach
    void setUp() {
        tracingService = Mockito.mock(AgentTracingService.class);
        when(tracingService.startToolCall(anyLong(), anyLong(), any(), any())).thenReturn(1L);
        properties = new AgentTracingProperties();
    }

    @Test
    void sameArgumentsExecuteDelegateOnceAndReturnSameResult() {
        ToolCallback raw = delegate("search_my_documents", "片段内容");
        PerRunToolResultCache cache = new PerRunToolResultCache(new ObjectMapper());
        ToolCallback callback = caching(raw, new AgentToolCallGuard(3), cache);
        String arguments = "{\"query\":\"进程\"}";

        String first = callback.call(arguments, context());
        String second = callback.call(arguments, context());

        assertThat(first).isEqualTo("片段内容");
        assertThat(second).isEqualTo("片段内容");
        verify(raw, times(1)).call(any(), any());
        verify(tracingService, times(1)).startToolCall(anyLong(), anyLong(), any(), any());
        verify(tracingService, times(1)).recordCacheHitToolCall(
                eq(77L), eq(8L), eq("search_my_documents"), eq(arguments), eq("片段内容"));
        assertThat(cache.hits()).isEqualTo(1);
    }

    @Test
    void differentKeyOrderAndWhitespaceStillHit() {
        ToolCallback raw = delegate("search_my_documents", "结果A");
        PerRunToolResultCache cache = new PerRunToolResultCache(new ObjectMapper());
        ToolCallback callback = caching(raw, new AgentToolCallGuard(3), cache);

        assertThat(callback.call("{\"query\":\"进程\",\"top\":2}", context())).isEqualTo("结果A");
        assertThat(callback.call("{  \"top\": 2 , \"query\": \"进程\" }", context())).isEqualTo("结果A");

        verify(raw, times(1)).call(any(), any());
        verify(tracingService, times(1)).recordCacheHitToolCall(
                eq(77L), eq(8L), eq("search_my_documents"), any(), eq("结果A"));
        assertThat(cache.hits()).isEqualTo(1);
    }

    @Test
    void differentArgumentsDoNotHit() {
        ToolCallback raw = delegate("search_my_documents", "结果");
        PerRunToolResultCache cache = new PerRunToolResultCache(new ObjectMapper());
        ToolCallback callback = caching(raw, new AgentToolCallGuard(3), cache);

        callback.call("{\"query\":\"进程\"}", context());
        callback.call("{\"query\":\"线程\"}", context());

        verify(raw, times(2)).call(any(), any());
        verify(tracingService, times(2)).startToolCall(anyLong(), anyLong(), any(), any());
        verify(tracingService, never()).recordCacheHitToolCall(
                anyLong(), anyLong(), any(), any(), any());
        assertThat(cache.hits()).isZero();
    }

    @Test
    void delegateExceptionIsNotCachedAndSameArgumentsReExecute() {
        AgentToolFailureException cause =
                new AgentToolFailureException("AGENT_RESOURCE_DENIED", "拒绝访问", false);
        ToolCallback raw = delegate("read_my_document",
                new ToolExecutionException(definition("read_my_document"), cause));
        PerRunToolResultCache cache = new PerRunToolResultCache(new ObjectMapper());
        ToolCallback callback = caching(raw, new AgentToolCallGuard(3), cache);
        String arguments = "{\"documentId\":\"999\"}";

        assertThatThrownBy(() -> callback.call(arguments, context()))
                .isInstanceOf(ToolExecutionException.class);
        assertThatThrownBy(() -> callback.call(arguments, context()))
                .isInstanceOf(ToolExecutionException.class);

        verify(raw, times(2)).call(any(), any());
        verify(tracingService, times(2)).failToolCall(anyLong(), any(), anyLong());
        verify(tracingService, never()).recordCacheHitToolCall(
                anyLong(), anyLong(), any(), any(), any());
        assertThat(cache.hits()).isZero();
    }

    @Test
    void cacheHitsDoNotConsumeGuardQuota() {
        // P1-D1：这条断言的是“硬中止”行为，显式关掉软降级开关（默认已改为软降级）
        properties.setToolBudgetSoft(false);
        ToolCallback raw = delegate("search_my_documents", "结果");
        PerRunToolResultCache cache = new PerRunToolResultCache(new ObjectMapper());
        AgentToolCallGuard guard = new AgentToolCallGuard(1);
        ToolCallback callback = caching(raw, guard, cache);
        String sameArguments = "{\"query\":\"进程\"}";

        assertThat(callback.call(sameArguments, context())).isEqualTo("结果");
        assertThat(callback.call(sameArguments, context())).isEqualTo("结果");
        assertThat(callback.call(sameArguments, context())).isEqualTo("结果");

        assertThatThrownBy(() -> callback.call("{\"query\":\"线程\"}", context()))
                .isInstanceOf(AgentRunAbortException.class)
                .satisfies(ex -> assertThat(((AgentRunAbortException) ex).code())
                        .isEqualTo("AGENT_TOOL_LIMIT_EXCEEDED"));

        verify(raw, times(1)).call(any(), any());
        verify(tracingService, times(1)).startToolCall(anyLong(), anyLong(), any(), any());
        verify(tracingService, times(2)).recordCacheHitToolCall(
                anyLong(), anyLong(), eq("search_my_documents"), any(), eq("结果"));
        assertThat(guard.acquiredCount()).isEqualTo(2);
        assertThat(cache.hits()).isEqualTo(2);
    }

    @Test
    void budgetExhaustedTextIsNotCachedAsToolResult() {
        ToolCallback raw = delegate("search_my_documents", "结果");
        PerRunToolResultCache cache = new PerRunToolResultCache(new ObjectMapper());
        AgentToolCallGuard guard = new AgentToolCallGuard(1);
        ToolCallback callback = caching(raw, guard, cache);
        String firstArguments = "{\"query\":\"进程\"}";

        assertThat(callback.call(firstArguments, context())).isEqualTo("结果");
        // 第 2 次（不同参数）预算已满：返回固定提示，且不把提示文本写进缓存
        assertThat(callback.call("{\"query\":\"线程\"}", context()))
                .isEqualTo(TracingToolCallback.TOOL_BUDGET_EXHAUSTED_TEXT);
        // 之后再调第 1 次那组参数：命中真实结果缓存（不占名额），说明提示文本没有被缓存
        assertThat(callback.call(firstArguments, context())).isEqualTo("结果");

        assertThat(cache.hits()).isEqualTo(1);
        verify(tracingService, times(1)).recordCacheHitToolCall(
                eq(77L), eq(8L), eq("search_my_documents"), eq(firstArguments), eq("结果"));
        assertThat(guard.acquiredCount()).isEqualTo(1);
    }

    @Test
    void separateRunsWithSeparateCachesNeverShareResults() {
        ToolCallback rawFirst = delegate("search_my_documents", "结果1");
        ToolCallback rawSecond = delegate("search_my_documents", "结果2");
        PerRunToolResultCache cacheFirst = new PerRunToolResultCache(new ObjectMapper());
        PerRunToolResultCache cacheSecond = new PerRunToolResultCache(new ObjectMapper());
        ToolCallback firstRun = caching(rawFirst, new AgentToolCallGuard(3), cacheFirst);
        ToolCallback secondRun = caching(rawSecond, new AgentToolCallGuard(3), cacheSecond);
        String arguments = "{\"query\":\"进程\"}";

        assertThat(firstRun.call(arguments, context())).isEqualTo("结果1");
        assertThat(secondRun.call(arguments, context())).isEqualTo("结果2");
        assertThat(firstRun.call(arguments, context())).isEqualTo("结果1");

        verify(rawFirst, times(1)).call(any(), any());
        verify(rawSecond, times(1)).call(any(), any());
        assertThat(cacheFirst.hits()).isEqualTo(1);
        assertThat(cacheSecond.hits()).isZero();
    }

    private ToolCallback caching(ToolCallback raw, AgentToolCallGuard guard,
                                 PerRunToolResultCache cache) {
        TracingToolCallback tracing = new TracingToolCallback(raw, tracingService, guard, properties);
        return new CachingToolCallback(tracing, tracingService, cache);
    }

    // ===== P0-2：缓存命中补发过程事件 =====

    @Test
    void cacheHitEmitsStartAndSucceedFromCacheWithZeroDuration() {
        RecordingSink sink = new RecordingSink();
        ToolCallback raw = delegate("search_my_documents", "片段内容");
        PerRunToolResultCache cache = new PerRunToolResultCache(new ObjectMapper());
        ToolCallback callback = caching(raw, new AgentToolCallGuard(3), cache);
        String arguments = "{\"query\":\"时间片轮转\"}";

        callback.call(arguments, context(sink));
        callback.call(arguments, context(sink));

        assertThat(sink.events).hasSize(4);
        // 第一次：真实调用
        assertThat(sink.events.get(0).phase()).isEqualTo(AgentToolCallEvent.PHASE_START);
        assertThat(sink.events.get(1).fromCache()).isFalse();
        // 第二次：缓存命中，start 与 succeed 共用 seq=2
        AgentToolCallEvent hitStart = sink.events.get(2);
        AgentToolCallEvent hitSucceed = sink.events.get(3);
        assertThat(hitStart.phase()).isEqualTo(AgentToolCallEvent.PHASE_START);
        assertThat(hitStart.seq()).isEqualTo(2L);
        assertThat(hitStart.detail()).isEqualTo("时间片轮转");
        assertThat(hitSucceed.phase()).isEqualTo(AgentToolCallEvent.PHASE_SUCCEED);
        assertThat(hitSucceed.seq()).isEqualTo(2L);
        assertThat(hitSucceed.fromCache()).isTrue();
        assertThat(hitSucceed.durationMs()).isZero();
        assertThat(hitSucceed.detail()).isEqualTo("时间片轮转");
    }

    @Test
    void cacheMissEmitsSingleStartSucceedPairWithoutDuplication() {
        RecordingSink sink = new RecordingSink();
        ToolCallback raw = delegate("search_mindmap_nodes", "节点结果");
        PerRunToolResultCache cache = new PerRunToolResultCache(new ObjectMapper());
        ToolCallback callback = caching(raw, new AgentToolCallGuard(3), cache);

        callback.call("{\"query\":\"进程调度\"}", context(sink));

        assertThat(sink.events).extracting(AgentToolCallEvent::phase)
                .containsExactly(AgentToolCallEvent.PHASE_START, AgentToolCallEvent.PHASE_SUCCEED);
        assertThat(sink.events.get(1).fromCache()).isFalse();
    }

    private static ToolContext context(AgentToolEventSink sink) {
        return new ToolContext(java.util.Map.of(
                AgentToolContextKeys.USER_ID, 8L,
                AgentToolContextKeys.RUN_ID, 77L,
                AgentToolContextKeys.TOOL_EVENT_SINK, sink));
    }

    /** 记录事件序列的最小 sink 实现。 */
    private static final class RecordingSink implements AgentToolEventSink {

        private final AtomicInteger seq = new AtomicInteger();
        private final List<AgentToolCallEvent> events = new ArrayList<>();

        @Override
        public int nextSeq() {
            return seq.incrementAndGet();
        }

        @Override
        public void emit(AgentToolCallEvent event) {
            events.add(event);
        }
    }

    private static ToolContext context() {
        return new ToolContext(java.util.Map.of(
                AgentToolContextKeys.USER_ID, 8L,
                AgentToolContextKeys.RUN_ID, 77L));
    }

    private static ToolDefinition definition(String name) {
        return ToolDefinition.builder().name(name).description("测试").inputSchema("{\"type\":\"object\"}").build();
    }

    private static ToolCallback delegate(String name, String result) {
        ToolCallback mock = Mockito.mock(ToolCallback.class);
        Mockito.when(mock.getToolDefinition()).thenReturn(definition(name));
        Mockito.when(mock.call(any(), any())).thenReturn(result);
        return mock;
    }

    private static ToolCallback delegate(String name, RuntimeException thrown) {
        ToolCallback mock = Mockito.mock(ToolCallback.class);
        Mockito.when(mock.getToolDefinition()).thenReturn(definition(name));
        Mockito.when(mock.call(any(), any())).thenThrow(thrown);
        return mock;
    }
}
