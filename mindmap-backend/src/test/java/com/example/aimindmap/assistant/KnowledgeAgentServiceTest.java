package com.example.aimindmap.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.aimindmap.ai.AiChatMessage;
import com.example.aimindmap.ai.AiProperties;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import reactor.core.publisher.Flux;

class KnowledgeAgentServiceTest {

    @Test
    void streamAnswerJoinsDeltasAndBuildsPromptOnce() {
        RecordingChatModel chatModel = new RecordingChatModel(
                prompt -> Flux.just(chunk("你好"), chunk("，"), chunk("我是助手")));
        KnowledgeAgentService service = new KnowledgeAgentService(chatModel, new AiProperties());

        List<AiChatMessage> history = List.of(
                new AiChatMessage("user", "你好"),
                new AiChatMessage("assistant", "你好，有什么可以帮你？"),
                new AiChatMessage("user", "解释一下进程"));

        ToolCallback toolCallback = new StubToolCallback("demo_tool");
        Map<String, Object> toolContextMap = Map.of("userId", 7L, "runId", 99L);
        List<ChatResponse> chunks = service.streamAnswer("系统提示词正文", history, 1234, 0.3d,
                        List.of(toolCallback), toolContextMap)
                .collectList()
                .block();

        String joinedText = chunks.stream()
                .map(chunk -> chunk.getResult().getOutput().getText())
                .reduce("", String::concat);
        assertThat(joinedText).isEqualTo("你好，我是助手");

        Prompt prompt = chatModel.lastPrompt;
        assertThat(prompt).isNotNull();
        List<Message> instructions = prompt.getInstructions();
        assertThat(instructions).hasSize(4);
        assertThat(instructions.get(0).getMessageType()).isEqualTo(MessageType.SYSTEM);
        assertThat(instructions.get(0).getText()).isEqualTo("系统提示词正文");
        assertThat(instructions.stream().filter(m -> m.getMessageType() == MessageType.SYSTEM)).hasSize(1);
        assertThat(instructions.get(1).getMessageType()).isEqualTo(MessageType.USER);
        assertThat(instructions.get(1).getText()).isEqualTo("你好");
        assertThat(instructions.get(2).getMessageType()).isEqualTo(MessageType.ASSISTANT);
        assertThat(instructions.get(2).getText()).isEqualTo("你好，有什么可以帮你？");
        assertThat(instructions.get(3).getMessageType()).isEqualTo(MessageType.USER);
        assertThat(instructions.get(3).getText()).isEqualTo("解释一下进程");

        OpenAiChatOptions options = (OpenAiChatOptions) prompt.getOptions();
        assertThat(options.getMaxTokens()).isEqualTo(1234);
        assertThat(options.getStreamUsage()).isTrue();
        assertThat(options.getTemperature()).isEqualTo(0.3d);
        assertThat(options.getToolCallbacks()).hasSize(1);
        assertThat(options.getToolCallbacks().get(0).getToolDefinition().name()).isEqualTo("demo_tool");
        assertThat(options.getToolContext()).containsEntry("userId", 7L).containsEntry("runId", 99L);
    }

    @Test
    void providerAndModelComeFromAiProperties() {
        AiProperties aiProperties = new AiProperties();

        KnowledgeAgentService service =
                new KnowledgeAgentService(new RecordingChatModel(prompt -> Flux.empty()), aiProperties);

        assertThat(service.provider()).isEqualTo("deepseek");
        assertThat(service.model()).isEqualTo("deepseek-chat");
    }

    @Test
    void streamFailurePropagatesAiException() {
        RecordingChatModel chatModel = new RecordingChatModel(
                prompt -> Flux.error(new TransientAiException("上游服务暂时不可用")));
        KnowledgeAgentService service = new KnowledgeAgentService(chatModel, new AiProperties());

        assertThatThrownBy(() -> service.streamAnswer("系统提示词正文",
                        List.of(new AiChatMessage("user", "你好")), 100, 0.3d,
                        List.of(), Map.of())
                .collectList()
                .block())
                .isInstanceOf(TransientAiException.class)
                .hasMessageContaining("上游服务暂时不可用");
    }

    @Test
    void emptyUpstreamProducesEmptyFlux() {
        RecordingChatModel chatModel = new RecordingChatModel(prompt -> Flux.empty());
        KnowledgeAgentService service = new KnowledgeAgentService(chatModel, new AiProperties());

        List<ChatResponse> chunks = service.streamAnswer("系统提示词正文",
                        List.of(new AiChatMessage("user", "你好")), 100, 0.3d,
                        List.of(), Map.of())
                .collectList()
                .block();

        assertThat(chunks).isEmpty();
    }

    private static ChatResponse chunk(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    private static final class StubToolCallback implements ToolCallback {

        private final ToolDefinition definition;

        private StubToolCallback(String name) {
            this.definition = ToolDefinition.builder().name(name).description("测试工具")
                    .inputSchema("{\"type\":\"object\"}").build();
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return definition;
        }

        @Override
        public String call(String toolInput) {
            return "ok";
        }

        @Override
        public String call(String toolInput, ToolContext toolContext) {
            return "ok";
        }
    }

    private static final class RecordingChatModel implements ChatModel {

        private final Function<Prompt, Flux<ChatResponse>> handler;
        private Prompt lastPrompt;

        private RecordingChatModel(Function<Prompt, Flux<ChatResponse>> handler) {
            this.handler = handler;
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            this.lastPrompt = prompt;
            return handler.apply(prompt);
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            throw new UnsupportedOperationException("本测试只覆盖流式路径");
        }
    }
}
