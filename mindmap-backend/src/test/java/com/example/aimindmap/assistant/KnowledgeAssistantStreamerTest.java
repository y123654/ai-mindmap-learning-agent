package com.example.aimindmap.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.aimindmap.agentruntime.AgentIntent;
import com.example.aimindmap.agentruntime.AgentIntentClassifier;
import com.example.aimindmap.agentruntime.AgentPlan;
import com.example.aimindmap.agentruntime.AgentPlanner;
import com.example.aimindmap.agentruntime.AgentRuntimeProperties;
import com.example.aimindmap.agentruntime.AnswerLeakChecker;
import com.example.aimindmap.agentruntime.EvidenceItem;
import com.example.aimindmap.agentruntime.EvidenceLedger;
import com.example.aimindmap.agentruntime.EvidenceLedgerFactory;
import com.example.aimindmap.agentruntime.EvidenceSanitizer;
import com.example.aimindmap.agentruntime.PlanExecutor;
import com.example.aimindmap.agenttracing.AgentCitationRepository;
import com.example.aimindmap.agenttracing.AgentEvidenceRepository;
import com.example.aimindmap.agenttracing.AgentMetrics;
import com.example.aimindmap.agenttracing.AgentRunAbortException;
import com.example.aimindmap.agenttracing.AgentRunEntity;
import com.example.aimindmap.agenttracing.AgentRunRepository;
import com.example.aimindmap.agenttracing.AgentRunStatus;
import com.example.aimindmap.agenttracing.AgentRunSupervisor;
import com.example.aimindmap.agenttracing.AgentRunTools;
import com.example.aimindmap.agenttracing.AgentStepEntity;
import com.example.aimindmap.agenttracing.AgentStepRepository;
import com.example.aimindmap.agenttracing.AgentToolCallEvent;
import com.example.aimindmap.agenttracing.AgentToolCallGuard;
import com.example.aimindmap.agenttracing.AgentToolCallRepository;
import com.example.aimindmap.agenttracing.AgentToolCallbackFactory;
import com.example.aimindmap.agenttracing.AgentToolContextKeys;
import com.example.aimindmap.agenttracing.AgentTracingProperties;
import com.example.aimindmap.agenttracing.AgentTracingService;
import com.example.aimindmap.agenttracing.PerRunToolResultCache;
import com.example.aimindmap.agenttracing.TracingToolCallback;
import com.example.aimindmap.agenttools.LearningResourceTools;
import com.example.aimindmap.ai.AiChatMessage;
import com.example.aimindmap.ai.AiGateway;
import com.example.aimindmap.ai.AiJsonRequest;
import com.example.aimindmap.ai.AiJsonResult;
import com.example.aimindmap.ai.AiProperties;
import com.example.aimindmap.ai.AiUsage;
import com.example.aimindmap.auth.ExternalIdCodec;
import com.example.aimindmap.vectorindex.VectorResourceType;
import com.example.aimindmap.vectorindex.VectorSearchService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import reactor.core.publisher.Flux;

class KnowledgeAssistantStreamerTest {

    private KnowledgeAgentService agentService;
    private KnowledgeAssistantService assistantService;
    private AgentTracingService tracingService;
    private AgentToolCallbackFactory toolCallbackFactory;
    private KnowledgeAssistantStreamer streamer;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        assistantService = mock(KnowledgeAssistantService.class);
        tracingService = mock(AgentTracingService.class);
        toolCallbackFactory = mock(AgentToolCallbackFactory.class);
        AgentRunEntity run = new AgentRunEntity();
        setId(run, 99L);
        when(tracingService.startRun(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(run);
        when(toolCallbackFactory.createForRun(any(), any())).thenReturn(new AgentRunTools(
                List.of(), new AgentToolCallGuard(3), new PerRunToolResultCache(objectMapper)));
        when(assistantService.completeAssistantMessage(any(), any(), any(), any(), any()))
                .thenReturn(new KnowledgeAssistantDtos.StreamCompleted(
                        new KnowledgeAssistantDtos.MessageResponse(
                                "m1", "assistant", "答案", "deepseek", "deepseek-chat", null),
                        new KnowledgeAssistantDtos.SessionSummaryResponse("s1", "标题", null, null),
                        null));
    }

    private void createStreamer(ChatModel chatModel) {
        createStreamer(chatModel, new EvidenceLedgerFactory());
    }

    private void createStreamer(ChatModel chatModel, EvidenceLedgerFactory ledgerFactory) {
        agentService = new KnowledgeAgentService(chatModel, new AiProperties());
        streamer = new KnowledgeAssistantStreamer(
                agentService, assistantService, objectMapper, tracingService,
                toolCallbackFactory, new AgentRunSupervisor(), new AgentTracingProperties(),
                ledgerFactory);
    }

    /** 让本次 run 的工具调用数 &gt; 0（知识类判定：agent_tool_calls &gt; 0）。 */
    private void stubKnowledgeRunWithToolCalls() {
        AgentToolCallGuard guard = new AgentToolCallGuard(3);
        guard.tryAcquire();
        when(toolCallbackFactory.createForRun(any(), any())).thenReturn(new AgentRunTools(
                List.of(), guard, new PerRunToolResultCache(objectMapper)));
    }

    private static EvidenceLedger ledgerWithOneEvidence() {
        EvidenceLedger ledger = new EvidenceLedger(99L);
        ledger.register("document_chunk", "42", 0, 0.71d, "操作系统第三章", "进程是资源分配的基本单位");
        return ledger;
    }

    private static EvidenceLedgerFactory ledgerFactoryReturning(EvidenceLedger ledger) {
        EvidenceLedgerFactory factory = mock(EvidenceLedgerFactory.class);
        when(factory.create(any())).thenReturn(ledger);
        return factory;
    }

    private KnowledgeAssistantService.ChatStreamContext context() {
        return new KnowledgeAssistantService.ChatStreamContext(
                7L, 90L, "系统提示词",
                List.of(new AiChatMessage("user", "你好")),
                2000, 123, 0.3d);
    }

    /** P1-E3b：指定用户提问原文的上下文（用于“回声排除”用例）。 */
    private KnowledgeAssistantService.ChatStreamContext contextWithUserMessage(String userMessage) {
        return new KnowledgeAssistantService.ChatStreamContext(
                7L, 90L, "系统提示词",
                List.of(new AiChatMessage("user", userMessage)),
                2000, 123, 0.3d);
    }

    @Test
    void completedEventCarriesUsageFromLastFrameAndPersistsPromptTokens() throws Exception {
        ChatResponse usageFrame = new ChatResponse(
                List.of(new Generation(new AssistantMessage(""))),
                ChatResponseMetadata.builder().usage(new DefaultUsage(123, 45)).build());
        createStreamer(new RecordingChatModel(
                prompt -> Flux.just(chunk("你好"), chunk("，我是助手"), usageFrame)));

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        streamer.stream(context(), output);

        String text = output.toString(StandardCharsets.UTF_8);
        assertThat(text).contains("event: delta").contains("event: completed");
        JsonNode completed = lastDataJson(text);
        assertThat(completed.get("message").get("content").asText()).isEqualTo("答案");
        assertThat(completed.get("usage").get("estimatedInputTokens").asInt()).isEqualTo(123);
        assertThat(completed.get("usage").get("promptTokens").asInt()).isEqualTo(123);
        assertThat(completed.get("usage").get("completionTokens").asInt()).isEqualTo(45);

        // prompt_chars 仍落库（系统提示词 5 字符 + user 角色 4 + 正文 2 = 11），估算 token 一并传入
        verify(tracingService).startRun(eq(7L), eq(90L), eq("deepseek-chat"),
                eq("你好"), eq(11), eq(123), isNull());
        verify(tracingService).succeedRun(eq(99L), anyString(), eq(0), eq(0));
        verify(tracingService, never()).failRun(any(), any(), any(), anyInt(), anyInt());
    }

    @Test
    void missingUsageInLastFrameDegradesGracefullyAndCompletesNormally() throws Exception {
        createStreamer(new RecordingChatModel(
                prompt -> Flux.just(chunk("你好"), chunk("，我是助手"))));

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        streamer.stream(context(), output);

        String text = output.toString(StandardCharsets.UTF_8);
        JsonNode completed = lastDataJson(text);
        assertThat(completed.get("message").get("content").asText()).isEqualTo("答案");
        assertThat(completed.get("usage").isNull()).isTrue();
        verify(tracingService).succeedRun(eq(99L), anyString(), eq(0), eq(0));
        verify(tracingService, never()).failRun(any(), any(), any(), anyInt(), anyInt());
    }

    @Test
    void knowledgeAnswerWithoutCitationAppendsNoticeExactlyOnce() throws Exception {
        stubKnowledgeRunWithToolCalls();
        createStreamer(new RecordingChatModel(
                prompt -> Flux.just(chunk("进程是资源分配的基本单位。"))));

        ArgumentCaptor<String> persistedContent = ArgumentCaptor.forClass(String.class);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        streamer.stream(context(), output);

        String text = output.toString(StandardCharsets.UTF_8);
        assertThat(countOccurrences(text, "本次回答未能关联到你的笔记内容")).isEqualTo(1);
        assertThat(text).contains("event: delta");
        verify(assistantService).completeAssistantMessage(eq(7L), eq(90L),
                persistedContent.capture(), any(), any());
        assertThat(countOccurrences(persistedContent.getValue(), "本次回答未能关联到你的笔记内容"))
                .isEqualTo(1);
        assertThat(persistedContent.getValue()).endsWith(KnowledgeAssistantStreamer.UNGROUNDED_NOTICE);
        verify(tracingService).succeedRun(eq(99L), eq(persistedContent.getValue()), eq(1), eq(0));
    }

    @Test
    void knowledgeAnswerWithValidCitationDoesNotAppendNotice() throws Exception {
        stubKnowledgeRunWithToolCalls();
        createStreamer(new RecordingChatModel(
                        prompt -> Flux.just(chunk("进程是资源分配的基本单位。[E1]"))),
                ledgerFactoryReturning(ledgerWithOneEvidence()));

        ArgumentCaptor<String> persistedContent = ArgumentCaptor.forClass(String.class);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        streamer.stream(context(), output);

        String text = output.toString(StandardCharsets.UTF_8);
        assertThat(text).doesNotContain("本次回答未能关联到你的笔记内容");
        verify(assistantService).completeAssistantMessage(eq(7L), eq(90L),
                persistedContent.capture(), any(), any());
        assertThat(persistedContent.getValue()).isEqualTo("进程是资源分配的基本单位。[E1]");
    }

    @Test
    void chitchatWithoutToolCallsDoesNotAppendNotice() throws Exception {
        createStreamer(new RecordingChatModel(prompt -> Flux.just(chunk("你好，我是学习助手。"))));

        ArgumentCaptor<String> persistedContent = ArgumentCaptor.forClass(String.class);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        streamer.stream(context(), output);

        String text = output.toString(StandardCharsets.UTF_8);
        assertThat(text).doesNotContain("本次回答未能关联到你的笔记内容");
        verify(assistantService).completeAssistantMessage(eq(7L), eq(90L),
                persistedContent.capture(), any(), any());
        assertThat(persistedContent.getValue()).isEqualTo("你好，我是学习助手。");
    }

    @Test
    void noticeIsNotAppendedTwiceWhenModelAlreadySaidIt() throws Exception {
        stubKnowledgeRunWithToolCalls();
        String answerWithNotice = "你的笔记中暂时没有相关内容。"
                + "\n\n> 注：本次回答未能关联到你的笔记内容，请谨慎参考。";
        createStreamer(new RecordingChatModel(prompt -> Flux.just(chunk(answerWithNotice))));

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        streamer.stream(context(), output);

        String text = output.toString(StandardCharsets.UTF_8);
        assertThat(countOccurrences(text, "本次回答未能关联到你的笔记内容")).isEqualTo(1);
    }

    private static int countOccurrences(String text, String target) {
        int count = 0;
        int index = text.indexOf(target);
        while (index >= 0) {
            count++;
            index = text.indexOf(target, index + target.length());
        }
        return count;
    }

    private JsonNode lastDataJson(String sseOutput) throws Exception {
        List<String> dataLines = sseOutput.lines()
                .filter(line -> line.startsWith("data: "))
                .map(line -> line.substring("data: ".length()))
                .toList();
        assertThat(dataLines).isNotEmpty();
        return objectMapper.readTree(dataLines.get(dataLines.size() - 1));
    }

    @Test
    void persistsEvidenceAndCitationSummaryForKnowledgeRun() {
        stubKnowledgeRunWithToolCalls();
        createStreamer(new RecordingChatModel(
                        prompt -> Flux.just(chunk("进程是资源分配的基本单位。[E1]"))),
                ledgerFactoryReturning(ledgerWithOneEvidence()));

        streamer.stream(context(), new ByteArrayOutputStream());

        verify(tracingService).recordCitationSummary(99L, 1, true);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<com.example.aimindmap.agentruntime.EvidenceItem>> evidenceCaptor =
                ArgumentCaptor.forClass(List.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<com.example.aimindmap.agentruntime.CitationCheckResult.Citation>>
                citationCaptor = ArgumentCaptor.forClass(List.class);
        verify(tracingService).recordEvidenceAndCitations(eq(99L), evidenceCaptor.capture(),
                citationCaptor.capture());
        assertThat(evidenceCaptor.getValue()).singleElement()
                .satisfies(item -> assertThat(item.key()).isEqualTo("E1"));
        assertThat(citationCaptor.getValue()).containsExactly(
                new com.example.aimindmap.agentruntime.CitationCheckResult.Citation("E1", true));
    }

    @Test
    void evidencePersistenceFailureDoesNotBreakAnswer() {
        stubKnowledgeRunWithToolCalls();
        org.mockito.Mockito.doThrow(new RuntimeException("数据库写入失败"))
                .when(tracingService).recordEvidenceAndCitations(any(), any(), any());
        createStreamer(new RecordingChatModel(
                        prompt -> Flux.just(chunk("进程是资源分配的基本单位。[E1]"))),
                ledgerFactoryReturning(ledgerWithOneEvidence()));

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        streamer.stream(context(), output);

        String text = output.toString(StandardCharsets.UTF_8);
        assertThat(text).contains("event: completed").doesNotContain("event: error");
        verify(tracingService, never()).failRun(any(), any(), any(), anyInt(), anyInt());
    }

    @Test
    void citationSummaryFailureDoesNotBreakAnswer() {
        stubKnowledgeRunWithToolCalls();
        org.mockito.Mockito.doThrow(new RuntimeException("数据库写入失败"))
                .when(tracingService).recordCitationSummary(any(), anyInt(),
                        org.mockito.ArgumentMatchers.anyBoolean());
        createStreamer(new RecordingChatModel(
                        prompt -> Flux.just(chunk("进程是资源分配的基本单位。[E1]"))),
                ledgerFactoryReturning(ledgerWithOneEvidence()));

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        streamer.stream(context(), output);

        String text = output.toString(StandardCharsets.UTF_8);
        assertThat(text).contains("event: completed").doesNotContain("event: error");
        verify(tracingService, never()).failRun(any(), any(), any(), anyInt(), anyInt());
    }

    private static ChatResponse chunk(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    // ===== P0-2：tool_call 事件 =====

    @Test
    void toolCallEventsAreWrittenBeforeFirstDelta() throws Exception {
        AgentToolCallGuard guard = new AgentToolCallGuard(3);
        stubRunWithTool(guard, "search_my_documents", "片段内容");
        createStreamer(modelInvokingTool("search_my_documents",
                "{\"query\":\"时间片轮转\"}", "时间片轮转是一种调度算法。"));

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        streamer.stream(context(), output);

        String text = output.toString(StandardCharsets.UTF_8);
        int toolIndex = text.indexOf("event: tool_call");
        int deltaIndex = text.indexOf("event: delta");
        assertThat(toolIndex).isGreaterThanOrEqualTo(0).isLessThan(deltaIndex);
        assertThat(countOccurrences(text, "event: tool_call")).isEqualTo(2);
        assertThat(text).contains("event: completed").doesNotContain("event: error");
        assertThat(text).doesNotContain("888901");

        List<JsonNode> payloads = toolCallPayloads(text);
        assertThat(payloads).hasSize(2);
        JsonNode start = payloads.get(0);
        JsonNode succeed = payloads.get(1);
        assertThat(start.get("phase").asText()).isEqualTo("start");
        assertThat(start.get("tool").asText()).isEqualTo("search_my_documents");
        assertThat(start.get("label").asText()).isEqualTo("检索课程笔记");
        assertThat(start.get("detail").asText()).isEqualTo("时间片轮转");
        assertThat(succeed.get("phase").asText()).isEqualTo("succeed");
        assertThat(succeed.get("seq").asLong()).isEqualTo(start.get("seq").asLong());
        assertThat(succeed.get("fromCache").asBoolean()).isFalse();
        assertThat(succeed.get("durationMs").asLong()).isGreaterThanOrEqualTo(0L);
    }

    @Test
    void toolEventPushFailureDoesNotBreakAnswer() throws Exception {
        AgentToolCallGuard guard = new AgentToolCallGuard(3);
        stubRunWithTool(guard, "read_my_document", "笔记正文");
        createStreamer(modelInvokingTool("read_my_document",
                "{\"documentId\":\"888901\"}", "这是回答正文。"));

        // 只让 tool_call 事件写入失败，delta / completed 仍可正常写入
        FailOnToolCallOutputStream output = new FailOnToolCallOutputStream();
        streamer.stream(context(), output);

        String text = output.toString(StandardCharsets.UTF_8);
        assertThat(output.toolCallWriteAttempts).isGreaterThan(0);
        assertThat(text).contains("event: delta").contains("event: completed")
                .doesNotContain("event: error");
        verify(tracingService, never()).failRun(any(), any(), any(), anyInt(), anyInt());
    }

    @Test
    void toolCallSinkIncrementsSeqAndSwallowsWriteFailure() {
        FailOnToolCallOutputStream output = new FailOnToolCallOutputStream();
        KnowledgeAssistantStreamer.ToolCallSseSink sink =
                new KnowledgeAssistantStreamer.ToolCallSseSink(objectMapper, output, new Object());

        assertThat(sink.nextSeq()).isEqualTo(1);
        assertThat(sink.nextSeq()).isEqualTo(2);
        sink.emit(AgentToolCallEvent.start(1L, "read_my_document", "读取笔记全文", "读取笔记全文"));
        sink.emit(AgentToolCallEvent.failed(1L, "read_my_document", "读取笔记全文",
                "读取笔记全文", "AGENT_RESOURCE_DENIED"));

        assertThat(output.toString(StandardCharsets.UTF_8)).isEmpty();
        assertThat(output.toolCallWriteAttempts).isEqualTo(2);
    }

    /** 让本次 run 的工具列表里有一个真实 TracingToolCallback（raw 为 mock）。 */
    private void stubRunWithTool(AgentToolCallGuard guard, String toolName, String result) {
        ToolCallback raw = mock(ToolCallback.class);
        when(raw.getToolDefinition()).thenReturn(
                ToolDefinition.builder().name(toolName).description("测试")
                        .inputSchema("{\"type\":\"object\"}").build());
        when(raw.call(any(), any())).thenReturn(result);
        ToolCallback decorated = new TracingToolCallback(
                raw, tracingService, guard, new AgentTracingProperties());
        when(toolCallbackFactory.createForRun(any(), any())).thenReturn(new AgentRunTools(
                List.of(decorated), guard, new PerRunToolResultCache(objectMapper)));
    }

    /** 模拟模型调用工具后再产出正文：工具在 Flux 返回前同步执行，事件先于 delta。 */
    private static ChatModel modelInvokingTool(String toolName, String arguments, String answer) {
        return new ChatModel() {
            @Override
            public Flux<ChatResponse> stream(Prompt prompt) {
                invokeTool(prompt, toolName, arguments);
                return Flux.just(chunk(answer));
            }

            @Override
            public ChatResponse call(Prompt prompt) {
                throw new UnsupportedOperationException("本测试只覆盖流式路径");
            }
        };
    }

    private static void invokeTool(Prompt prompt, String toolName, String arguments) {
        if (!(prompt.getOptions() instanceof ToolCallingChatOptions options)) {
            throw new IllegalStateException("ChatClient 未把工具上下文传给模型");
        }
        Map<String, Object> context = options.getToolContext() == null
                ? Map.of() : options.getToolContext();
        for (ToolCallback callback : options.getToolCallbacks()) {
            if (toolName.equals(callback.getToolDefinition().name())) {
                callback.call(arguments, new ToolContext(context));
            }
        }
    }

    private List<JsonNode> toolCallPayloads(String sseOutput) throws Exception {
        List<JsonNode> payloads = new ArrayList<>();
        for (String block : sseOutput.split("\n\n")) {
            if (!block.contains("event: tool_call")) {
                continue;
            }
            for (String line : block.lines().toList()) {
                if (line.startsWith("data: ")) {
                    payloads.add(objectMapper.readTree(line.substring("data: ".length())));
                }
            }
        }
        return payloads;
    }

    /** 对 tool_call 事件写入抛异常的输出流，用于验证事件推送失败不影响回答。 */
    private static final class FailOnToolCallOutputStream extends ByteArrayOutputStream {

        private int toolCallWriteAttempts;

        @Override
        public synchronized void write(byte[] b, int off, int len) {
            String chunk = new String(b, off, len, StandardCharsets.UTF_8);
            if (chunk.contains("event: tool_call")) {
                toolCallWriteAttempts++;
                throw new UncheckedIOException(new IOException("模拟 SSE 写入失败"));
            }
            super.write(b, off, len);
        }
    }

    private static void setId(AgentRunEntity entity, Long id) {
        try {
            var field = AgentRunEntity.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(entity, id);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static final class RecordingChatModel implements ChatModel {

        private final Function<Prompt, Flux<ChatResponse>> handler;

        private RecordingChatModel(Function<Prompt, Flux<ChatResponse>> handler) {
            this.handler = handler;
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return handler.apply(prompt);
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            throw new UnsupportedOperationException("本测试只覆盖流式路径");
        }
    }

    // ===== P0-E1：真实 token/成本落库、completed.runId、四类 Micrometer 指标 =====

    private SimpleMeterRegistry registry;
    private ExternalIdCodec runIdCodec;

    /** 生产装配形态：带 AgentMetrics（SimpleMeterRegistry）与 ExternalIdCodec 的 10 参构造。 */
    private void createObservableStreamer(ChatModel chatModel, EvidenceLedgerFactory ledgerFactory) {
        registry = new SimpleMeterRegistry();
        runIdCodec = new ExternalIdCodec("streamer-observability-test-salt");
        agentService = new KnowledgeAgentService(chatModel, new AiProperties());
        streamer = new KnowledgeAssistantStreamer(
                agentService, assistantService, objectMapper, tracingService, toolCallbackFactory,
                new AgentRunSupervisor(), new AgentTracingProperties(), ledgerFactory,
                new AgentMetrics(registry), runIdCodec);
    }

    @Test
    void completedEventCarriesEncodedRunId() throws Exception {
        createObservableStreamer(new RecordingChatModel(prompt -> Flux.just(chunk("你好"))),
                new EvidenceLedgerFactory());

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        streamer.stream(context(), output);

        JsonNode completed = lastDataJson(output.toString(StandardCharsets.UTF_8));
        String runId = completed.get("runId").asText();
        assertThat(runId).isEqualTo(runIdCodec.encode(99L));
        assertThat(runIdCodec.decode(runId)).isEqualTo(99L);
        // 旧字段不变
        assertThat(completed.get("message").get("content").asText()).isEqualTo("答案");
        assertThat(completed.get("session").get("title").asText()).isEqualTo("标题");
    }

    @Test
    void realUsageIsPersistedToRunAfterCompletion() throws Exception {
        ChatResponse usageFrame = new ChatResponse(
                List.of(new Generation(new AssistantMessage(""))),
                ChatResponseMetadata.builder().usage(new DefaultUsage(123, 45)).build());
        createObservableStreamer(new RecordingChatModel(
                prompt -> Flux.just(chunk("你好"), usageFrame)), new EvidenceLedgerFactory());

        streamer.stream(context(), new ByteArrayOutputStream());

        verify(tracingService).recordTokenUsage(99L, 123, 45);
        // run 落库成功后才写真实用量（失败路径不写）
        verify(tracingService).succeedRun(eq(99L), anyString(), eq(0), eq(0));
    }

    @Test
    void missingUsageSkipsTokenUsagePersistSoCostStaysNull() {
        createObservableStreamer(new RecordingChatModel(
                prompt -> Flux.just(chunk("你好，我是学习助手。"))), new EvidenceLedgerFactory());

        streamer.stream(context(), new ByteArrayOutputStream());

        verify(tracingService, never()).recordTokenUsage(any(), any(), any());
    }

    @Test
    void tokenUsagePersistFailureDoesNotBreakAnswer() {
        org.mockito.Mockito.doThrow(new RuntimeException("数据库写入失败"))
                .when(tracingService).recordTokenUsage(any(), any(), any());
        createObservableStreamer(new RecordingChatModel(
                prompt -> Flux.just(chunk("你好，我是学习助手。"))), new EvidenceLedgerFactory());

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        streamer.stream(context(), output);

        String text = output.toString(StandardCharsets.UTF_8);
        assertThat(text).contains("event: completed").doesNotContain("event: error");
        verify(tracingService, never()).failRun(any(), any(), any(), anyInt(), anyInt());
    }

    @Test
    void successRunRecordsDurationGroundedAndCoverageOnce() {
        stubKnowledgeRunWithToolCalls();
        createObservableStreamer(new RecordingChatModel(
                        prompt -> Flux.just(chunk("进程是资源分配的基本单位。[E1]"))),
                ledgerFactoryReturning(ledgerWithOneEvidence()));

        streamer.stream(context(), new ByteArrayOutputStream());

        Timer successTimer = registry.get(AgentMetrics.RUN_DURATION)
                .tag(AgentMetrics.TAG_OUTCOME, AgentMetrics.OUTCOME_SUCCESS).timer();
        assertThat(successTimer).isNotNull();
        assertThat(successTimer.count()).isEqualTo(1L);
        assertThat(registry.find(AgentMetrics.RUN_DURATION)
                .tag(AgentMetrics.TAG_OUTCOME, AgentMetrics.OUTCOME_ERROR).timer()).isNull();

        Counter grounded = registry.get(AgentMetrics.GROUNDED)
                .tag(AgentMetrics.TAG_GROUNDED, "true").counter();
        assertThat(grounded.count()).isEqualTo(1d);

        DistributionSummary coverage = registry.get(AgentMetrics.CITATION_COVERAGE).summary();
        assertThat(coverage.count()).isEqualTo(1L);
        assertThat(coverage.totalAmount()).isGreaterThan(0d).isLessThanOrEqualTo(1d);
    }

    @Test
    void failedRunRecordsErrorDurationAndNoQualityMetrics() {
        createObservableStreamer(new RecordingChatModel(
                prompt -> Flux.error(new IllegalStateException("模拟模型不可用"))),
                new EvidenceLedgerFactory());

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        streamer.stream(context(), output);

        assertThat(output.toString(StandardCharsets.UTF_8)).contains("event: error");
        verify(tracingService).failRun(eq(99L), anyString(), anyString(), anyInt(), anyInt());

        Timer errorTimer = registry.get(AgentMetrics.RUN_DURATION)
                .tag(AgentMetrics.TAG_OUTCOME, AgentMetrics.OUTCOME_ERROR).timer();
        assertThat(errorTimer).isNotNull();
        assertThat(errorTimer.count()).isEqualTo(1L);
        assertThat(registry.find(AgentMetrics.RUN_DURATION)
                .tag(AgentMetrics.TAG_OUTCOME, AgentMetrics.OUTCOME_SUCCESS).timer()).isNull();
        // 失败 run 没有引用校验结果，不写 grounded/coverage，避免污染质量指标
        assertThat(registry.find(AgentMetrics.GROUNDED).counters()).isEmpty();
        assertThat(registry.find(AgentMetrics.CITATION_COVERAGE).summary()).isNull();
    }

    // ===== P1-B1：意图接线（只写 agent_runs.intent，不改回答行为） =====

    /** 生产装配形态 + P1 意图分类器：12 参构造。 */
    private void createRuntimeStreamer(ChatModel chatModel, AgentIntentClassifier classifier,
                                       AgentRuntimeProperties runtimeProperties) {
        createRuntimeStreamer(chatModel, classifier, runtimeProperties, new EvidenceLedgerFactory());
    }

    private void createRuntimeStreamer(ChatModel chatModel, AgentIntentClassifier classifier,
                                       AgentRuntimeProperties runtimeProperties,
                                       EvidenceLedgerFactory ledgerFactory) {
        agentService = new KnowledgeAgentService(chatModel, new AiProperties());
        streamer = new KnowledgeAssistantStreamer(
                agentService, assistantService, objectMapper, tracingService, toolCallbackFactory,
                new AgentRunSupervisor(), new AgentTracingProperties(), ledgerFactory,
                AgentMetrics.noop(), null, classifier, runtimeProperties);
    }

    /** P1-B2：13 参构造（带学习资源工具），用于零调用补检用例。 */
    private void createFallbackStreamer(ChatModel chatModel, AgentIntentClassifier classifier,
                                        AgentRuntimeProperties runtimeProperties,
                                        LearningResourceTools learningResourceTools,
                                        EvidenceLedgerFactory ledgerFactory) {
        createFallbackStreamer(new KnowledgeAgentService(chatModel, new AiProperties()),
                classifier, runtimeProperties, learningResourceTools, ledgerFactory);
    }

    private void createFallbackStreamer(KnowledgeAgentService agentService,
                                        AgentIntentClassifier classifier,
                                        AgentRuntimeProperties runtimeProperties,
                                        LearningResourceTools learningResourceTools,
                                        EvidenceLedgerFactory ledgerFactory) {
        this.agentService = agentService;
        streamer = new KnowledgeAssistantStreamer(
                agentService, assistantService, objectMapper, tracingService, toolCallbackFactory,
                new AgentRunSupervisor(), new AgentTracingProperties(), ledgerFactory,
                AgentMetrics.noop(), null, classifier, runtimeProperties, learningResourceTools);
    }

    private static AgentRuntimeProperties enabledRuntime() {
        AgentRuntimeProperties properties = new AgentRuntimeProperties();
        properties.setEnabled(true);
        return properties;
    }

    private static ChatResponse usageChunk(int promptTokens, int completionTokens) {
        return new ChatResponse(
                List.of(new Generation(new AssistantMessage(""))),
                ChatResponseMetadata.builder()
                        .usage(new DefaultUsage(promptTokens, completionTokens)).build());
    }

    /** 固定的“两段 delta”回答，便于两次运行逐字节比对 SSE 输出。 */
    private static ChatModel greetingModel() {
        return new RecordingChatModel(prompt -> Flux.just(chunk("你好"), chunk("，我是学习助手。")));
    }

    @Test
    void runtimeSwitchOffNeverCallsClassifierAndKeepsIntentNull() throws Exception {
        // 基线：未接线（8 参构造）的现有行为
        createStreamer(greetingModel());
        ByteArrayOutputStream baseline = new ByteArrayOutputStream();
        streamer.stream(context(), baseline);

        // 接线后但开关关（默认 false）：分类器一次都不该被调用
        AgentIntentClassifier classifier = mock(AgentIntentClassifier.class);
        createRuntimeStreamer(greetingModel(), classifier, new AgentRuntimeProperties());
        ByteArrayOutputStream wired = new ByteArrayOutputStream();
        streamer.stream(context(), wired);

        assertThat(wired.toString(StandardCharsets.UTF_8))
                .as("开关关时 SSE 事件序列必须与未接线时逐字节一致")
                .isEqualTo(baseline.toString(StandardCharsets.UTF_8));
        verifyNoInteractions(classifier);

        ArgumentCaptor<AgentIntent> intentCaptor = ArgumentCaptor.forClass(AgentIntent.class);
        verify(tracingService, times(2)).startRun(eq(7L), eq(90L), eq("deepseek-chat"),
                eq("你好"), eq(11), eq(123), intentCaptor.capture());
        assertThat(intentCaptor.getAllValues()).containsOnlyNulls();
    }

    @Test
    void runtimeSwitchOnPersistsMultiHopWithoutChangingAnswer() throws Exception {
        // 用“已引用笔记”的回答做基线：开关开时 MULTI_HOP 也属知识类（P1-B2 §1.3），
        // 有合法引用 → 不追加无依据提示，因此回答与开关关时仍应逐字节一致。
        ChatModel citedModel = new RecordingChatModel(
                prompt -> Flux.just(chunk("进程是资源分配的基本单位。[E1]")));
        createStreamer(citedModel, ledgerFactoryReturning(ledgerWithOneEvidence()));
        ByteArrayOutputStream baseline = new ByteArrayOutputStream();
        streamer.stream(context(), baseline);

        // 开关开：分类器判 MULTI_HOP
        AgentIntentClassifier classifier = mock(AgentIntentClassifier.class);
        when(classifier.classify("你好")).thenReturn(AgentIntent.MULTI_HOP);
        AgentRuntimeProperties enabled = new AgentRuntimeProperties();
        enabled.setEnabled(true);
        createRuntimeStreamer(citedModel, classifier, enabled,
                ledgerFactoryReturning(ledgerWithOneEvidence()));
        ByteArrayOutputStream wired = new ByteArrayOutputStream();
        streamer.stream(context(), wired);

        // 1) 意图确实写进 startRun（最终由 AgentTracingService 落到 agent_runs.intent）
        verify(classifier).classify("你好");
        verify(tracingService).startRun(eq(7L), eq(90L), eq("deepseek-chat"), eq("你好"),
                eq(11), eq(123), eq(AgentIntent.MULTI_HOP));

        // 2) 回答、SSE 事件与成功收尾和开关关时完全一致
        assertThat(wired.toString(StandardCharsets.UTF_8))
                .as("开关开只多写 intent，SSE 输出必须与开关关逐字节一致")
                .isEqualTo(baseline.toString(StandardCharsets.UTF_8));
        verify(tracingService, times(2)).succeedRun(eq(99L), eq("进程是资源分配的基本单位。[E1]"),
                eq(0), eq(0));
        verify(tracingService, never()).failRun(any(), any(), any(), anyInt(), anyInt());
        // MULTI_HOP 不触发零调用补检（只有 RETRIEVE/TASK 触发）
        verify(tracingService, never()).incrementRepairRound(anyLong());
    }

    @Test
    void classifierFailureDegradesToNullIntentAndAnswerStillCompletes() {
        AgentIntentClassifier classifier = mock(AgentIntentClassifier.class);
        when(classifier.classify(anyString())).thenThrow(new RuntimeException("模拟分类器故障"));
        AgentRuntimeProperties enabled = new AgentRuntimeProperties();
        enabled.setEnabled(true);
        createRuntimeStreamer(greetingModel(), classifier, enabled);

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        streamer.stream(context(), output);

        String text = output.toString(StandardCharsets.UTF_8);
        assertThat(text).contains("event: completed").doesNotContain("event: error");

        ArgumentCaptor<AgentIntent> intentCaptor = ArgumentCaptor.forClass(AgentIntent.class);
        verify(tracingService).startRun(eq(7L), eq(90L), eq("deepseek-chat"), eq("你好"),
                eq(11), eq(123), intentCaptor.capture());
        assertThat(intentCaptor.getValue()).isNull();
        verify(tracingService).succeedRun(eq(99L), eq("你好，我是学习助手。"), eq(0), eq(0));
        // 分类失败绝不能让本轮判失败
        verify(tracingService, never()).failRun(any(), any(), any(), anyInt(), anyInt());
    }

    // ===== P1-B2：RETRIEVE/TASK 零调用补检 =====

    /** 按顺序返回多轮流式响应（第一轮、第二轮…）的 mock AgentService。 */
    private static KnowledgeAgentService mockAgentService(List<Flux<ChatResponse>> rounds) {
        KnowledgeAgentService agent = mock(KnowledgeAgentService.class);
        when(agent.model()).thenReturn("deepseek-chat");
        when(agent.provider()).thenReturn("deepseek");
        java.util.concurrent.atomic.AtomicInteger index =
                new java.util.concurrent.atomic.AtomicInteger();
        when(agent.streamAnswer(anyString(), anyList(), anyInt(), anyDouble(), anyList(), anyMap()))
                .thenAnswer(invocation -> rounds.get(
                        Math.min(index.getAndIncrement(), rounds.size() - 1)));
        return agent;
    }

    /** 模拟 search_my_documents 命中 1 条：登记 E1 证据并返回带编号的资料块。 */
    private static LearningResourceTools retrievalToolReturningOneEvidence() {
        LearningResourceTools tools = mock(LearningResourceTools.class);
        when(tools.searchMyDocuments(anyString(), any(ToolContext.class))).thenAnswer(invocation -> {
            ToolContext toolContext = invocation.getArgument(1);
            EvidenceLedger ledger = (EvidenceLedger) toolContext.getContext()
                    .get(AgentToolContextKeys.EVIDENCE_LEDGER);
            ledger.register("document_chunk", "42", 0, 0.71d, "操作系统第三章",
                    "进程是资源分配的基本单位");
            return "[E1] [来源类型: document_chunk, 资源ID: 42]\n内容：进程是资源分配的基本单位";
        });
        return tools;
    }

    @Test
    @SuppressWarnings("unchecked")
    void zeroToolCallRetrieveWithSwitchOnTriggersFallbackOnce() {
        // 工具已装配但模型一次都没调用 → guard.acquiredCount() == 0
        stubRunWithTool(new AgentToolCallGuard(3), "search_my_documents", "未被调用");
        AgentIntentClassifier classifier = mock(AgentIntentClassifier.class);
        when(classifier.classify("你好")).thenReturn(AgentIntent.RETRIEVE);
        LearningResourceTools resourceTools = retrievalToolReturningOneEvidence();
        KnowledgeAgentService agent = mockAgentService(List.of(
                Flux.just(chunk("第一段回答。"), usageChunk(100, 20)),
                Flux.just(chunk("[E1] 第二段回答。"), usageChunk(200, 30))));
        createFallbackStreamer(agent, classifier, enabledRuntime(), resourceTools,
                new EvidenceLedgerFactory());

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        streamer.stream(context(), output);

        String sse = output.toString(StandardCharsets.UTF_8);
        assertThat(sse).contains("event: completed").doesNotContain("event: error");
        assertThat(sse).contains("根据你的笔记补充");

        // 最终持久化文本 = 第一段 + 固定分隔标记 + 第二段（用户看到什么、库里就存什么）
        String expected = "第一段回答。" + KnowledgeAssistantStreamer.FALLBACK_SEPARATOR
                + "[E1] 第二段回答。";
        ArgumentCaptor<String> persisted = ArgumentCaptor.forClass(String.class);
        verify(assistantService).completeAssistantMessage(eq(7L), eq(90L), persisted.capture(),
                any(), any());
        assertThat(persisted.getValue()).isEqualTo(expected);
        verify(tracingService).succeedRun(eq(99L), eq(expected), eq(0), eq(0));

        // AI 被调用两次；repair_round 加 1；写一条 RETRIEVE_FALLBACK（SUCCEED）
        verify(agent, times(2)).streamAnswer(anyString(), anyList(), anyInt(), anyDouble(),
                anyList(), anyMap());
        verify(tracingService).incrementRepairRound(99L);
        verify(tracingService).recordRetrieveFallbackStep(eq(99L), eq("SUCCEED"), eq("你好"),
                contains("命中 1 条证据"), isNull(), anyLong());

        // 两轮 usage 累加后落库：prompt 100+200、completion 20+30
        verify(tracingService).recordTokenUsage(99L, 300, 50);

        // 第一轮带工具、第二轮工具列表为空
        ArgumentCaptor<List<AiChatMessage>> messagesCaptor = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<List<ToolCallback>> toolsCaptor = ArgumentCaptor.forClass(List.class);
        verify(agent, times(2)).streamAnswer(anyString(), messagesCaptor.capture(), anyInt(),
                anyDouble(), toolsCaptor.capture(), anyMap());
        assertThat(toolsCaptor.getAllValues().get(0)).hasSize(1);
        assertThat(toolsCaptor.getAllValues().get(1)).isEmpty();

        // 第二轮 messages = 原有消息 + 第一次回答 + 资料块与指令
        List<AiChatMessage> secondMessages = messagesCaptor.getAllValues().get(1);
        assertThat(secondMessages).hasSize(3);
        assertThat(secondMessages.get(1).role()).isEqualTo("assistant");
        assertThat(secondMessages.get(1).content()).isEqualTo("第一段回答。");
        assertThat(secondMessages.get(2).role()).isEqualTo("user");
        assertThat(secondMessages.get(2).content())
                .contains("进程是资源分配的基本单位")
                .contains("请只依据这些资料重新回答");

        // 手工构造的 ToolContext 带 userId / runId / 证据台账
        ArgumentCaptor<ToolContext> toolContextCaptor =
                ArgumentCaptor.forClass(ToolContext.class);
        verify(resourceTools).searchMyDocuments(eq("你好"), toolContextCaptor.capture());
        assertThat(toolContextCaptor.getValue().getContext())
                .containsEntry(AgentToolContextKeys.USER_ID, 7L)
                .containsEntry(AgentToolContextKeys.RUN_ID, 99L)
                .containsKey(AgentToolContextKeys.EVIDENCE_LEDGER);
    }

    @Test
    void toolCallPresentWithRetrieveDoesNotTriggerFallback() {
        stubKnowledgeRunWithToolCalls();
        AgentIntentClassifier classifier = mock(AgentIntentClassifier.class);
        when(classifier.classify("你好")).thenReturn(AgentIntent.RETRIEVE);
        LearningResourceTools resourceTools = mock(LearningResourceTools.class);
        KnowledgeAgentService agent = mockAgentService(List.of(
                Flux.just(chunk("进程是资源分配的基本单位。[E1]"))));
        createFallbackStreamer(agent, classifier, enabledRuntime(), resourceTools,
                ledgerFactoryReturning(ledgerWithOneEvidence()));

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        streamer.stream(context(), output);

        verify(agent, times(1)).streamAnswer(anyString(), anyList(), anyInt(), anyDouble(),
                anyList(), anyMap());
        verifyNoInteractions(resourceTools);
        verify(tracingService, never()).incrementRepairRound(anyLong());
        verify(tracingService, never()).recordRetrieveFallbackStep(any(), any(), any(), any(),
                any(), anyLong());
        assertThat(output.toString(StandardCharsets.UTF_8)).doesNotContain("根据你的笔记补充");
    }

    @Test
    void zeroToolCallChitchatWithSwitchOnDoesNotTriggerFallback() {
        AgentIntentClassifier classifier = mock(AgentIntentClassifier.class);
        when(classifier.classify("你好")).thenReturn(AgentIntent.CHITCHAT);
        LearningResourceTools resourceTools = mock(LearningResourceTools.class);
        KnowledgeAgentService agent = mockAgentService(List.of(
                Flux.just(chunk("你好，我是学习助手。"))));
        createFallbackStreamer(agent, classifier, enabledRuntime(), resourceTools,
                new EvidenceLedgerFactory());

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        streamer.stream(context(), output);

        verify(agent, times(1)).streamAnswer(anyString(), anyList(), anyInt(), anyDouble(),
                anyList(), anyMap());
        verifyNoInteractions(resourceTools);
        verify(tracingService, never()).incrementRepairRound(anyLong());

        ArgumentCaptor<String> persisted = ArgumentCaptor.forClass(String.class);
        verify(assistantService).completeAssistantMessage(eq(7L), eq(90L), persisted.capture(),
                any(), any());
        assertThat(persisted.getValue()).isEqualTo("你好，我是学习助手。");
        assertThat(output.toString(StandardCharsets.UTF_8)).doesNotContain("根据你的笔记补充");
    }

    @Test
    void switchOffZeroCallRetrieveKeepsB1BehaviorByteForByte() throws Exception {
        // 基线：未接线（8 参构造）的 B1 行为
        createStreamer(greetingModel());
        ByteArrayOutputStream baseline = new ByteArrayOutputStream();
        streamer.stream(context(), baseline);

        // 开关关（默认）+ 13 参装配：分类器与补检工具一次都不该被调用
        AgentIntentClassifier classifier = mock(AgentIntentClassifier.class);
        when(classifier.classify("你好")).thenReturn(AgentIntent.RETRIEVE);
        LearningResourceTools resourceTools = mock(LearningResourceTools.class);
        createFallbackStreamer(greetingModel(), classifier, new AgentRuntimeProperties(),
                resourceTools, new EvidenceLedgerFactory());
        ByteArrayOutputStream wired = new ByteArrayOutputStream();
        streamer.stream(context(), wired);

        assertThat(wired.toString(StandardCharsets.UTF_8))
                .as("开关关时 SSE 事件序列必须与 B1 行为逐字节一致")
                .isEqualTo(baseline.toString(StandardCharsets.UTF_8));
        verifyNoInteractions(classifier, resourceTools);
        verify(tracingService, never()).incrementRepairRound(anyLong());
        verify(tracingService, never()).recordRetrieveFallbackStep(any(), any(), any(), any(),
                any(), anyLong());
    }

    @Test
    void fallbackSecondRoundFailureKeepsFirstAnswerAndSucceedsRun() {
        stubRunWithTool(new AgentToolCallGuard(3), "search_my_documents", "未被调用");
        AgentIntentClassifier classifier = mock(AgentIntentClassifier.class);
        when(classifier.classify("你好")).thenReturn(AgentIntent.RETRIEVE);
        KnowledgeAgentService agent = mockAgentService(List.of(
                Flux.just(chunk("第一段回答。"), usageChunk(100, 20)),
                Flux.<ChatResponse>error(new IllegalStateException("模拟第二轮失败"))));
        createFallbackStreamer(agent, classifier, enabledRuntime(),
                retrievalToolReturningOneEvidence(), new EvidenceLedgerFactory());

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        streamer.stream(context(), output);

        String sse = output.toString(StandardCharsets.UTF_8);
        // 第一段保留、SSE 照常 completed、不发 error；第二段失败时不留下悬空分隔符
        assertThat(sse).contains("第一段回答。").contains("event: completed")
                .doesNotContain("event: error")
                .doesNotContain("根据你的笔记补充");

        ArgumentCaptor<String> persisted = ArgumentCaptor.forClass(String.class);
        verify(assistantService).completeAssistantMessage(eq(7L), eq(90L), persisted.capture(),
                any(), any());
        assertThat(persisted.getValue()).contains("第一段回答。")
                .doesNotContain(KnowledgeAssistantStreamer.FALLBACK_SEPARATOR);
        verify(tracingService).recordRetrieveFallbackStep(eq(99L), eq("FAILED"), eq("你好"),
                contains("命中 1 条证据"), anyString(), anyLong());
        // run 仍算成功，绝不 failRun
        verify(tracingService).succeedRun(eq(99L), eq(persisted.getValue()), eq(0), eq(0));
        verify(tracingService, never()).failRun(any(), any(), any(), anyInt(), anyInt());
    }

    @Test
    void fallbackRunsAtMostOnceEvenIfStillZeroToolCalls() {
        stubRunWithTool(new AgentToolCallGuard(3), "search_my_documents", "未被调用");
        AgentIntentClassifier classifier = mock(AgentIntentClassifier.class);
        when(classifier.classify("你好")).thenReturn(AgentIntent.RETRIEVE);
        KnowledgeAgentService agent = mockAgentService(List.of(
                Flux.just(chunk("第一段回答。")),
                Flux.just(chunk("[E1] 第二段回答。"))));
        createFallbackStreamer(agent, classifier, enabledRuntime(),
                retrievalToolReturningOneEvidence(), new EvidenceLedgerFactory());

        streamer.stream(context(), new ByteArrayOutputStream());

        // 补检后仍 0 工具调用，也不会再来一轮：AI 恰好两次
        verify(agent, times(2)).streamAnswer(anyString(), anyList(), anyInt(), anyDouble(),
                anyList(), anyMap());
        verify(tracingService, times(1)).incrementRepairRound(99L);
        verify(tracingService, times(1)).recordRetrieveFallbackStep(any(), any(), any(), any(),
                any(), anyLong());
    }

    @Test
    void switchOnRetrieveZeroCallWithoutEvidenceIsKnowledgeIntentAndAppendsNotice() {
        AgentIntentClassifier classifier = mock(AgentIntentClassifier.class);
        when(classifier.classify("你好")).thenReturn(AgentIntent.RETRIEVE);
        LearningResourceTools resourceTools = mock(LearningResourceTools.class);
        when(resourceTools.searchMyDocuments(anyString(), any(ToolContext.class)))
                .thenReturn("未检索到相关内容（matched=false）。请明确告知用户：你的笔记中暂未找到相关内容，不要编造。");
        KnowledgeAgentService agent = mockAgentService(List.of(
                Flux.just(chunk("第一段回答。"))));
        createFallbackStreamer(agent, classifier, enabledRuntime(), resourceTools,
                new EvidenceLedgerFactory());

        streamer.stream(context(), new ByteArrayOutputStream());

        // 开关开 + RETRIEVE + 0 调用 → 知识类：无依据提示照常追加（§1.3）
        ArgumentCaptor<String> persisted = ArgumentCaptor.forClass(String.class);
        verify(assistantService).completeAssistantMessage(eq(7L), eq(90L), persisted.capture(),
                any(), any());
        assertThat(persisted.getValue()).contains("第一段回答。")
                .contains(KnowledgeAssistantStreamer.UNGROUNDED_NOTICE.strip());
        verify(tracingService).recordRetrieveFallbackStep(eq(99L), eq("FAILED"), eq("你好"),
                contains("未命中证据"), eq("RETRIEVE_FALLBACK_NO_EVIDENCE"), anyLong());
        verify(agent, times(1)).streamAnswer(anyString(), anyList(), anyInt(), anyDouble(),
                anyList(), anyMap());
    }

    @Test
    void switchOnMultiHopZeroCallIsKnowledgeIntentWithoutFallback() {
        AgentIntentClassifier classifier = mock(AgentIntentClassifier.class);
        when(classifier.classify("你好")).thenReturn(AgentIntent.MULTI_HOP);
        LearningResourceTools resourceTools = mock(LearningResourceTools.class);
        KnowledgeAgentService agent = mockAgentService(List.of(
                Flux.just(chunk("第一段回答。"))));
        createFallbackStreamer(agent, classifier, enabledRuntime(), resourceTools,
                new EvidenceLedgerFactory());

        streamer.stream(context(), new ByteArrayOutputStream());

        // MULTI_HOP 属知识类但不触发补检（补检只针对 RETRIEVE/TASK）
        ArgumentCaptor<String> persisted = ArgumentCaptor.forClass(String.class);
        verify(assistantService).completeAssistantMessage(eq(7L), eq(90L), persisted.capture(),
                any(), any());
        assertThat(persisted.getValue()).contains(KnowledgeAssistantStreamer.UNGROUNDED_NOTICE.strip());
        verifyNoInteractions(resourceTools);
        verify(tracingService, never()).incrementRepairRound(anyLong());
        verify(tracingService, never()).recordRetrieveFallbackStep(any(), any(), any(), any(),
                any(), anyLong());
    }

    // ===== P1-C1：MULTI_HOP 计划编排（Planner → 并发检索 → 合成） =====

    /** 生产装配形态 + 计划组件：15 参构造（P1-B2 的 13 参 + Planner + Executor）。 */
    private void createPlanStreamer(KnowledgeAgentService agentService,
                                    AgentIntentClassifier classifier,
                                    AgentRuntimeProperties runtimeProperties,
                                    LearningResourceTools learningResourceTools,
                                    EvidenceLedgerFactory ledgerFactory,
                                    AgentPlanner planner,
                                    PlanExecutor planExecutor) {
        createPlanStreamer(agentService, classifier, runtimeProperties, learningResourceTools,
                ledgerFactory, new AgentTracingProperties(), planner, planExecutor);
    }

    /** 同上，但允许指定 tracing 配置（用于把总预算调小，验证合成阶段的保底预算）。 */
    private void createPlanStreamer(KnowledgeAgentService agentService,
                                    AgentIntentClassifier classifier,
                                    AgentRuntimeProperties runtimeProperties,
                                    LearningResourceTools learningResourceTools,
                                    EvidenceLedgerFactory ledgerFactory,
                                    AgentTracingProperties tracingProperties,
                                    AgentPlanner planner,
                                    PlanExecutor planExecutor) {
        this.agentService = agentService;
        streamer = new KnowledgeAssistantStreamer(
                agentService, assistantService, objectMapper, tracingService, toolCallbackFactory,
                new AgentRunSupervisor(), tracingProperties, ledgerFactory,
                AgentMetrics.noop(), null, classifier, runtimeProperties, learningResourceTools,
                planner, planExecutor);
    }

    private static AgentPlan twoSubQuestionPlan() {
        return AgentPlan.multiHop(List.of(
                new AgentPlan.SubQuestion("q1", "进程和线程有什么区别",
                        List.of(AgentPlan.RESOURCE_DOCUMENT), "进程 线程 区别"),
                new AgentPlan.SubQuestion("q2", "有哪些常见调度算法",
                        List.of(AgentPlan.RESOURCE_DOCUMENT), "进程调度算法")),
                false, "comparison");
    }

    private static AgentPlanner plannerReturning(AgentPlan plan, AiUsage usage) {
        AgentPlanner planner = mock(AgentPlanner.class);
        when(planner.plan(anyString(), anyLong()))
                .thenReturn(new AgentPlanner.Outcome(plan, usage, 5L));
        return planner;
    }

    private static VectorSearchService searchAnswering(
            Function<String, VectorSearchService.SearchOutcome> handler) {
        VectorSearchService search = mock(VectorSearchService.class);
        when(search.search(any(), anyString(), anyList())).thenAnswer(invocation ->
                handler.apply(invocation.getArgument(1)));
        return search;
    }

    private static VectorSearchService.VectorHit hitFor(String documentId) {
        return new VectorSearchService.VectorHit("document_chunk", documentId, 0,
                "笔记 " + documentId, "内容 " + documentId, List.of(), 0.8d,
                java.util.UUID.randomUUID());
    }

    private static VectorSearchService.SearchOutcome multipleHits(
            VectorSearchService.VectorHit... hits) {
        return new VectorSearchService.SearchOutcome(List.of(hits), hits.length > 0);
    }

    @Test
    void multiHopPlanRetrievesEachSubQuestionThenSynthesizesOnceWithEvidence() {
        AgentIntentClassifier classifier = mock(AgentIntentClassifier.class);
        when(classifier.classify("你好")).thenReturn(AgentIntent.MULTI_HOP);
        AgentPlanner planner = plannerReturning(twoSubQuestionPlan(), new AiUsage(20, 10, 30));
        VectorSearchService search = searchAnswering(query -> "进程 线程 区别".equals(query)
                ? multipleHits(hitFor("42"))
                : multipleHits(hitFor("43")));
        KnowledgeAgentService agent = mockAgentService(List.of(Flux.just(
                chunk("[E1][E2] 进程是资源分配单位，线程是调度单位。"), usageChunk(200, 50))));
        LearningResourceTools resourceTools = mock(LearningResourceTools.class);
        createPlanStreamer(agent, classifier, enabledRuntime(), resourceTools,
                new EvidenceLedgerFactory(), planner, new PlanExecutor(search));

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        streamer.stream(context(), output);

        // 1) 两个子问题各检索一次（并发，顺序无关）
        verify(search, times(2)).search(eq(7L), anyString(), anyList());
        ArgumentCaptor<String> queryCaptor = ArgumentCaptor.forClass(String.class);
        verify(search, times(2)).search(any(), queryCaptor.capture(), anyList());
        assertThat(queryCaptor.getAllValues()).containsExactlyInAnyOrder("进程 线程 区别", "进程调度算法");

        // 2) 合成只调一次 AI，工具列表为空；系统提示带证据块与作答要求
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ToolCallback>> toolsCaptor = ArgumentCaptor.forClass(List.class);
        verify(agent, times(1)).streamAnswer(promptCaptor.capture(), anyList(), anyInt(),
                anyDouble(), toolsCaptor.capture(), anyMap());
        assertThat(toolsCaptor.getValue()).isEmpty();
        String synthesisPrompt = promptCaptor.getValue();
        assertThat(countOccurrences(synthesisPrompt, "<evidence ")).isEqualTo(2);
        assertThat(synthesisPrompt)
                .contains("source=\"document_chunk:42\"")
                .contains("source=\"document_chunk:43\"")
                .contains("按子问题顺序作答")
                .contains("[E编号]")
                .contains("不得超出证据")
                .contains("本轮不要调用任何工具");
        assertThat(synthesisPrompt.indexOf("1. 进程和线程有什么区别"))
                .isGreaterThanOrEqualTo(0)
                .isLessThan(synthesisPrompt.indexOf("2. 有哪些常见调度算法"));

        // 3) SSE 正常收尾，没有 error；run 成功、绝不 failRun
        String sse = output.toString(StandardCharsets.UTF_8);
        assertThat(sse).contains("event: completed").doesNotContain("event: error");
        assertThat(sse).contains("[E1][E2] 进程是资源分配单位，线程是调度单位。");
        verify(tracingService).succeedRun(eq(99L), anyString(), eq(0), eq(0));
        verify(tracingService, never()).failRun(any(), any(), any(), anyInt(), anyInt());

        // 4) plan_json 落库的是校验后的计划
        ArgumentCaptor<String> planJsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(tracingService).recordPlanJson(eq(99L), planJsonCaptor.capture());
        assertThat(planJsonCaptor.getValue())
                .contains("\"complexity\":\"MULTI_HOP\"")
                .contains("进程 线程 区别")
                .contains("进程调度算法");

        // 5) 步骤顺序：PLAN → RETRIEVE ×2 → SYNTH
        ArgumentCaptor<String> stepTypeCaptor = ArgumentCaptor.forClass(String.class);
        verify(tracingService, times(4)).recordStep(eq(99L), stepTypeCaptor.capture(), any(),
                any(), any(), any(), any(), anyLong());
        assertThat(stepTypeCaptor.getAllValues())
                .containsExactly("PLAN", "RETRIEVE", "RETRIEVE", "SYNTH");

        // 6) token：Planner 真实用量（20/10）+ 合成（200/50）累加后落库
        verify(tracingService).recordTokenUsage(99L, 220, 60);

        // 7) 计划路径下（MULTI_HOP + 本轮 0 工具调用）不触发 B2 零调用补检
        verifyNoInteractions(resourceTools);
        verify(tracingService, never()).incrementRepairRound(anyLong());
        verify(tracingService, never()).recordRetrieveFallbackStep(any(), any(), any(), any(),
                any(), anyLong());
    }

    @Test
    void degradedPlanRetrievesOnceAndStillProducesAnswer() {
        AgentIntentClassifier classifier = mock(AgentIntentClassifier.class);
        when(classifier.classify("你好")).thenReturn(AgentIntent.MULTI_HOP);
        AgentPlan degraded = AgentPlan.singleQuery("你好", AgentPlanner.DEGRADE_INVALID_JSON);
        AgentPlanner planner = plannerReturning(degraded, AiUsage.empty());
        VectorSearchService search = searchAnswering(query -> multipleHits(hitFor("42")));
        KnowledgeAgentService agent = mockAgentService(List.of(
                Flux.just(chunk("[E1] 进程是资源分配的基本单位。"))));
        createPlanStreamer(agent, classifier, enabledRuntime(), mock(LearningResourceTools.class),
                new EvidenceLedgerFactory(), planner, new PlanExecutor(search));

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        streamer.stream(context(), output);

        // 降级计划只检索 1 次，query 就是原始用户消息
        verify(search, times(1)).search(eq(7L), eq("你好"), anyList());
        verify(agent, times(1)).streamAnswer(anyString(), anyList(), anyInt(), anyDouble(),
                anyList(), anyMap());

        // 步骤：PLAN 标 FAILED + 稳定错误码，但后面 RETRIEVE/SYNTH 照常
        ArgumentCaptor<String> stepTypeCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> statusCaptor = ArgumentCaptor.forClass(String.class);
        verify(tracingService, times(3)).recordStep(eq(99L), stepTypeCaptor.capture(), any(),
                statusCaptor.capture(), any(), any(), any(), anyLong());
        assertThat(stepTypeCaptor.getAllValues()).containsExactly("PLAN", "RETRIEVE", "SYNTH");
        assertThat(statusCaptor.getAllValues()).containsExactly("FAILED", "SUCCEED", "SUCCEED");
        verify(tracingService).recordStep(eq(99L), eq("PLAN"), any(), eq("FAILED"), any(), any(),
                eq(AgentPlanner.DEGRADE_INVALID_JSON), anyLong());

        // 仍然给出回答、run 成功
        String sse = output.toString(StandardCharsets.UTF_8);
        assertThat(sse).contains("event: completed").doesNotContain("event: error");
        verify(tracingService).succeedRun(eq(99L), anyString(), eq(0), eq(0));
        verify(tracingService, never()).failRun(any(), any(), any(), anyInt(), anyInt());

        // 降级计划也落 plan_json（degraded=true）
        verify(tracingService).recordPlanJson(eq(99L), contains("\"degraded\":true"));
        // Planner 没有真实用量、合成也没有 usage 帧 → 不写 token（不估算）
        verify(tracingService, never()).recordTokenUsage(any(), any(), any());
    }

    @Test
    void executorAllEmptyStillSynthesizesAndAppendsUngroundedNotice() {
        AgentIntentClassifier classifier = mock(AgentIntentClassifier.class);
        when(classifier.classify("你好")).thenReturn(AgentIntent.MULTI_HOP);
        AgentPlanner planner = plannerReturning(twoSubQuestionPlan(), AiUsage.empty());
        VectorSearchService search = searchAnswering(query -> multipleHits());
        KnowledgeAgentService agent = mockAgentService(List.of(
                Flux.just(chunk("没有找到相关依据。"))));
        createPlanStreamer(agent, classifier, enabledRuntime(), mock(LearningResourceTools.class),
                new EvidenceLedgerFactory(), planner, new PlanExecutor(search));

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        streamer.stream(context(), output);

        // 两个子问题都检索了，但全空
        verify(search, times(2)).search(any(), anyString(), anyList());

        // 合成仍执行，提示里明确“没有证据”，回答照常生成并追加无依据提示
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(agent, times(1)).streamAnswer(promptCaptor.capture(), anyList(), anyInt(),
                anyDouble(), anyList(), anyMap());
        assertThat(promptCaptor.getValue()).contains("本次没有检索到任何证据");

        ArgumentCaptor<String> persisted = ArgumentCaptor.forClass(String.class);
        verify(assistantService).completeAssistantMessage(eq(7L), eq(90L), persisted.capture(),
                any(), any());
        assertThat(persisted.getValue())
                .startsWith("没有找到相关依据。")
                .endsWith(KnowledgeAssistantStreamer.UNGROUNDED_NOTICE);
        assertThat(output.toString(StandardCharsets.UTF_8))
                .contains("event: completed").doesNotContain("event: error");
        verify(tracingService).succeedRun(eq(99L), eq(persisted.getValue()), eq(0), eq(0));
        verify(tracingService, never()).failRun(any(), any(), any(), anyInt(), anyInt());

        // 两条 RETRIEVE 都是 FAILED + PLAN_RETRIEVE_NO_EVIDENCE
        verify(tracingService, times(2)).recordStep(eq(99L), eq("RETRIEVE"), any(),
                eq("FAILED"), any(), any(), eq(PlanExecutor.ERROR_NO_EVIDENCE), anyLong());
    }

    @Test
    void switchOffMultiHopDoesNotTouchPlanComponentsAndKeepsB2Sse() throws Exception {
        // 基线：B2 装配（13 参，无计划组件）+ 开关关
        AgentIntentClassifier baselineClassifier = mock(AgentIntentClassifier.class);
        when(baselineClassifier.classify(anyString())).thenReturn(AgentIntent.MULTI_HOP);
        createFallbackStreamer(greetingModel(), baselineClassifier, new AgentRuntimeProperties(),
                mock(LearningResourceTools.class), new EvidenceLedgerFactory());
        ByteArrayOutputStream baseline = new ByteArrayOutputStream();
        streamer.stream(context(), baseline);

        // 开关关（默认）+ 15 参装配（Planner/Executor 已接线）
        AgentIntentClassifier classifier = mock(AgentIntentClassifier.class);
        when(classifier.classify(anyString())).thenReturn(AgentIntent.MULTI_HOP);
        AgentPlanner planner = mock(AgentPlanner.class);
        PlanExecutor planExecutor = mock(PlanExecutor.class);
        createPlanStreamer(new KnowledgeAgentService(greetingModel(), new AiProperties()),
                classifier, new AgentRuntimeProperties(), mock(LearningResourceTools.class),
                new EvidenceLedgerFactory(), planner, planExecutor);
        ByteArrayOutputStream wired = new ByteArrayOutputStream();
        streamer.stream(context(), wired);

        assertThat(wired.toString(StandardCharsets.UTF_8))
                .as("开关关时 MULTI_HOP 不得走计划路径，SSE 必须与 B2 逐字节一致")
                .isEqualTo(baseline.toString(StandardCharsets.UTF_8));
        verifyNoInteractions(planner, planExecutor);
        // 开关关时分类器一次都不调用（intent 恒为 null）
        verifyNoInteractions(classifier);
        verify(tracingService, never()).recordPlanJson(any(), any());
        verify(tracingService, never()).recordStep(any(), any(), any(), any(), any(), any(),
                any(), any());
        verify(tracingService, never()).incrementRepairRound(anyLong());
    }

    @Test
    void switchOnRetrieveStillUsesB2FallbackAndNeverTouchesPlanComponents() {
        AgentToolCallGuard guard = new AgentToolCallGuard(3);
        stubRunWithTool(guard, "search_my_documents", "未被调用");
        AgentIntentClassifier classifier = mock(AgentIntentClassifier.class);
        when(classifier.classify("你好")).thenReturn(AgentIntent.RETRIEVE);
        LearningResourceTools resourceTools = retrievalToolReturningOneEvidence();
        KnowledgeAgentService agent = mockAgentService(List.of(
                Flux.just(chunk("第一段回答。"), usageChunk(100, 20)),
                Flux.just(chunk("[E1] 第二段回答。"), usageChunk(200, 30))));
        AgentPlanner planner = mock(AgentPlanner.class);
        PlanExecutor planExecutor = mock(PlanExecutor.class);
        createPlanStreamer(agent, classifier, enabledRuntime(), resourceTools,
                new EvidenceLedgerFactory(), planner, planExecutor);

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        streamer.stream(context(), output);

        // RETRIEVE 永远不走计划路径；B2 补检行为保持不变
        verifyNoInteractions(planner, planExecutor);
        verify(agent, times(2)).streamAnswer(anyString(), anyList(), anyInt(), anyDouble(),
                anyList(), anyMap());
        verify(tracingService).incrementRepairRound(99L);
        verify(tracingService).recordRetrieveFallbackStep(eq(99L), eq("SUCCEED"), eq("你好"),
                contains("命中 1 条证据"), isNull(), anyLong());
        verify(tracingService, never()).recordPlanJson(any(), any());
        verify(tracingService, never()).recordStep(any(), any(), any(), any(), any(), any(),
                any(), any());
        assertThat(output.toString(StandardCharsets.UTF_8))
                .contains("根据你的笔记补充").contains("event: completed")
                .doesNotContain("event: error");
    }

    @Test
    void switchOnChitchatNeverTouchesPlanComponents() {
        AgentIntentClassifier classifier = mock(AgentIntentClassifier.class);
        when(classifier.classify("你好")).thenReturn(AgentIntent.CHITCHAT);
        LearningResourceTools resourceTools = mock(LearningResourceTools.class);
        KnowledgeAgentService agent = mockAgentService(List.of(
                Flux.just(chunk("你好，我是学习助手。"))));
        AgentPlanner planner = mock(AgentPlanner.class);
        PlanExecutor planExecutor = mock(PlanExecutor.class);
        createPlanStreamer(agent, classifier, enabledRuntime(), resourceTools,
                new EvidenceLedgerFactory(), planner, planExecutor);

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        streamer.stream(context(), output);

        verifyNoInteractions(planner, planExecutor, resourceTools);
        verify(agent, times(1)).streamAnswer(anyString(), anyList(), anyInt(), anyDouble(),
                anyList(), anyMap());
        verify(tracingService, never()).recordPlanJson(any(), any());
        verify(tracingService, never()).recordStep(any(), any(), any(), any(), any(), any(),
                any(), any());
        verify(tracingService, never()).incrementRepairRound(anyLong());
        assertThat(output.toString(StandardCharsets.UTF_8))
                .contains("event: completed").doesNotContain("event: error");
    }

    @Test
    void planPathDoesNotTriggerRetrieveFallback() {
        AgentIntentClassifier classifier = mock(AgentIntentClassifier.class);
        when(classifier.classify("你好")).thenReturn(AgentIntent.MULTI_HOP);
        AgentPlan plan = AgentPlan.multiHop(List.of(
                new AgentPlan.SubQuestion("q1", "进程调度算法",
                        List.of(AgentPlan.RESOURCE_DOCUMENT), "进程调度算法")),
                false, "answer");
        AgentPlanner planner = plannerReturning(plan, AiUsage.empty());
        VectorSearchService search = searchAnswering(query -> multipleHits(hitFor("42")));
        KnowledgeAgentService agent = mockAgentService(List.of(
                Flux.just(chunk("[E1] 常见调度算法有先来先服务、时间片轮转。"))));
        LearningResourceTools resourceTools = mock(LearningResourceTools.class);
        createPlanStreamer(agent, classifier, enabledRuntime(), resourceTools,
                new EvidenceLedgerFactory(), planner, new PlanExecutor(search));

        streamer.stream(context(), new ByteArrayOutputStream());

        // 计划路径已自行完成检索：本轮工具调用数为 0，但绝不能再触发 B2 补检
        verifyNoInteractions(resourceTools);
        verify(tracingService, never()).incrementRepairRound(anyLong());
        verify(tracingService, never()).recordRetrieveFallbackStep(any(), any(), any(), any(),
                any(), anyLong());
        verify(agent, times(1)).streamAnswer(anyString(), anyList(), anyInt(), anyDouble(),
                anyList(), anyMap());
        verify(tracingService).succeedRun(eq(99L), anyString(), eq(0), eq(0));
    }

    // ===== P1-D2：关键结论句自检 → 补充说明 + notice 事件 =====

    /**
     * 生产形态 + 开关打开的知识类回答：工具调用数 1（knowledgeIntent=true），
     * 分类器返回 null（不走补检、不走计划路径），只验证 P1-D2 这一段。
     */
    private void createSupplementStreamer(ChatModel chatModel, EvidenceLedgerFactory ledgerFactory) {
        stubKnowledgeRunWithToolCalls();
        createRuntimeStreamer(chatModel, mock(AgentIntentClassifier.class), enabledRuntime(),
                ledgerFactory);
    }

    /** 取指定 SSE 事件的 data 载荷；没有该事件时返回 null。 */
    private JsonNode eventPayload(String sseOutput, String eventName) throws Exception {
        for (String block : sseOutput.split("\n\n")) {
            if (!block.contains("event: " + eventName)) {
                continue;
            }
            for (String line : block.lines().toList()) {
                if (line.startsWith("data: ")) {
                    return objectMapper.readTree(line.substring("data: ".length()));
                }
            }
        }
        return null;
    }

    private static final String SUPPLEMENT_PREFIX = "\n\n> 补充说明：";

    @Test
    void zeroCitationAnswerKeepsGenericNoticeAndEmitsNoNoticeEvent() throws Exception {
        createSupplementStreamer(new RecordingChatModel(
                        prompt -> Flux.just(chunk("进程是资源分配的基本单位。"))),
                new EvidenceLedgerFactory());

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        streamer.stream(context(), output);
        String sse = output.toString(StandardCharsets.UTF_8);

        // 裁定（整体提示优先）：整篇零引用 → 只追加 P0 的 UNGROUNDED_NOTICE（文案一个字不改），
        // 不逐句列点、不发 notice，SSE 与改造前一致（只有 delta / completed）
        String expected = "进程是资源分配的基本单位。"
                + KnowledgeAssistantStreamer.UNGROUNDED_NOTICE;
        ArgumentCaptor<String> persisted = ArgumentCaptor.forClass(String.class);
        verify(assistantService).completeAssistantMessage(eq(7L), eq(90L), persisted.capture(),
                any(), any());
        assertThat(persisted.getValue()).isEqualTo(expected);
        assertThat(persisted.getValue()).doesNotContain("补充说明");
        assertThat(countOccurrences(persisted.getValue(), "本次回答未能关联到你的笔记内容"))
                .isEqualTo(1);

        assertThat(sse).doesNotContain("event: notice").doesNotContain("event: error")
                .contains("event: delta").contains("event: completed");
        assertThat(eventPayload(sse, "notice")).isNull();

        // 落库口径不变：grounded=false、citation_count=0；repair_round 不加、不写 step
        verify(tracingService).recordCitationSummary(99L, 0, false);
        verify(tracingService, never()).incrementRepairRound(anyLong());
        verify(tracingService, never()).recordStep(any(), any(), any(), any(), any(), any(),
                any(), any());
        verify(tracingService).succeedRun(99L, expected, 1, 0);
        verify(tracingService, never()).failRun(any(), any(), any(), anyInt(), anyInt());
    }

    @Test
    void invalidCitationStaysInvalidAndItsSentenceIsListedOnce() throws Exception {
        // 混一条合法引用（E1）让本轮 grounded=true，才能走到「部分有依据」的逐句补充说明路径；
        // E9 这条非法编号仍按原口径计数
        createSupplementStreamer(new RecordingChatModel(prompt -> Flux.just(chunk(
                        "进程是资源分配的基本单位。[E1] 线程是调度基本单位。[E9]"))),
                ledgerFactoryReturning(ledgerWithOneEvidence()));

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        streamer.stream(context(), output);
        String sse = output.toString(StandardCharsets.UTF_8);

        // 非法编号仍进 agent_citations（valid=false），逐句口径只多一条 unsupported，两个口径不重复计
        ArgumentCaptor<String> persisted = ArgumentCaptor.forClass(String.class);
        verify(assistantService).completeAssistantMessage(eq(7L), eq(90L), persisted.capture(),
                any(), any());
        assertThat(persisted.getValue()).isEqualTo(
                "进程是资源分配的基本单位。[E1] 线程是调度基本单位。[E9]"
                        + SUPPLEMENT_PREFIX + "上面第 2 点未在笔记中找到依据。");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<com.example.aimindmap.agentruntime.CitationCheckResult.Citation>>
                citationCaptor = ArgumentCaptor.forClass(List.class);
        verify(tracingService).recordEvidenceAndCitations(eq(99L), anyList(),
                citationCaptor.capture());
        assertThat(citationCaptor.getValue()).containsExactly(
                new com.example.aimindmap.agentruntime.CitationCheckResult.Citation("E1", true),
                new com.example.aimindmap.agentruntime.CitationCheckResult.Citation("E9", false));
        verify(tracingService).recordCitationSummary(99L, 1, true);
        // 只有第 2 点被列出来：不会因为「既非法又无依据」把第 1 句也列进去
        assertThat(countOccurrences(persisted.getValue(), "上面第 2 点")).isEqualTo(1);
        assertThat(persisted.getValue()).doesNotContain("第 1 点").doesNotContain("第 3 点");
        verify(tracingService, never()).incrementRepairRound(anyLong());
    }

    @Test
    void partialCitationsListOnlyUnsupportedPointsInOrder() throws Exception {
        createSupplementStreamer(new RecordingChatModel(prompt -> Flux.just(chunk(
                        "进程是资源分配的基本单位。[E1] 线程是调度基本单位。调度有三种算法。"))),
                ledgerFactoryReturning(ledgerWithOneEvidence()));

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        streamer.stream(context(), output);
        String sse = output.toString(StandardCharsets.UTF_8);

        // 3 个关键结论句只第 1 句有合法引用 → 只列第 2、3 点，编号与出现顺序一致
        String expectedAnswer = "进程是资源分配的基本单位。[E1] 线程是调度基本单位。调度有三种算法。";
        ArgumentCaptor<String> persisted = ArgumentCaptor.forClass(String.class);
        verify(assistantService).completeAssistantMessage(eq(7L), eq(90L), persisted.capture(),
                any(), any());
        assertThat(persisted.getValue()).isEqualTo(expectedAnswer
                + SUPPLEMENT_PREFIX + "上面第 2、3 点未在笔记中找到依据。");
        JsonNode notice = eventPayload(sse, "notice");
        assertThat(notice).isNotNull();
        assertThat(notice.get("code").asText())
                .isEqualTo(KnowledgeAssistantStreamer.CITATION_UNSUPPORTED_CODE);
        assertThat(notice.get("message").asText()).isEqualTo("上面第 2、3 点未在笔记中找到依据。");
        assertThat(notice.get("points").size()).isEqualTo(2);
        assertThat(notice.get("points").get(0).asInt()).isEqualTo(2);
        assertThat(notice.get("points").get(1).asInt()).isEqualTo(3);
        // 部分有依据 → 走逐句补充说明，整体提示绝不叠加
        verify(tracingService).recordCitationSummary(99L, 1, true);
        assertThat(persisted.getValue()).doesNotContain("本次回答未能关联到你的笔记内容");
        verify(tracingService, never()).incrementRepairRound(anyLong());
    }

    @Test
    void supplementIsAppendedOnlyOnceWhenAnswerAlreadyContainsIt() throws Exception {
        String answer = "进程是资源分配的基本单位。[E1] 线程是调度基本单位。"
                + SUPPLEMENT_PREFIX + "上面第 2 点未在笔记中找到依据。";
        createSupplementStreamer(new RecordingChatModel(prompt -> Flux.just(chunk(answer))),
                ledgerFactoryReturning(ledgerWithOneEvidence()));

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        streamer.stream(context(), output);
        String sse = output.toString(StandardCharsets.UTF_8);

        ArgumentCaptor<String> persisted = ArgumentCaptor.forClass(String.class);
        verify(assistantService).completeAssistantMessage(eq(7L), eq(90L), persisted.capture(),
                any(), any());
        assertThat(persisted.getValue()).isEqualTo(answer);
        assertThat(countOccurrences(persisted.getValue(), "补充说明：")).isEqualTo(1);
        assertThat(eventPayload(sse, "notice")).isNull();
    }

    @Test
    void chitchatDoesNotAppendSupplementOrEmitNotice() throws Exception {
        AgentIntentClassifier classifier = mock(AgentIntentClassifier.class);
        when(classifier.classify("你好")).thenReturn(AgentIntent.CHITCHAT);
        createRuntimeStreamer(new RecordingChatModel(
                        prompt -> Flux.just(chunk("调度有三种算法。"))),
                classifier, enabledRuntime(), new EvidenceLedgerFactory());

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        streamer.stream(context(), output);
        String sse = output.toString(StandardCharsets.UTF_8);

        // 非知识类：即便句子里有「三种」这种数字，也不追加、不发 notice、不追加整体提示
        ArgumentCaptor<String> persisted = ArgumentCaptor.forClass(String.class);
        verify(assistantService).completeAssistantMessage(eq(7L), eq(90L), persisted.capture(),
                any(), any());
        assertThat(persisted.getValue()).isEqualTo("调度有三种算法。");
        assertThat(sse).doesNotContain("补充说明").doesNotContain("event: notice")
                .doesNotContain("本次回答未能关联到你的笔记内容");
        verify(tracingService, never()).incrementRepairRound(anyLong());
    }

    @Test
    void switchOffKeepsSseByteForByteAndNeverAppendsSupplement() throws Exception {
        // 基线：未接线（8 参构造，P1-D1 之前的现有行为）
        stubKnowledgeRunWithToolCalls();
        createStreamer(new RecordingChatModel(
                prompt -> Flux.just(chunk("进程是资源分配的基本单位。"))));
        ByteArrayOutputStream baseline = new ByteArrayOutputStream();
        streamer.stream(context(), baseline);

        // 开关关（默认）+ 12 参装配：逐句自检一次都不生效
        stubKnowledgeRunWithToolCalls();
        createRuntimeStreamer(new RecordingChatModel(
                        prompt -> Flux.just(chunk("进程是资源分配的基本单位。"))),
                mock(AgentIntentClassifier.class), new AgentRuntimeProperties(),
                new EvidenceLedgerFactory());
        ByteArrayOutputStream wired = new ByteArrayOutputStream();
        streamer.stream(context(), wired);

        String wiredText = wired.toString(StandardCharsets.UTF_8);
        assertThat(wiredText)
                .as("开关关时 SSE 事件序列必须与 P1-D1 逐字节一致")
                .isEqualTo(baseline.toString(StandardCharsets.UTF_8));
        assertThat(wiredText).doesNotContain("补充说明").doesNotContain("event: notice")
                .contains("本次回答未能关联到你的笔记内容");
        verify(tracingService, never()).incrementRepairRound(anyLong());
    }

    @Test
    void genericUngroundedNoticeWinsAndSuppressesPerSentenceSupplement() throws Exception {
        String answer = "进程是资源分配的基本单位，线程是调度基本单位。"
                + "\n\n> 注：本次回答未能关联到你的笔记内容，请谨慎参考。";
        createSupplementStreamer(new RecordingChatModel(prompt -> Flux.just(chunk(answer))),
                new EvidenceLedgerFactory());

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        streamer.stream(context(), output);
        String sse = output.toString(StandardCharsets.UTF_8);

        // 互斥优先级（裁定：整体优先）：本条是零引用 + 答案自带整体提示，
        // 两道保险都要求「只保留整体提示、不再逐句追加」（ungrounded 前置 + 文案去重）
        ArgumentCaptor<String> persisted = ArgumentCaptor.forClass(String.class);
        verify(assistantService).completeAssistantMessage(eq(7L), eq(90L), persisted.capture(),
                any(), any());
        assertThat(persisted.getValue()).isEqualTo(answer);
        assertThat(countOccurrences(persisted.getValue(), "本次回答未能关联到你的笔记内容"))
                .isEqualTo(1);
        assertThat(persisted.getValue()).doesNotContain("补充说明");
        assertThat(eventPayload(sse, "notice")).isNull();
    }

    @Test
    void codeBlockSentencesDoNotTriggerSupplement() throws Exception {
        String answer = "示例代码：\n\n"
                + "```java\nint count = 3;\n因此需要三份拷贝。\n```\n\n"
                + "看代码即可。";
        createSupplementStreamer(new RecordingChatModel(prompt -> Flux.just(chunk(answer))),
                new EvidenceLedgerFactory());

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        streamer.stream(context(), output);
        String sse = output.toString(StandardCharsets.UTF_8);

        // 代码块里的「3」「因此」不参与判定 → 没有关键结论句 → 不追加逐句说明，照旧整体提示
        ArgumentCaptor<String> persisted = ArgumentCaptor.forClass(String.class);
        verify(assistantService).completeAssistantMessage(eq(7L), eq(90L), persisted.capture(),
                any(), any());
        assertThat(persisted.getValue()).doesNotContain("补充说明")
                .endsWith(KnowledgeAssistantStreamer.UNGROUNDED_NOTICE);
        assertThat(eventPayload(sse, "notice")).isNull();
    }

    @Test
    void tooLongAnswerStillCompletesAndListsAtMostThreePoints() throws Exception {
        // 第一句带合法引用（grounded=true，走逐句路径），后面 250 句都是无引用的关键结论句
        StringBuilder answer = new StringBuilder("[E1] 这是总述。");
        for (int i = 0; i < 250; i++) {
            answer.append("这是第").append(i + 1).append("种情况说明。");
        }
        createSupplementStreamer(new RecordingChatModel(
                        prompt -> Flux.just(chunk(answer.toString()))),
                ledgerFactoryReturning(ledgerWithOneEvidence()));

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        streamer.stream(context(), output);
        String sse = output.toString(StandardCharsets.UTF_8);

        // >200 句只查前 200 句（打 warn），不炸也不超时；文案最多列 3 条
        // 第 1 个关键结论句已引用 → 从第 2 点开始列，最多到第 4 点
        ArgumentCaptor<String> persisted = ArgumentCaptor.forClass(String.class);
        verify(assistantService).completeAssistantMessage(eq(7L), eq(90L), persisted.capture(),
                any(), any());
        assertThat(persisted.getValue())
                .endsWith(SUPPLEMENT_PREFIX + "上面第 2、3、4 点未在笔记中找到依据。");
        assertThat(sse).contains("event: completed").contains("event: notice")
                .doesNotContain("event: error");
        verify(tracingService).recordCitationSummary(99L, 1, true);
        verify(tracingService, never()).incrementRepairRound(anyLong());
    }

    @Test
    void noticeEventPushFailureDoesNotBreakAnswer() throws Exception {
        // 部分有依据（E1）才会下发 notice；零引用路径不发 notice（裁定：整体优先）
        createSupplementStreamer(new RecordingChatModel(prompt -> Flux.just(chunk(
                        "进程是资源分配的基本单位。[E1] 线程是调度基本单位。"))),
                ledgerFactoryReturning(ledgerWithOneEvidence()));

        FailOnEventOutputStream output = new FailOnEventOutputStream("notice");
        streamer.stream(context(), output);

        String sse = output.toString(StandardCharsets.UTF_8);
        assertThat(output.failAttempts).isGreaterThan(0);
        // notice 推送失败只记 warn：delta 里的补充说明与 completed 都照常下发
        assertThat(sse).contains("补充说明：上面第 2 点").contains("event: completed")
                .doesNotContain("event: error");
        verify(tracingService, never()).failRun(any(), any(), any(), anyInt(), anyInt());
    }

    /** 对指定事件名的写入抛异常的输出流，用于验证可选事件推送失败不影响回答。 */
    private static final class FailOnEventOutputStream extends ByteArrayOutputStream {

        private final String eventName;
        private int failAttempts;

        private FailOnEventOutputStream(String eventName) {
            this.eventName = eventName;
        }

        @Override
        public synchronized void write(byte[] b, int off, int len) {
            String chunk = new String(b, off, len, StandardCharsets.UTF_8);
            if (chunk.contains("event: " + eventName)) {
                failAttempts++;
                throw new UncheckedIOException(new IOException("模拟 SSE 写入失败"));
            }
            super.write(b, off, len);
        }
    }

    @Test
    void exhaustedTotalBudgetStillSynthesizesWithMinimumBudget() {
        AgentIntentClassifier classifier = mock(AgentIntentClassifier.class);
        when(classifier.classify("你好")).thenReturn(AgentIntent.MULTI_HOP);
        // 模拟「总预算已耗尽」：Planner 直接降级，只剩不到 1s
        AgentPlan degraded = AgentPlan.singleQuery("你好", AgentPlanner.DEGRADE_BUDGET_EXHAUSTED);
        AgentPlanner planner = plannerReturning(degraded, AiUsage.empty());
        VectorSearchService search = searchAnswering(query -> multipleHits(hitFor("42")));
        KnowledgeAgentService agent = mockAgentService(List.of(
                Flux.just(chunk("[E1] 预算耗尽也照常回答。"))));
        AgentTracingProperties tight = new AgentTracingProperties();
        tight.setTimeoutSeconds(1);
        createPlanStreamer(agent, classifier, enabledRuntime(), mock(LearningResourceTools.class),
                new EvidenceLedgerFactory(), tight, planner, new PlanExecutor(search));

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        streamer.stream(context(), output);

        // 剩余预算 < MIN_SYNTHESIS_BUDGET_MS 时仍保底合成，回答照常下发、run 成功
        verify(agent, times(1)).streamAnswer(anyString(), anyList(), anyInt(), anyDouble(),
                anyList(), anyMap());
        assertThat(output.toString(StandardCharsets.UTF_8))
                .contains("[E1] 预算耗尽也照常回答。")
                .contains("event: completed")
                .doesNotContain("event: error");
        verify(tracingService).succeedRun(eq(99L), anyString(), eq(0), eq(0));
        verify(tracingService, never()).failRun(any(), any(), any(), anyInt(), anyInt());
    }

    // ===== P1-D2b：一个 run 只有一个总预算 =====

    /**
     * P1-D2b：计划路径的三段在发现「没预算了」时各打一条<b>统一日志</b>（stage=PLAN / RETRIEVE / SYNTH），
     * 而且仍然走保底合成、run 成功——这是 P1-F 统计「哪一段把预算用完」的唯一入口。
     *
     * <p>用 {@code timeout-seconds=0} 让三段开始时的剩余预算都必然是 0，因此断言是确定性的，
     * 不依赖机器速度。
     */
    @Test
    void planPathLogsBudgetExhaustedOncePerStageWithoutFailingRun() {
        AgentIntentClassifier classifier = mock(AgentIntentClassifier.class);
        when(classifier.classify("你好")).thenReturn(AgentIntent.MULTI_HOP);
        AgentPlanner planner = plannerReturning(twoSubQuestionPlan(), AiUsage.empty());
        KnowledgeAgentService agent = mockAgentService(List.of(
                Flux.just(chunk("[E1] 没有预算也照常回答。"))));
        AgentTracingProperties emptyBudget = new AgentTracingProperties();
        emptyBudget.setTimeoutSeconds(0);
        createPlanStreamer(agent, classifier, enabledRuntime(), mock(LearningResourceTools.class),
                new EvidenceLedgerFactory(), emptyBudget, planner,
                new PlanExecutor(mock(VectorSearchService.class)));

        ListAppender<ILoggingEvent> appender = attachAppender(KnowledgeAssistantStreamer.class);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            streamer.stream(context(), output);
        } finally {
            detachAppender(KnowledgeAssistantStreamer.class, appender);
        }

        // 预算为 0 也不许把整轮判失败：保底 5s 合成照常给出回答
        assertThat(output.toString(StandardCharsets.UTF_8))
                .contains("[E1] 没有预算也照常回答。")
                .contains("event: completed")
                .doesNotContain("event: error");
        verify(tracingService).succeedRun(eq(99L), anyString(), eq(0), eq(0));
        verify(tracingService, never()).failRun(any(), any(), any(), anyInt(), anyInt());
        assertThat(appender.list).anySatisfy(event -> assertThat(event.getFormattedMessage())
                .contains("Agent 预算耗尽").contains("runId=99").contains("stage=PLAN"));
        assertThat(appender.list).anySatisfy(event -> assertThat(event.getFormattedMessage())
                .contains("Agent 预算耗尽").contains("runId=99").contains("stage=RETRIEVE"));
        assertThat(appender.list).anySatisfy(event -> assertThat(event.getFormattedMessage())
                .contains("Agent 预算耗尽").contains("runId=99").contains("stage=SYNTH"));
    }

    /** 记录传给 executeWithTimeout 的超时时长的真实 Supervisor（用于断言合成阶段拿到的预算）。 */
    private static final class RecordingSupervisor extends AgentRunSupervisor {

        private final List<Duration> timeouts = new ArrayList<>();

        @Override
        public <T> T executeWithTimeout(Duration timeout, Callable<T> task) {
            timeouts.add(timeout);
            return super.executeWithTimeout(timeout, task);
        }
    }

    @Test
    void planPathSharesOneBudgetAcrossPlannerExecutorAndSynthesis() {
        AgentIntentClassifier classifier = mock(AgentIntentClassifier.class);
        when(classifier.classify("你好")).thenReturn(AgentIntent.MULTI_HOP);

        // fake Planner / Executor：把拿到的 remainingBudgetMs 记下来，并各花 5ms 让时间真的往前走
        AtomicLong plannerBudgetMs = new AtomicLong(-1L);
        AgentPlanner planner = mock(AgentPlanner.class);
        when(planner.plan(anyString(), anyLong())).thenAnswer(invocation -> {
            plannerBudgetMs.set(invocation.getArgument(1));
            Thread.sleep(5L);
            return new AgentPlanner.Outcome(twoSubQuestionPlan(), AiUsage.empty(), 5L);
        });
        AtomicLong executorBudgetMs = new AtomicLong(-1L);
        PlanExecutor planExecutor = mock(PlanExecutor.class);
        when(planExecutor.execute(any(), any(), any(), any(), anyLong())).thenAnswer(invocation -> {
            executorBudgetMs.set(invocation.getArgument(4));
            Thread.sleep(5L);
            return new PlanExecutor.Outcome(List.of(
                    new PlanExecutor.SubQuestionResult("q1", "进程 线程 区别",
                            PlanExecutor.STATUS_SUCCEED, 1, List.of(), null, 3L),
                    new PlanExecutor.SubQuestionResult("q2", "进程调度算法",
                            PlanExecutor.STATUS_SUCCEED, 1, List.of(), null, 3L)),
                    false, false, 6L);
        });

        AgentTracingProperties properties = new AgentTracingProperties();
        properties.setTimeoutSeconds(20);
        RecordingSupervisor supervisor = new RecordingSupervisor();
        KnowledgeAgentService agent = mockAgentService(List.of(
                Flux.just(chunk("[E1] 进程是资源分配单位，线程是调度单位。"))));
        streamer = new KnowledgeAssistantStreamer(
                agent, assistantService, objectMapper, tracingService, toolCallbackFactory,
                supervisor, properties, new EvidenceLedgerFactory(), AgentMetrics.noop(), null,
                classifier, enabledRuntime(), null, planner, planExecutor);

        streamer.stream(context(), new ByteArrayOutputStream());

        long totalBudgetMs = 20_000L;
        // 三段都从同一份预算扣时间：Planner > Executor > 合成，且都不超过总预算
        assertThat(plannerBudgetMs.get()).isPositive().isLessThanOrEqualTo(totalBudgetMs);
        assertThat(executorBudgetMs.get()).isPositive().isLessThanOrEqualTo(plannerBudgetMs.get());
        // 计划路径里 executeWithTimeout 只在合成阶段调用一次
        assertThat(supervisor.timeouts).hasSize(1);
        long synthesisBudgetMs = supervisor.timeouts.get(0).toMillis();
        assertThat(synthesisBudgetMs)
                .as("合成阶段拿到的是剩余预算，不是新开的 20s")
                .isPositive()
                .isLessThanOrEqualTo(executorBudgetMs.get())
                .isLessThan(totalBudgetMs)
                .isGreaterThan(KnowledgeAssistantStreamer.MIN_SYNTHESIS_BUDGET_MS);
    }

    /** P1-B2 的补检装配 + 指定 tracing 配置（用于把总预算调小）。 */
    private void createFallbackStreamer(KnowledgeAgentService agentService,
                                        AgentIntentClassifier classifier,
                                        AgentRuntimeProperties runtimeProperties,
                                        LearningResourceTools learningResourceTools,
                                        EvidenceLedgerFactory ledgerFactory,
                                        AgentTracingProperties tracingProperties) {
        this.agentService = agentService;
        streamer = new KnowledgeAssistantStreamer(
                agentService, assistantService, objectMapper, tracingService, toolCallbackFactory,
                new AgentRunSupervisor(), tracingProperties, ledgerFactory,
                AgentMetrics.noop(), null, classifier, runtimeProperties, learningResourceTools);
    }

    @Test
    void firstRoundToolContextCarriesRemainingRunBudget() {
        AgentToolCallGuard guard = new AgentToolCallGuard(3);
        ToolCallback raw = mock(ToolCallback.class);
        when(raw.getToolDefinition()).thenReturn(ToolDefinition.builder()
                .name("search_my_documents").description("测试").inputSchema("{\"type\":\"object\"}")
                .build());
        AtomicReference<ToolContext> captured = new AtomicReference<>();
        when(raw.call(any(), any())).thenAnswer(invocation -> {
            captured.set(invocation.getArgument(1));
            return "片段内容";
        });
        ToolCallback decorated = new TracingToolCallback(
                raw, tracingService, guard, new AgentTracingProperties());
        when(toolCallbackFactory.createForRun(any(), any())).thenReturn(new AgentRunTools(
                List.of(decorated), guard, new PerRunToolResultCache(objectMapper)));
        createStreamer(modelInvokingTool("search_my_documents",
                "{\"query\":\"时间片轮转\"}", "时间片轮转是一种调度算法。"));

        streamer.stream(context(), new ByteArrayOutputStream());

        Object budgetMs = captured.get().getContext()
                .get(AgentToolContextKeys.REMAINING_BUDGET_MS);
        assertThat(budgetMs).isInstanceOf(Long.class);
        assertThat((Long) budgetMs).isPositive().isLessThanOrEqualTo(75_000L);
    }

    @Test
    void fallbackSecondRoundSharesRunBudgetInsteadOfFreshTimeout() {
        stubRunWithTool(new AgentToolCallGuard(3), "search_my_documents", "未被调用");
        AgentIntentClassifier classifier = mock(AgentIntentClassifier.class);
        when(classifier.classify("你好")).thenReturn(AgentIntent.RETRIEVE);
        KnowledgeAgentService agent = mockAgentService(List.of(
                // 第一轮吃 1s（总预算 3s，只剩约 2s）
                Flux.just(chunk("第一段回答。")).delayElements(Duration.ofMillis(1_000L)),
                // 第二轮若要 2.5s：共享预算下会在大约 2s 处被总预算切断
                Flux.just(chunk("[E1] 第二段回答。")).delayElements(Duration.ofMillis(2_500L))));
        AgentTracingProperties tight = new AgentTracingProperties();
        tight.setTimeoutSeconds(3);
        createFallbackStreamer(agent, classifier, enabledRuntime(),
                retrievalToolReturningOneEvidence(), new EvidenceLedgerFactory(), tight);

        long startedNanos = System.nanoTime();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        streamer.stream(context(), output);
        long elapsedMs = Duration.ofNanos(System.nanoTime() - startedNanos).toMillis();

        String sse = output.toString(StandardCharsets.UTF_8);
        // 第一段保留、run 成功；第二轮被同一份预算切断，用户看不到第二段，也没有悬空分隔符
        assertThat(sse).contains("第一段回答。").contains("event: completed")
                .doesNotContain("event: error")
                .doesNotContain("根据你的笔记补充")
                .doesNotContain("第二段回答");
        ArgumentCaptor<String> persisted = ArgumentCaptor.forClass(String.class);
        verify(assistantService).completeAssistantMessage(eq(7L), eq(90L), persisted.capture(),
                any(), any());
        assertThat(persisted.getValue()).isEqualTo("第一段回答。"
                + KnowledgeAssistantStreamer.UNGROUNDED_NOTICE);
        // 第二轮确实发起了（两次 AI 调用），但只拿到“剩余预算”：若像改造前那样新开 3s，
        // 2.5s 的第二段会跑完并落进最终回答
        verify(agent, times(2)).streamAnswer(anyString(), anyList(), anyInt(), anyDouble(),
                anyList(), anyMap());
        verify(tracingService).recordRetrieveFallbackStep(eq(99L), eq("FAILED"), eq("你好"),
                contains("命中 1 条证据"), eq("AGENT_RUN_TIMEOUT"), anyLong());
        verify(tracingService).succeedRun(eq(99L), eq(persisted.getValue()), eq(0), eq(0));
        verify(tracingService, never()).failRun(any(), any(), any(), anyInt(), anyInt());
        assertThat(elapsedMs).isLessThan(4_500L);
    }

    @Test
    void fallbackSkipsSecondRoundAndLogsBudgetExhaustedWhenRetrievalEatsBudget() {
        stubRunWithTool(new AgentToolCallGuard(3), "search_my_documents", "未被调用");
        AgentIntentClassifier classifier = mock(AgentIntentClassifier.class);
        when(classifier.classify("你好")).thenReturn(AgentIntent.RETRIEVE);
        KnowledgeAgentService agent = mockAgentService(List.of(
                Flux.just(chunk("第一段回答。")),
                Flux.just(chunk("[E1] 第二段回答。"))));
        LearningResourceTools slowRetrieval = mock(LearningResourceTools.class);
        when(slowRetrieval.searchMyDocuments(anyString(), any(ToolContext.class)))
                .thenAnswer(invocation -> {
                    // 总预算 2s，检索要 2.2s：P1-D2b 起检索上限 = min(剩余预算, 10s) ≈ 2s，
                    // 因此检索会被剩余预算切断，来不及登记证据（改造前是裸调用，会睡满 2.2s）
                    Thread.sleep(2_200L);
                    ToolContext toolContext = invocation.getArgument(1);
                    EvidenceLedger ledger = (EvidenceLedger) toolContext.getContext()
                            .get(AgentToolContextKeys.EVIDENCE_LEDGER);
                    ledger.register("document_chunk", "42", 0, 0.71d, "操作系统第三章",
                            "进程是资源分配的基本单位");
                    return "[E1] [来源类型: document_chunk, 资源ID: 42]\n内容：进程是资源分配的基本单位";
                });
        AgentTracingProperties tight = new AgentTracingProperties();
        tight.setTimeoutSeconds(2);
        createFallbackStreamer(agent, classifier, enabledRuntime(), slowRetrieval,
                new EvidenceLedgerFactory(), tight);

        ListAppender<ILoggingEvent> appender = attachAppender(KnowledgeAssistantStreamer.class);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            streamer.stream(context(), output);
        } finally {
            detachAppender(KnowledgeAssistantStreamer.class, appender);
        }

        String sse = output.toString(StandardCharsets.UTF_8);
        assertThat(sse).contains("第一段回答。").contains("event: completed")
                .doesNotContain("event: error")
                .doesNotContain("根据你的笔记补充")
                .doesNotContain("第二段回答");
        // 检索被剩余预算切断 → 第二轮根本不发起：AI 只调用 1 次
        verify(agent, times(1)).streamAnswer(anyString(), anyList(), anyInt(), anyDouble(),
                anyList(), anyMap());
        verify(slowRetrieval).searchMyDocuments(eq("你好"), any(ToolContext.class));
        verify(tracingService).incrementRepairRound(99L);
        verify(tracingService).recordRetrieveFallbackStep(eq(99L), eq("FAILED"), eq("你好"),
                contains("检索被总预算切断"),
                eq(KnowledgeAssistantStreamer.FALLBACK_BUDGET_EXHAUSTED), anyLong());
        ArgumentCaptor<String> persisted = ArgumentCaptor.forClass(String.class);
        verify(assistantService).completeAssistantMessage(eq(7L), eq(90L), persisted.capture(),
                any(), any());
        assertThat(persisted.getValue()).isEqualTo("第一段回答。"
                + KnowledgeAssistantStreamer.UNGROUNDED_NOTICE);
        // 第一段保留、run 仍成功，绝不 failRun
        verify(tracingService).succeedRun(eq(99L), eq(persisted.getValue()), eq(0), eq(0));
        verify(tracingService, never()).failRun(any(), any(), any(), anyInt(), anyInt());
        // P1-D2b 统一日志：便于 P1-F 统计“哪一段把预算用完了”
        assertThat(appender.list).anySatisfy(event -> assertThat(event.getFormattedMessage())
                .contains("Agent 预算耗尽")
                .contains("runId=99")
                .contains("stage=RETRIEVE_FALLBACK")
                .contains("remainingMs=0"));
    }

    /**
     * P1-D2b：补检那次检索现在也受"总预算 + 单工具超时"双重约束（改造前是裸调用）。
     *
     * <p>`tool-timeout-seconds=1` + fake 检索睡 2s：检索必须在约 1s 处被切断，
     * 落 {@link KnowledgeAssistantStreamer#RETRIEVE_FALLBACK_TIMEOUT}，第一段回答保留、不 failRun。
     */
    @Test
    void fallbackRetrievalTimesOutAndKeepsFirstAnswer() {
        stubRunWithTool(new AgentToolCallGuard(3), "search_my_documents", "未被调用");
        AgentIntentClassifier classifier = mock(AgentIntentClassifier.class);
        when(classifier.classify("你好")).thenReturn(AgentIntent.RETRIEVE);
        KnowledgeAgentService agent = mockAgentService(List.of(
                Flux.just(chunk("第一段回答。")),
                Flux.just(chunk("[E1] 不该出现的第二段回答。"))));
        LearningResourceTools slowRetrieval = mock(LearningResourceTools.class);
        when(slowRetrieval.searchMyDocuments(anyString(), any(ToolContext.class)))
                .thenAnswer(invocation -> {
                    // 单工具超时 1s，这里睡 2s：必须被切断，不能真的等满
                    Thread.sleep(2_000L);
                    return "不该返回的检索结果";
                });
        AgentTracingProperties properties = new AgentTracingProperties();
        properties.setToolTimeoutSeconds(1);
        createFallbackStreamer(agent, classifier, enabledRuntime(), slowRetrieval,
                new EvidenceLedgerFactory(), properties);

        long startedNanos = System.nanoTime();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        streamer.stream(context(), output);
        long elapsedMs = Duration.ofNanos(System.nanoTime() - startedNanos).toMillis();

        String sse = output.toString(StandardCharsets.UTF_8);
        // 第一段保留、run 成功、SSE 无 error、没有悬空分隔符、第二轮内容不出现
        assertThat(sse).contains("第一段回答。").contains("event: completed")
                .doesNotContain("event: error")
                .doesNotContain("根据你的笔记补充")
                .doesNotContain("不该出现的第二段回答")
                .doesNotContain("不该返回的检索结果");
        verify(agent, times(1)).streamAnswer(anyString(), anyList(), anyInt(), anyDouble(),
                anyList(), anyMap());
        verify(slowRetrieval).searchMyDocuments(eq("你好"), any(ToolContext.class));
        verify(tracingService).recordRetrieveFallbackStep(eq(99L), eq("FAILED"), eq("你好"),
                contains("检索超时"),
                eq(KnowledgeAssistantStreamer.RETRIEVE_FALLBACK_TIMEOUT), anyLong());
        ArgumentCaptor<String> persisted = ArgumentCaptor.forClass(String.class);
        verify(assistantService).completeAssistantMessage(eq(7L), eq(90L), persisted.capture(),
                any(), any());
        assertThat(persisted.getValue()).isEqualTo("第一段回答。"
                + KnowledgeAssistantStreamer.UNGROUNDED_NOTICE);
        verify(tracingService).succeedRun(eq(99L), eq(persisted.getValue()), eq(0), eq(0));
        verify(tracingService, never()).failRun(any(), any(), any(), anyInt(), anyInt());
        // 真的被切断：整体远小于 fake 的 2s 睡眠
        assertThat(elapsedMs).isLessThan(1_800L);
    }

    /**
     * P1-D2b：进入补检时预算已耗尽 → <b>一次检索都不发</b>，落一条
     * {@code FALLBACK_BUDGET_EXHAUSTED} 的 agent_steps，第一段保留、不 failRun。
     *
     * <p>为什么需要测试替身：真实 supervisor 下"第一轮刚好在截止后才拿到答案"这个窗口只有纳秒级，
     * 用 {@code timeout-seconds=0} 又会让第一轮先被 0ms 超时切断（直接 failRun，走不到补检）。
     * {@link FirstRoundOverrunningSupervisor} 精确模拟这个竞态，因此断言是确定性的。
     */
    @Test
    void fallbackRetrievalSkippedWhenBudgetAlreadyExhausted() {
        stubRunWithTool(new AgentToolCallGuard(3), "search_my_documents", "未被调用");
        AgentIntentClassifier classifier = mock(AgentIntentClassifier.class);
        when(classifier.classify("你好")).thenReturn(AgentIntent.RETRIEVE);
        KnowledgeAgentService agent = mockAgentService(List.of(
                Flux.just(chunk("第一段回答。"))));
        LearningResourceTools retrieval = mock(LearningResourceTools.class);
        AgentTracingProperties tight = new AgentTracingProperties();
        tight.setTimeoutSeconds(1);
        this.agentService = agent;
        streamer = new KnowledgeAssistantStreamer(
                agent, assistantService, objectMapper, tracingService, toolCallbackFactory,
                new FirstRoundOverrunningSupervisor(1_200L), tight, new EvidenceLedgerFactory(),
                AgentMetrics.noop(), null, classifier, enabledRuntime(), retrieval);

        ListAppender<ILoggingEvent> appender = attachAppender(KnowledgeAssistantStreamer.class);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            streamer.stream(context(), output);
        } finally {
            detachAppender(KnowledgeAssistantStreamer.class, appender);
        }

        String sse = output.toString(StandardCharsets.UTF_8);
        assertThat(sse).contains("第一段回答。").contains("event: completed")
                .doesNotContain("event: error")
                .doesNotContain("根据你的笔记补充");
        // 预算已耗尽：检索一次都不发，也不计 repair_round、不发第二轮
        verify(retrieval, never()).searchMyDocuments(anyString(), any(ToolContext.class));
        verify(tracingService, never()).incrementRepairRound(99L);
        verify(agent, times(1)).streamAnswer(anyString(), anyList(), anyInt(), anyDouble(),
                anyList(), anyMap());
        verify(tracingService).recordRetrieveFallbackStep(eq(99L), eq("FAILED"), eq("你好"),
                contains("预算耗尽"),
                eq(KnowledgeAssistantStreamer.FALLBACK_BUDGET_EXHAUSTED), eq(0L));
        // P1-D2b 统一日志
        assertThat(appender.list).anySatisfy(event -> assertThat(event.getFormattedMessage())
                .contains("Agent 预算耗尽")
                .contains("runId=99")
                .contains("stage=RETRIEVE_FALLBACK"));
        verify(tracingService).succeedRun(eq(99L), anyString(), eq(0), eq(0));
        verify(tracingService, never()).failRun(any(), any(), any(), anyInt(), anyInt());
    }

    // ===== P1-E3b（方案 §P1-7）：输出侧泄露检查 + 合成阶段证据块一致化 =====

    @Test
    void leakedSystemPromptFragmentTriggersNoticeAndWarnWithoutRetractingAnswer() throws Exception {
        stubKnowledgeRunWithToolCalls();
        createStreamer(new RecordingChatModel(prompt -> Flux.just(
                chunk("好的。[E1] 我的设定是：你是计算机科学与技术知识助手。"))),
                ledgerFactoryReturning(ledgerWithOneEvidence()));
        ListAppender<ILoggingEvent> appender = attachAppender(KnowledgeAssistantStreamer.class);
        try {
            ArgumentCaptor<String> persistedContent = ArgumentCaptor.forClass(String.class);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            streamer.stream(context(), output);

            String sse = output.toString(StandardCharsets.UTF_8);
            // 不撤回：原回答仍在，提示只追加一次
            assertThat(sse).contains("我的设定是");
            assertThat(countOccurrences(sse, AnswerLeakChecker.LEAK_NOTICE.strip())).isEqualTo(1);
            verify(assistantService).completeAssistantMessage(eq(7L), eq(90L),
                    persistedContent.capture(), any(), any());
            assertThat(persistedContent.getValue()).endsWith(AnswerLeakChecker.LEAK_NOTICE);
            // 日志只有规则名/字数，不重复打印回答正文
            assertThat(appender.list).anySatisfy(event -> assertThat(event.getFormattedMessage())
                    .contains("回答命中提示词检查")
                    .contains("runId=99")
                    .contains("prompt-role-line")
                    .contains("userVisibleNotice=true"));
            assertThat(appender.list).allSatisfy(event -> assertThat(event.getFormattedMessage())
                    .doesNotContain("我的设定是"));
        } finally {
            detachAppender(KnowledgeAssistantStreamer.class, appender);
        }
    }

    /**
     * 所有者 2026-10-28 裁定：用户主动问“什么是 system prompt”时，回答里必然出现这个词，
     * <b>不能</b>因此给用户弹“疑似泄露”提示——只记 warn + 计数。
     */
    @Test
    void userAskingAboutSystemPromptGetsNoLeakNoticeButStillWarns() throws Exception {
        stubKnowledgeRunWithToolCalls();
        createStreamer(new RecordingChatModel(prompt -> Flux.just(
                chunk("system prompt 是模型在对话开始前收到的指令，用来规定角色与边界。[E1]"))),
                ledgerFactoryReturning(ledgerWithOneEvidence()));
        ListAppender<ILoggingEvent> appender = attachAppender(KnowledgeAssistantStreamer.class);
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            streamer.stream(contextWithUserMessage("什么是 system prompt"), output);

            String sse = output.toString(StandardCharsets.UTF_8);
            assertThat(sse).contains("system prompt 是模型在对话开始前收到的指令");
            assertThat(sse).doesNotContain(AnswerLeakChecker.LEAK_NOTICE.strip());
            assertThat(appender.list).anySatisfy(event -> assertThat(event.getFormattedMessage())
                    .contains("回答命中提示词检查")
                    .contains("system-prompt-en")
                    .contains("userVisibleNotice=false")
                    .contains("keywordOnlyCount=1"));
        } finally {
            detachAppender(KnowledgeAssistantStreamer.class, appender);
        }
    }

    /** 用户提问里已经出现“知识助手”时，回答复述该词不算泄露（回声排除）。 */
    @Test
    void exclusiveFragmentEchoedFromUserQuestionGetsNoNotice() throws Exception {
        stubKnowledgeRunWithToolCalls();
        createStreamer(new RecordingChatModel(prompt -> Flux.just(
                chunk("你是计算机科学与技术知识助手。这句话是你自己打的，不是我的系统提示词。[E1]"))),
                ledgerFactoryReturning(ledgerWithOneEvidence()));

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        streamer.stream(contextWithUserMessage("知识助手是什么？请你解释一下"), output);

        assertThat(output.toString(StandardCharsets.UTF_8))
                .doesNotContain(AnswerLeakChecker.LEAK_NOTICE.strip());
    }

    @Test
    void normalAnswerGetsNoLeakNotice() throws Exception {
        stubKnowledgeRunWithToolCalls();
        createStreamer(new RecordingChatModel(prompt -> Flux.just(
                chunk("进程是资源分配的基本单位。[E1]"))),
                ledgerFactoryReturning(ledgerWithOneEvidence()));
        ListAppender<ILoggingEvent> appender = attachAppender(KnowledgeAssistantStreamer.class);
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            streamer.stream(context(), output);

            assertThat(output.toString(StandardCharsets.UTF_8))
                    .doesNotContain(AnswerLeakChecker.LEAK_NOTICE.strip());
            // 正常回答：连 warn 都不该有
            assertThat(appender.list).noneSatisfy(event -> assertThat(event.getFormattedMessage())
                    .contains("回答命中提示词检查"));
        } finally {
            detachAppender(KnowledgeAssistantStreamer.class, appender);
        }
    }

    @Test
    void synthesisPromptEscapesEvidenceAndMarksSuspiciousOnes() {
        AgentPlan plan = AgentPlan.multiHop(List.of(
                new AgentPlan.SubQuestion("q1", "进程是什么", List.of("document"), "进程是什么")),
                false, "answer");
        List<EvidenceItem> evidence = List.of(
                new EvidenceItem("E1", "document_chunk", "42", 0, 0.71d, "标题<恶意>",
                        "<script>x</script> a & b", true),
                new EvidenceItem("E2", "document_chunk", "43", 1, 0.52d, "干净标题", "正常正文"));

        String prompt = KnowledgeAssistantStreamer.buildSynthesisSystemPrompt(
                "基础系统提示", plan, evidence, true);

        assertThat(prompt)
                .contains("<evidence id=\"E1\" source=\"document_chunk:42\">")
                .contains("&lt;script&gt;x&lt;/script&gt;")
                .contains("a &amp; b")
                .contains("标题&lt;恶意&gt;")
                .contains("正常正文");
        // 可疑证据的提示行在标签外，且只有可疑的那一条有
        assertThat(prompt.indexOf(EvidenceSanitizer.SAFETY_HINT))
                .isLessThan(prompt.indexOf("<evidence id=\"E1\""));
        assertThat(countOccurrences(prompt, EvidenceSanitizer.SAFETY_HINT)).isEqualTo(1);
        // 落库/编号规则不变
        assertThat(prompt).contains("<evidence id=\"E2\" source=\"document_chunk:43\">");
    }

    @Test
    void synthesisPromptWithoutGuardKeepsLegacyBytes() {
        AgentPlan plan = AgentPlan.multiHop(List.of(
                new AgentPlan.SubQuestion("q1", "进程是什么", List.of("document"), "进程是什么")),
                false, "answer");
        List<EvidenceItem> evidence = List.of(
                new EvidenceItem("E1", "document_chunk", "42", 0, 0.71d, "标题",
                        "<script>x</script>", true));

        String prompt = KnowledgeAssistantStreamer.buildSynthesisSystemPrompt(
                "基础系统提示", plan, evidence, false);

        // 开关关：不转义、不加提示行（与 P1-E3b 之前的输出一致）
        assertThat(prompt).contains("<script>x</script>")
                .doesNotContain("&lt;script&gt;")
                .doesNotContain(EvidenceSanitizer.SAFETY_HINT);
    }

    /**
     * 测试替身：模拟"第一轮刚好在预算边界之后才拿到答案"的竞态（真实 supervisor 下窗口只有纳秒级）。
     * 第一次调用先睡过预算再直接执行任务（不施加超时），之后委托给真实实现。
     */
    private static final class FirstRoundOverrunningSupervisor extends AgentRunSupervisor {

        private final long overrunMs;
        private final AtomicInteger calls = new AtomicInteger();

        private FirstRoundOverrunningSupervisor(long overrunMs) {
            this.overrunMs = overrunMs;
        }

        @Override
        public <T> T executeWithTimeout(Duration timeout, Callable<T> task) {
            if (calls.getAndIncrement() == 0) {
                try {
                    Thread.sleep(overrunMs);
                    return task.call();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AgentRunAbortException("AGENT_RUN_INTERRUPTED", "测试等待被中断", e);
                } catch (Exception e) {
                    throw new AgentRunAbortException("AGENT_RUN_FAILED", "测试任务执行失败", e);
                }
            }
            return super.executeWithTimeout(timeout, task);
        }
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
}
