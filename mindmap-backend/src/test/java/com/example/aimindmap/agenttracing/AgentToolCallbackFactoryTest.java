package com.example.aimindmap.agenttracing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.aimindmap.agenttools.LearningResourceTools;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;

class AgentToolCallbackFactoryTest {

    private final LearningResourceTools tools = Mockito.mock(LearningResourceTools.class);
    private final AgentTracingService tracingService = Mockito.mock(AgentTracingService.class);
    private final AgentTracingProperties properties = new AgentTracingProperties();

    private AgentToolCallbackFactory factory() {
        return new AgentToolCallbackFactory(tools, tracingService, properties, new ObjectMapper());
    }

    private static ToolCallback find(AgentRunTools runTools, String name) {
        return runTools.toolCallbacks().stream()
                .filter(callback -> name.equals(callback.getToolDefinition().name()))
                .findFirst()
                .orElseThrow();
    }

    private static ToolContext context() {
        return new ToolContext(java.util.Map.of(
                AgentToolContextKeys.USER_ID, 8L,
                AgentToolContextKeys.RUN_ID, 77L));
    }

    @Test
    void registersExactlySixToolsWithRequiredNames() {
        AgentRunTools runTools = factory().createForRun(1L, 10L);

        assertThat(runTools.toolCallbacks())
                .extracting(callback -> callback.getToolDefinition().name())
                .containsExactlyInAnyOrder(
                        "search_my_documents",
                        "read_my_document",
                        "search_mindmap_nodes",
                        "read_mindmap",
                        "search_my_memory",
                        "search_my_questions");
    }

    @Test
    void enabledByDefaultWrapsCachingAroundTracingWithFreshInstancesPerRun() throws Exception {
        AgentRunTools first = factory().createForRun(1L, 10L);
        AgentRunTools second = factory().createForRun(1L, 11L);

        assertThat(first.toolCallbacks()).allMatch(CachingToolCallback.class::isInstance);
        assertThat(second.toolCallbacks()).allMatch(CachingToolCallback.class::isInstance);
        assertThat(first.guard()).isNotSameAs(second.guard());
        assertThat(first.cache()).isNotSameAs(second.cache());
        assertThat(first.toolCallbacks()).isNotSameAs(second.toolCallbacks());
        for (ToolCallback callback : first.toolCallbacks()) {
            Object inner = wrappedDelegate(callback);
            assertThat(inner).isInstanceOf(TracingToolCallback.class);
        }
    }

    @Test
    void disabledFallsBackToBareTracingCallbacks() {
        properties.getToolCache().setEnabled(false);

        AgentRunTools runTools = factory().createForRun(1L, 10L);

        assertThat(runTools.toolCallbacks())
                .allMatch(TracingToolCallback.class::isInstance)
                .noneMatch(CachingToolCallback.class::isInstance);
    }

    @Test
    void enabledRepeatedSameArgumentsExecuteUnderlyingToolOnce() {
        when(tracingService.startToolCall(anyLong(), anyLong(), anyString(), anyString())).thenReturn(1L);
        when(tools.searchMyDocuments(anyString(), any())).thenReturn("片段内容");

        AgentRunTools runTools = factory().createForRun(1L, 10L);
        ToolCallback callback = find(runTools, "search_my_documents");

        String first = callback.call("{\"query\":\"进程\"}", context());
        String second = callback.call("{\"query\":\"进程\"}", context());

        assertThat(first).isEqualTo(second).contains("片段内容");
        verify(tools, times(1)).searchMyDocuments(eq("进程"), any());
        verify(tracingService, times(1)).recordCacheHitToolCall(
                eq(77L), eq(8L), eq("search_my_documents"), anyString(), eq(first));
        assertThat(runTools.cache().hits()).isEqualTo(1);
    }

    @Test
    void disabledRepeatedSameArgumentsExecuteUnderlyingToolEveryTime() {
        properties.getToolCache().setEnabled(false);
        when(tracingService.startToolCall(anyLong(), anyLong(), anyString(), anyString())).thenReturn(1L);
        when(tools.searchMyDocuments(anyString(), any())).thenReturn("片段内容");

        AgentRunTools runTools = factory().createForRun(1L, 10L);
        ToolCallback callback = find(runTools, "search_my_documents");

        callback.call("{\"query\":\"进程\"}", context());
        callback.call("{\"query\":\"进程\"}", context());

        verify(tools, times(2)).searchMyDocuments(eq("进程"), any());
        verify(tracingService, never()).recordCacheHitToolCall(
                anyLong(), anyLong(), anyString(), anyString(), anyString());
        assertThat(runTools.cache().hits()).isZero();
    }

    private static Object wrappedDelegate(ToolCallback callback) throws Exception {
        java.lang.reflect.Field field = CachingToolCallback.class.getDeclaredField("delegate");
        field.setAccessible(true);
        return field.get(callback);
    }
}
