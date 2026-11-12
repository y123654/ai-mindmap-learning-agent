package com.example.aimindmap.agenttracing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.aimindmap.agenttools.AgentToolFailureException;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.execution.ToolExecutionException;

class TracingToolCallbackTest {

    private AgentTracingService tracingService;
    private AgentTracingProperties properties;

    @BeforeEach
    void setUp() {
        tracingService = Mockito.mock(AgentTracingService.class);
        when(tracingService.startToolCall(anyLong(), anyLong(), any(), any())).thenReturn(1L);
        properties = new AgentTracingProperties();
    }

    @Test
    void successfulCallIsRecordedAndResultReturned() {
        ToolCallback delegate = delegate("search_my_documents", "片段内容");
        TracingToolCallback callback = newCallback(delegate, new AgentToolCallGuard(3));

        String result = callback.call("{}", context());

        assertThat(result).isEqualTo("片段内容");
        verify(tracingService).startToolCall(eq(77L), eq(8L), eq("search_my_documents"), eq("{}"));
        verify(tracingService).succeedToolCall(eq(1L), eq("片段内容"), anyLong());
    }

    @Test
    void longResultIsTruncatedWithNotice() {
        String longResult = "x".repeat(properties.getToolResultMaxChars() + 100);
        ToolCallback delegate = delegate("read_my_document", longResult);
        TracingToolCallback callback = newCallback(delegate, new AgentToolCallGuard(3));

        String result = callback.call("{}", context());

        assertThat(result).hasSizeLessThan(longResult.length());
        assertThat(result).contains("已截断");
        verify(tracingService).succeedToolCall(eq(1L),
                org.mockito.ArgumentMatchers.contains("已截断"), anyLong());
    }

    // ===== P1-E3b：截断不能把 <evidence> 标签切一半 =====

    /**
     * 无标签（开关关 / 没包装）时，截断输出必须与改造前<b>逐字节一致</b>：
     * {@code substring(0,maxChars) + "\n\n[系统提示：…已截断…]"}。
     */
    @Test
    void truncationWithoutEvidenceTagsIsByteIdenticalToLegacyBehavior() {
        int maxChars = properties.getToolResultMaxChars();
        String longResult = "x".repeat(maxChars + 100);
        ToolCallback delegate = delegate("read_my_document", longResult);
        TracingToolCallback callback = newCallback(delegate, new AgentToolCallGuard(3));

        String result = callback.call("{}", context());

        String expected = longResult.substring(0, maxChars)
                + "\n\n[系统提示：工具返回内容超过 " + maxChars
                + " 字符已截断，请缩小搜索范围或指定片段后重试]";
        assertThat(result).isEqualTo(expected);
    }

    /**
     * 截断点落在证据块中间（开标签已完整、闭标签还没出现）：必须补一个 {@code </evidence>}，
     * 且截断说明在<b>所有标签之外</b>。
     */
    @Test
    void truncationInsideEvidenceBodyClosesTheDanglingTagBeforeNotice() {
        int maxChars = properties.getToolResultMaxChars();
        String body = "证据正文".repeat(800);
        String longResult = "共检索到 1 条相关内容：\n\n---\n"
                + "<evidence id=\"E1\" source=\"document_chunk:42\">\n"
                + "[E1] [来源类型: document_chunk, 资源ID: 42]\n内容：\n"
                + body
                + "\n</evidence>\n";
        assertThat(longResult.length()).isGreaterThan(maxChars);
        ToolCallback delegate = delegate("search_my_documents", longResult);
        TracingToolCallback callback = newCallback(delegate, new AgentToolCallGuard(3));

        String result = callback.call("{}", context());

        assertThat(countOccurrences(result, "<evidence")).isEqualTo(1);
        assertThat(countOccurrences(result, "</evidence>"))
                .as("开/闭标签数必须相等：%s", result.substring(Math.max(0, result.length() - 120)))
                .isEqualTo(1);
        // 最后一个可见证据有闭标签，且截断说明在闭标签之后（标签之外）
        assertThat(result.lastIndexOf("</evidence>")).isLessThan(result.indexOf("已截断"));
        assertThat(result.indexOf("已截断")).isGreaterThan(result.lastIndexOf("<evidence"));
    }

    /**
     * 截断点正好落在 {@code <evidence ...>} 开标签内部：残缺开标签必须整个去掉，
     * 不能留给模型半个标签。
     */
    @Test
    void truncationInsideOpenTagDropsTheBrokenTag() {
        int maxChars = properties.getToolResultMaxChars();
        // 让截断点落在第二个证据的开标签属性里：<evidence id="E2" source="document_chunk:884201">
        String firstBlock = "<evidence id=\"E1\" source=\"document_chunk:41\">\n"
                + "短正文\n</evidence>\n";
        String padding = "x".repeat(maxChars - firstBlock.length() - 10);
        String secondOpenTag = "<evidence id=\"E2\" source=\"document_chunk:884201\">\n";
        String longResult = firstBlock + padding + secondOpenTag + "第二条正文\n</evidence>\n";
        assertThat(longResult.length()).isGreaterThan(maxChars);
        ToolCallback delegate = delegate("search_my_documents", longResult);
        TracingToolCallback callback = newCallback(delegate, new AgentToolCallGuard(3));

        String result = callback.call("{}", context());

        // 残缺的 "<evidence id=\"E2\" …>" 被整段去掉：只剩 E1 那对标签
        assertThat(result).doesNotContain("E2");
        assertThat(countOccurrences(result, "<evidence")).isEqualTo(1);
        assertThat(countOccurrences(result, "</evidence>")).isEqualTo(1);
        assertThat(result).contains("id=\"E1\"");
        // 截断说明在所有标签之外
        assertThat(result.lastIndexOf("</evidence>")).isLessThan(result.indexOf("已截断"));
    }

    private static int countOccurrences(String text, String marker) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(marker, index)) >= 0) {
            count++;
            index += marker.length();
        }
        return count;
    }

    @Test
    void nonFatalFailureIsRecordedAndRethrownAsToolException() {
        AgentToolFailureException cause =
                new AgentToolFailureException("AGENT_RESOURCE_DENIED", "拒绝访问", false);
        ToolCallback delegate = delegate("read_my_document",
                new ToolExecutionException(definition("read_my_document"), cause));
        TracingToolCallback callback = newCallback(delegate, new AgentToolCallGuard(3));

        assertThatThrownBy(() -> callback.call("{}", context()))
                .isInstanceOf(ToolExecutionException.class);

        verify(tracingService).failToolCall(eq(1L), org.mockito.ArgumentMatchers.contains("拒绝访问"), anyLong());
        verify(tracingService, never()).succeedToolCall(anyLong(), any(), anyLong());
    }

    @Test
    void fatalFailureAbortsRun() {
        AgentToolFailureException cause =
                new AgentToolFailureException("AGENT_RETRIEVAL_FAILED", "检索不可用", true);
        ToolCallback delegate = delegate("search_my_questions",
                new ToolExecutionException(definition("search_my_questions"), cause));
        TracingToolCallback callback = newCallback(delegate, new AgentToolCallGuard(3));

        assertThatThrownBy(() -> callback.call("{}", context()))
                .isInstanceOf(AgentRunAbortException.class)
                .satisfies(ex -> assertThat(((AgentRunAbortException) ex).code())
                        .isEqualTo("AGENT_RETRIEVAL_FAILED"));

        verify(tracingService).failToolCall(eq(1L), any(), anyLong());
    }

    @Test
    void hardAbortModeBlocksFourthCallAcrossSharedGuard() {
        // P1-D1：硬中止开关（tool-budget-soft=false）仍保留原行为——第 4 次直接中止整轮
        properties.setToolBudgetSoft(false);
        AgentToolCallGuard sharedGuard = new AgentToolCallGuard(3);
        ToolCallback first = delegate("search_my_documents", "a");
        ToolCallback second = delegate("search_mindmap_nodes", "b");
        ToolCallback third = delegate("read_my_document", "c");
        ToolCallback fourth = delegate("search_my_questions", "d");

        newCallback(first, sharedGuard).call("{}", context());
        newCallback(second, sharedGuard).call("{}", context());
        newCallback(third, sharedGuard).call("{}", context());

        assertThatThrownBy(() -> newCallback(fourth, sharedGuard).call("{}", context()))
                .isInstanceOf(AgentRunAbortException.class)
                .satisfies(ex -> assertThat(((AgentRunAbortException) ex).code())
                        .isEqualTo("AGENT_TOOL_LIMIT_EXCEEDED"));

        verify(fourth, never()).call(any(), any());
        verify(tracingService, Mockito.times(3)).startToolCall(anyLong(), anyLong(), any(), any());
    }

    @Test
    void defaultSoftModeFourthCallReturnsBudgetTextInsteadOfAborting() {
        RecordingSink sink = new RecordingSink();
        AgentToolCallGuard sharedGuard = new AgentToolCallGuard(3);
        ToolCallback first = delegate("search_my_documents", "a");
        ToolCallback second = delegate("search_mindmap_nodes", "b");
        ToolCallback third = delegate("read_my_document", "c");
        ToolCallback fourth = delegate("search_my_questions", "d");

        newCallback(first, sharedGuard).call("{}", context());
        newCallback(second, sharedGuard).call("{}", context());
        newCallback(third, sharedGuard).call("{}", context());

        String result = newCallback(fourth, sharedGuard).call("{}", context(sink));

        assertThat(result).isEqualTo(TracingToolCallback.TOOL_BUDGET_EXHAUSTED_TEXT);
        assertThat(result)
                .isEqualTo("工具调用预算已用完。请基于已有检索结果作答；若无足够依据，请明确说明。");
        // 第 4 次不是真实调用：不执行、不落库、不占名额
        verify(fourth, never()).call(any(), any());
        verify(tracingService, Mockito.times(3)).startToolCall(anyLong(), anyLong(), any(), any());
        // 只有前 3 次真实调用成功落库，第 4 次没有新增成功记录
        verify(tracingService, Mockito.times(3)).succeedToolCall(anyLong(), any(), anyLong());
        assertThat(sharedGuard.acquiredCount()).isEqualTo(3);
        // 只发一条 failed，没有 start
        assertThat(sink.events).hasSize(1);
        assertThat(sink.events.get(0).phase()).isEqualTo(AgentToolCallEvent.PHASE_FAILED);
        assertThat(sink.events.get(0).errorCode()).isEqualTo("AGENT_TOOL_LIMIT_EXCEEDED");
    }

    @Test
    void missingBudgetKeyKeepsLegacySingleRetry() {
        ToolDefinition definition = definition("search_my_documents");
        ToolCallback delegate = Mockito.mock(ToolCallback.class);
        Mockito.when(delegate.getToolDefinition()).thenReturn(definition);
        Mockito.when(delegate.call(any(), any()))
                .thenThrow(new ToolExecutionException(definition, new AgentToolFailureException(
                        "AGENT_RETRIEVAL_TRANSIENT", "临时抖动", false, true)))
                .thenReturn("重试成功的结果");

        // ToolContext 里没有 REMAINING_BUDGET_MS（老调用方 / 老单测）→ 仍按旧行为重试 1 次
        String result = newCallback(delegate, new AgentToolCallGuard(3)).call("{}", context());

        assertThat(result).isEqualTo("重试成功的结果");
        verify(delegate, Mockito.times(2)).call(any(), any());
    }

    @Test
    void sufficientRemainingBudgetStillRetriesTimeoutOnce() {
        properties.setToolTimeoutSeconds(1);
        ToolCallback delegate = sleepingDelegate("read_my_document", 5_000);
        AgentToolCallGuard guard = new AgentToolCallGuard(3);

        // 一次重试需要 200ms 退避 + 1000ms 单工具超时 = 1200ms；剩余 10s 明显够 → 照旧执行 2 次
        String result = newCallback(delegate, guard).call("{}", contextWithBudget(10_000L));

        assertThat(result).contains("AGENT_TOOL_TIMEOUT");
        verify(delegate, Mockito.times(2)).call(any(), any());
        verify(tracingService).failToolCall(eq(1L),
                org.mockito.ArgumentMatchers.contains("AGENT_TOOL_TIMEOUT"), anyLong());
        verify(tracingService, never()).succeedToolCall(anyLong(), any(), anyLong());
        verify(tracingService, Mockito.times(1))
                .startToolCall(anyLong(), anyLong(), any(), any());
        assertThat(guard.acquiredCount()).isEqualTo(1);
    }

    @Test
    void remainingBudgetJustEnoughForOneAttemptSkipsRetryAndLogs() {
        properties.setToolTimeoutSeconds(1);
        ToolCallback delegate = sleepingDelegate("read_my_document", 5_000);
        AgentToolCallGuard guard = new AgentToolCallGuard(3);
        ListAppender<ILoggingEvent> appender = attachAppender(TracingToolCallback.class);

        String result;
        try {
            // 门槛 = 200ms 退避 + 1000ms 单工具超时 = 1200ms；剩余“刚好等于门槛”→ 不重试
            result = newCallback(delegate, guard)
                    .call("{}", contextWithBudget(TracingToolCallback.RETRY_DELAY_MS + 1_000L));
        } finally {
            detachAppender(TracingToolCallback.class, appender);
        }

        // 不重试：delegate 只跑 1 次；但失败仍按“非致命 + retryable”把提示文本交给模型，不中止整轮
        assertThat(result).contains("AGENT_TOOL_TIMEOUT").contains("请调整调用方式后重试");
        verify(delegate, Mockito.times(1)).call(any(), any());
        verify(tracingService).failToolCall(eq(1L),
                org.mockito.ArgumentMatchers.contains("AGENT_TOOL_TIMEOUT"), anyLong());
        verify(tracingService, never()).succeedToolCall(anyLong(), any(), anyLong());
        verify(tracingService, Mockito.times(1))
                .startToolCall(anyLong(), anyLong(), any(), any());
        assertThat(guard.acquiredCount()).isEqualTo(1);
        assertThat(appender.list).anySatisfy(event -> assertThat(event.getFormattedMessage())
                .contains("因剩余预算不足跳过工具重试")
                .contains("runId=77")
                .contains("toolName=read_my_document")
                .contains("remainingMs=1200")
                .contains("requiredMs=1200"));
    }

    @Test
    void insufficientRemainingBudgetSkipsRetryForRetryableBusinessFailure() {
        ToolDefinition definition = definition("search_my_documents");
        ToolCallback delegate = Mockito.mock(ToolCallback.class);
        Mockito.when(delegate.getToolDefinition()).thenReturn(definition);
        Mockito.when(delegate.call(any(), any()))
                .thenThrow(new ToolExecutionException(definition, new AgentToolFailureException(
                        "AGENT_RETRIEVAL_TRANSIENT", "向量库抖动", false, true)))
                .thenReturn("不应出现的第二次执行结果");

        // 剩余 500ms 远不够“200ms 退避 + 10s 单工具超时”→ 不重试，直接给模型提示文本
        String result = newCallback(delegate, new AgentToolCallGuard(3))
                .call("{}", contextWithBudget(500L));

        assertThat(result).contains("AGENT_RETRIEVAL_TRANSIENT")
                .contains("向量库抖动")
                .contains("请调整调用方式后重试");
        verify(delegate, Mockito.times(1)).call(any(), any());
    }

    private TracingToolCallback newCallback(ToolCallback delegate, AgentToolCallGuard guard) {
        return new TracingToolCallback(delegate, tracingService, guard, properties);
    }

    // ===== P0-2：工具过程事件 =====

    @Test
    void successEmitsStartThenSucceedWithSharedSeq() {
        RecordingSink sink = new RecordingSink();
        String arguments = "{\"query\":\"时间片轮转\"}";
        ToolCallback delegate = delegate("search_my_documents", "片段内容");
        newCallback(delegate, new AgentToolCallGuard(3)).call(arguments, context(sink));

        assertThat(sink.events).hasSize(2);
        AgentToolCallEvent start = sink.events.get(0);
        AgentToolCallEvent succeed = sink.events.get(1);

        assertThat(start.phase()).isEqualTo(AgentToolCallEvent.PHASE_START);
        assertThat(start.seq()).isEqualTo(1L);
        assertThat(start.tool()).isEqualTo("search_my_documents");
        assertThat(start.label()).isEqualTo("检索课程笔记");
        assertThat(start.detail()).isEqualTo("时间片轮转");
        assertThat(start.fromCache()).isNull();
        assertThat(start.durationMs()).isNull();

        assertThat(succeed.phase()).isEqualTo(AgentToolCallEvent.PHASE_SUCCEED);
        assertThat(succeed.seq()).isEqualTo(1L);
        assertThat(succeed.label()).isEqualTo("检索课程笔记");
        assertThat(succeed.fromCache()).isFalse();
        assertThat(succeed.durationMs()).isNotNull().isGreaterThanOrEqualTo(0L);
        assertThat(succeed.errorCode()).isNull();
    }

    @Test
    void secondCallGetsNextSeq() {
        RecordingSink sink = new RecordingSink();
        AgentToolCallGuard guard = new AgentToolCallGuard(3);

        newCallback(delegate("search_my_documents", "a"), guard)
                .call("{\"query\":\"进程\"}", context(sink));
        newCallback(delegate("search_mindmap_nodes", "b"), guard)
                .call("{\"query\":\"线程\"}", context(sink));

        assertThat(sink.events).extracting(AgentToolCallEvent::seq)
                .containsExactly(1L, 1L, 2L, 2L);
    }

    @Test
    void nonFatalBusinessFailureEmitsFailedWithBusinessCode() {
        RecordingSink sink = new RecordingSink();
        AgentToolFailureException cause =
                new AgentToolFailureException("AGENT_RESOURCE_DENIED", "拒绝访问", false);
        ToolCallback delegate = delegate("read_my_document",
                new ToolExecutionException(definition("read_my_document"), cause));

        assertThatThrownBy(() -> newCallback(delegate, new AgentToolCallGuard(3))
                .call("{\"documentId\":\"888901\"}", context(sink)))
                .isInstanceOf(ToolExecutionException.class);

        assertThat(sink.events).hasSize(2);
        assertThat(sink.events.get(0).phase()).isEqualTo(AgentToolCallEvent.PHASE_START);
        AgentToolCallEvent failed = sink.events.get(1);
        assertThat(failed.phase()).isEqualTo(AgentToolCallEvent.PHASE_FAILED);
        assertThat(failed.seq()).isEqualTo(1L);
        assertThat(failed.errorCode()).isEqualTo("AGENT_RESOURCE_DENIED");
        assertThat(failed.fromCache()).isNull();
        assertThat(failed.durationMs()).isNull();
        assertThat(failed.detail()).isEqualTo("读取笔记全文");
    }

    @Test
    void fatalBusinessFailureEmitsFailedWithFatalCode() {
        RecordingSink sink = new RecordingSink();
        AgentToolFailureException cause =
                new AgentToolFailureException("AGENT_RETRIEVAL_FAILED", "检索不可用", true);
        ToolCallback delegate = delegate("search_my_questions",
                new ToolExecutionException(definition("search_my_questions"), cause));

        assertThatThrownBy(() -> newCallback(delegate, new AgentToolCallGuard(3))
                .call("{\"query\":\"进程\"}", context(sink)))
                .isInstanceOf(AgentRunAbortException.class);

        assertThat(sink.events).hasSize(2);
        assertThat(sink.events.get(1).phase()).isEqualTo(AgentToolCallEvent.PHASE_FAILED);
        assertThat(sink.events.get(1).errorCode()).isEqualTo("AGENT_RETRIEVAL_FAILED");
    }

    @Test
    void overLimitEmitsOnlyFailedEventWithoutStart() {
        // P1-D1：这条继续覆盖“硬中止模式”的事件序列；软降级的事件序列见下面一条
        properties.setToolBudgetSoft(false);
        RecordingSink sink = new RecordingSink();
        AgentToolCallGuard guard = new AgentToolCallGuard(1);

        newCallback(delegate("search_my_documents", "a"), guard)
                .call("{\"query\":\"进程\"}", context(sink));
        assertThatThrownBy(() -> newCallback(delegate("search_my_documents", "b"), guard)
                .call("{\"query\":\"线程\"}", context(sink)))
                .isInstanceOf(AgentRunAbortException.class);

        assertThat(sink.events).extracting(AgentToolCallEvent::phase)
                .containsExactly(AgentToolCallEvent.PHASE_START,
                        AgentToolCallEvent.PHASE_SUCCEED, AgentToolCallEvent.PHASE_FAILED);
        assertThat(sink.events.get(2).errorCode()).isEqualTo("AGENT_TOOL_LIMIT_EXCEEDED");
    }

    // ===== P1-D1：预算软降级 / 单工具超时 / 1 次重试 / 参数纠错 =====

    @Test
    void softOverLimitEmitsOnlyFailedEventWithoutStartOrRecord() {
        RecordingSink sink = new RecordingSink();
        AgentToolCallGuard guard = new AgentToolCallGuard(1);

        newCallback(delegate("search_my_documents", "a"), guard)
                .call("{\"query\":\"进程\"}", context(sink));
        String result = newCallback(delegate("search_my_documents", "b"), guard)
                .call("{\"query\":\"线程\"}", context(sink));

        assertThat(result).isEqualTo(TracingToolCallback.TOOL_BUDGET_EXHAUSTED_TEXT);
        assertThat(sink.events).extracting(AgentToolCallEvent::phase)
                .containsExactly(AgentToolCallEvent.PHASE_START,
                        AgentToolCallEvent.PHASE_SUCCEED, AgentToolCallEvent.PHASE_FAILED);
        assertThat(sink.events.get(2).errorCode()).isEqualTo("AGENT_TOOL_LIMIT_EXCEEDED");
        assertThat(guard.acquiredCount()).isEqualTo(1);
        verify(tracingService, Mockito.times(1))
                .startToolCall(anyLong(), anyLong(), any(), any());
    }

    @Test
    void timeoutIsReportedAsRetryableNonFatalFailure() {
        properties.setToolTimeoutSeconds(1);
        TracingToolCallback callback = newCallback(
                sleepingDelegate("read_my_document", 5_000), new AgentToolCallGuard(3));

        assertThatThrownBy(() -> callback.executeWithTimeout("{}", context()))
                .isInstanceOf(AgentToolFailureException.class)
                .satisfies(ex -> {
                    AgentToolFailureException failure = (AgentToolFailureException) ex;
                    assertThat(failure.code()).isEqualTo("AGENT_TOOL_TIMEOUT");
                    assertThat(failure.retryable()).isTrue();
                    assertThat(failure.fatal()).isFalse();
                });
    }

    @Test
    void timeoutIsRecordedAndEmitsFailedWithoutAbortingRun() {
        properties.setToolTimeoutSeconds(1);
        RecordingSink sink = new RecordingSink();
        ToolCallback delegate = sleepingDelegate("read_my_document", 5_000);
        AgentToolCallGuard guard = new AgentToolCallGuard(3);

        String result = newCallback(delegate, guard).call("{}", context(sink));

        // 不中止整轮：超时按 retryable 重试 1 次后把错误文本交给模型
        assertThat(result).contains("AGENT_TOOL_TIMEOUT").contains("超时");
        verify(delegate, Mockito.times(2)).call(any(), any());
        verify(tracingService).failToolCall(eq(1L),
                org.mockito.ArgumentMatchers.contains("AGENT_TOOL_TIMEOUT"), anyLong());
        verify(tracingService, never()).succeedToolCall(anyLong(), any(), anyLong());
        verify(tracingService, Mockito.times(1))
                .startToolCall(anyLong(), anyLong(), any(), any());
        assertThat(sink.events).extracting(AgentToolCallEvent::phase)
                .containsExactly(AgentToolCallEvent.PHASE_START, AgentToolCallEvent.PHASE_FAILED);
        assertThat(sink.events.get(1).errorCode()).isEqualTo("AGENT_TOOL_TIMEOUT");
        assertThat(guard.acquiredCount()).isEqualTo(1);
    }

    @Test
    void retryableFailureRetriesOnceThenSucceedsWithSingleRecordAndEventPair() {
        RecordingSink sink = new RecordingSink();
        AgentToolFailureException transientFailure = new AgentToolFailureException(
                "AGENT_RETRIEVAL_TRANSIENT", "临时抖动", false, true);
        ToolDefinition definition = definition("search_my_documents");
        ToolCallback delegate = Mockito.mock(ToolCallback.class);
        Mockito.when(delegate.getToolDefinition()).thenReturn(definition);
        Mockito.when(delegate.call(any(), any()))
                .thenThrow(new ToolExecutionException(definition, transientFailure))
                .thenReturn("重试成功的结果");
        AgentToolCallGuard guard = new AgentToolCallGuard(3);

        String result = newCallback(delegate, guard).call("{}", context(sink));

        assertThat(result).isEqualTo("重试成功的结果");
        verify(delegate, Mockito.times(2)).call(any(), any());
        // 只落一行工具调用、只发一对 start/succeed、名额只占 1
        verify(tracingService, Mockito.times(1))
                .startToolCall(eq(77L), eq(8L), eq("search_my_documents"), eq("{}"));
        verify(tracingService, Mockito.times(1))
                .succeedToolCall(eq(1L), eq("重试成功的结果"), anyLong());
        verify(tracingService, never()).failToolCall(anyLong(), any(), anyLong());
        assertThat(sink.events).extracting(AgentToolCallEvent::phase)
                .containsExactly(AgentToolCallEvent.PHASE_START, AgentToolCallEvent.PHASE_SUCCEED);
        assertThat(guard.acquiredCount()).isEqualTo(1);
    }

    @Test
    void retryableFailureStillFailingReturnsErrorTextWithoutAbort() {
        RecordingSink sink = new RecordingSink();
        ToolDefinition definition = definition("search_my_documents");
        ToolCallback delegate = Mockito.mock(ToolCallback.class);
        Mockito.when(delegate.getToolDefinition()).thenReturn(definition);
        Mockito.when(delegate.call(any(), any())).thenThrow(
                new ToolExecutionException(definition, new AgentToolFailureException(
                        "AGENT_RETRIEVAL_TRANSIENT", "第一次抖动", false, true)),
                new ToolExecutionException(definition, new AgentToolFailureException(
                        "AGENT_RETRIEVAL_TRANSIENT", "第二次仍抖动", false, true)));
        AgentToolCallGuard guard = new AgentToolCallGuard(3);

        String result = newCallback(delegate, guard).call("{}", context(sink));

        assertThat(result).contains("AGENT_RETRIEVAL_TRANSIENT")
                .contains("第二次仍抖动")
                .contains("请调整调用方式后重试");
        verify(delegate, Mockito.times(2)).call(any(), any());
        verify(tracingService).failToolCall(eq(1L),
                org.mockito.ArgumentMatchers.contains("第二次仍抖动"), anyLong());
        verify(tracingService, never()).succeedToolCall(anyLong(), any(), anyLong());
        assertThat(sink.events).extracting(AgentToolCallEvent::phase)
                .containsExactly(AgentToolCallEvent.PHASE_START, AgentToolCallEvent.PHASE_FAILED);
        assertThat(guard.acquiredCount()).isEqualTo(1);
    }

    @Test
    void invalidArgumentReturnsCorrectionTextForModelToRetry() {
        ToolDefinition definition = definition("read_my_document");
        ToolCallback delegate = Mockito.mock(ToolCallback.class);
        Mockito.when(delegate.getToolDefinition()).thenReturn(definition);
        Mockito.when(delegate.call(any(), any())).thenThrow(
                new ToolExecutionException(definition, new AgentToolFailureException(
                        "AGENT_ARGUMENT_INVALID", "AGENT_ARGUMENT_INVALID: 资源 ID 格式不正确：abc",
                        false, true)));
        RecordingSink sink = new RecordingSink();

        String result = newCallback(delegate, new AgentToolCallGuard(3))
                .call("{\"documentId\":\"abc\"}", context(sink));

        assertThat(result).startsWith("参数不合法：")
                .contains("资源 ID 格式不正确：abc")
                .contains("请检查参数格式后重试一次")
                .doesNotContain("AGENT_ARGUMENT_INVALID:");
        // 仍然是“一次调用”：一次 start + 一次 failed，不是直接中止整轮
        assertThat(sink.events).extracting(AgentToolCallEvent::phase)
                .containsExactly(AgentToolCallEvent.PHASE_START, AgentToolCallEvent.PHASE_FAILED);
    }

    @Test
    void invalidArgumentIsNotRetriedSoDelegateRunsOnlyOnce() {
        ToolDefinition definition = definition("read_my_document");
        ToolCallback delegate = Mockito.mock(ToolCallback.class);
        Mockito.when(delegate.getToolDefinition()).thenReturn(definition);
        Mockito.when(delegate.call(any(), any()))
                .thenThrow(new ToolExecutionException(definition, new AgentToolFailureException(
                        "AGENT_ARGUMENT_INVALID", "AGENT_ARGUMENT_INVALID: 资源 ID 格式不正确：abc",
                        false, true)))
                .thenReturn("不应出现的第二次执行结果");
        RecordingSink sink = new RecordingSink();
        AgentToolCallGuard guard = new AgentToolCallGuard(3);

        String result = newCallback(delegate, guard).call("{\"documentId\":\"abc\"}", context(sink));

        assertThat(result).startsWith("参数不合法：")
                .contains("请检查参数格式后重试一次")
                .doesNotContain("不应出现的第二次执行结果");
        // 同参数重试无意义：参数错误只执行 1 次，纠错重试交给模型
        verify(delegate, Mockito.times(1)).call(any(), any());
        assertThat(guard.acquiredCount()).isEqualTo(1);
        assertThat(sink.events).extracting(AgentToolCallEvent::phase)
                .containsExactly(AgentToolCallEvent.PHASE_START, AgentToolCallEvent.PHASE_FAILED);
    }

    @Test
    void timeoutStillRetriesOnceAfterArgumentExceptionIsExcluded() {
        properties.setToolTimeoutSeconds(1);
        ToolCallback delegate = sleepingDelegate("read_my_document", 5_000);
        AgentToolCallGuard guard = new AgentToolCallGuard(3);

        String result = newCallback(delegate, guard).call("{}", context());

        // 超时仍属 retryable 且不是参数错误 → 执行 2 次（首次 + 重试 1 次）
        assertThat(result).contains("AGENT_TOOL_TIMEOUT");
        verify(delegate, Mockito.times(2)).call(any(), any());
        assertThat(guard.acquiredCount()).isEqualTo(1);
    }

    @Test
    void nonRetryableFailureIsNotRetried() {
        ToolDefinition definition = definition("read_my_document");
        ToolCallback delegate = Mockito.mock(ToolCallback.class);
        Mockito.when(delegate.getToolDefinition()).thenReturn(definition);
        Mockito.when(delegate.call(any(), any())).thenThrow(new ToolExecutionException(
                definition, new AgentToolFailureException("AGENT_RESOURCE_DENIED", "拒绝访问", false)));
        AgentToolCallGuard guard = new AgentToolCallGuard(3);

        assertThatThrownBy(() -> newCallback(delegate, guard).call("{}", context()))
                .isInstanceOf(ToolExecutionException.class);

        verify(delegate, Mockito.times(1)).call(any(), any());
        assertThat(guard.acquiredCount()).isEqualTo(1);
    }

    @Test
    void fatalFailureIsNotRetriedEvenWhenRetryable() {
        ToolDefinition definition = definition("search_my_questions");
        ToolCallback delegate = Mockito.mock(ToolCallback.class);
        Mockito.when(delegate.getToolDefinition()).thenReturn(definition);
        Mockito.when(delegate.call(any(), any())).thenThrow(new ToolExecutionException(
                definition, new AgentToolFailureException(
                        "AGENT_RETRIEVAL_FAILED", "检索不可用", true, true)));

        assertThatThrownBy(() -> newCallback(delegate, new AgentToolCallGuard(3))
                .call("{}", context()))
                .isInstanceOf(AgentRunAbortException.class)
                .satisfies(ex -> assertThat(((AgentRunAbortException) ex).code())
                        .isEqualTo("AGENT_RETRIEVAL_FAILED"));

        verify(delegate, Mockito.times(1)).call(any(), any());
    }

    @Test
    void eventTextCarriesNoInternalIdsOrResultBody() {
        RecordingSink sink = new RecordingSink();
        String resultBody = "第一条笔记正文：进程是资源分配的基本单位";
        ToolCallback delegate = delegate("read_my_document", resultBody);

        newCallback(delegate, new AgentToolCallGuard(3))
                .call("{\"documentId\":\"888901\",\"userId\":\"77\"}", context(sink));

        String rendered = sink.events.toString();
        assertThat(rendered).doesNotContain("888901").doesNotContain("77")
                .doesNotContain(resultBody);
    }

    @Test
    void throwingSinkDoesNotBreakToolExecution() {
        AgentToolEventSink throwingSink = new AgentToolEventSink() {
            @Override
            public int nextSeq() {
                return 1;
            }

            @Override
            public void emit(AgentToolCallEvent event) {
                throw new IllegalStateException("模拟事件推送失败");
            }
        };
        ToolCallback delegate = delegate("search_my_documents", "片段内容");

        String result = newCallback(delegate, new AgentToolCallGuard(3))
                .call("{\"query\":\"进程\"}", context(throwingSink));

        assertThat(result).isEqualTo("片段内容");
        verify(tracingService).succeedToolCall(eq(1L), eq("片段内容"), anyLong());
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

    /** P1-D2b：带剩余总预算（毫秒）的 ToolContext。 */
    private static ToolContext contextWithBudget(Long remainingBudgetMs) {
        return new ToolContext(java.util.Map.<String, Object>of(
                AgentToolContextKeys.USER_ID, 8L,
                AgentToolContextKeys.RUN_ID, 77L,
                AgentToolContextKeys.REMAINING_BUDGET_MS, remainingBudgetMs));
    }

    /** 把 ListAppender 挂到指定类的 logger 上，用于断言日志内容（用完必须摘掉）。 */
    private static ListAppender<ILoggingEvent> attachAppender(Class<?> type) {
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(type);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private static void detachAppender(Class<?> type, ListAppender<ILoggingEvent> appender) {
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(type);
        logger.detachAppender(appender);
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

    /** 一定超时的假工具：循环等待到期限；被 interrupt 时立刻退出，避免测试留下长任务线程。 */
    private static ToolCallback sleepingDelegate(String name, long sleepMs) {
        ToolCallback mock = Mockito.mock(ToolCallback.class);
        Mockito.when(mock.getToolDefinition()).thenReturn(definition(name));
        Mockito.when(mock.call(any(), any())).thenAnswer(invocation -> {
            long deadline = System.nanoTime() + sleepMs * 1_000_000L;
            while (System.nanoTime() < deadline) {
                Thread.sleep(5L);
            }
            return "迟到的结果";
        });
        return mock;
    }
}
