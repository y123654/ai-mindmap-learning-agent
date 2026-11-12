package com.example.aimindmap.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.aimindmap.ai.AiProperties;
import com.example.aimindmap.mqsupport.outbox.IdempotentConsumptionRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.retry.TransientAiException;

class SessionSummaryServiceTest {

    private AiChatSessionRepository sessionRepository;
    private AiChatMessageRepository messageRepository;
    private RecordingChatModel chatModel;
    private AiProperties aiProperties;
    private AiChatSessionEntity session;
    private IdempotentConsumptionRepository idempotentConsumptionRepository;
    private SessionSummaryService service;

    @BeforeEach
    void setUp() {
        sessionRepository = mock(AiChatSessionRepository.class);
        messageRepository = mock(AiChatMessageRepository.class);
        idempotentConsumptionRepository = mock(IdempotentConsumptionRepository.class);
        chatModel = new RecordingChatModel(prompt -> textResponse("摘".repeat(900)));
        aiProperties = new AiProperties();
        session = new AiChatSessionEntity();
        session.setUserId(1L);
        setId(session, 10L);
        when(sessionRepository.findByIdAndUserId(10L, 1L)).thenReturn(Optional.of(session));
        when(sessionRepository.save(any(AiChatSessionEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        service = new SessionSummaryService(
                sessionRepository, messageRepository, chatModel, aiProperties, null,
                idempotentConsumptionRepository);
        setField(service, "self", service);
    }

    @Test
    void doesNothingAt20Messages() {
        stubMessages(messages(20));

        service.generateSummary(10L, 1L);

        assertThat(chatModel.lastPrompt).isNull();
        assertThat(session.getSummary()).isNull();
    }

    @Test
    void firstSummaryAt21CoversOlder11AndKeepsRecent10() {
        stubMessages(messages(21));

        service.generateSummary(10L, 1L);

        Prompt prompt = chatModel.lastPrompt;
        assertThat(prompt.getInstructions().get(0).getText()).contains("800~1200 字符");
        String userText = prompt.getInstructions().get(1).getText();
        assertThat(userText).contains("较早的对话记录").doesNotContain("已有摘要");
        assertThat(userText).contains("message-1").contains("message-11");
        assertThat(userText).doesNotContain("message-12");
        assertThat(session.getSummary()).hasSize(900);
        assertThat(session.getSummarizedUntilMessageId()).isEqualTo(11L);
        assertThat(session.getSummaryUpdatedAt()).isNotNull();
    }

    @Test
    void incrementalUpdateMergesPreviousSummaryAndOnlyNewMessages() {
        session.setSummary("旧摘要内容");
        session.setSummarizedUntilMessageId(11L);
        stubMessages(messages(25));

        service.generateSummary(10L, 1L);

        String userText = chatModel.lastPrompt.getInstructions().get(1).getText();
        assertThat(userText).contains("已有摘要").contains("旧摘要内容");
        assertThat(userText).contains("message-12").contains("message-15");
        assertThat(userText).doesNotContain("message-16");
        assertThat(session.getSummarizedUntilMessageId()).isEqualTo(15L);
    }

    @Test
    void skipsWhenAllOlderMessagesAlreadySummarized() {
        session.setSummary("旧摘要内容");
        session.setSummarizedUntilMessageId(15L);
        stubMessages(messages(25));

        service.generateSummary(10L, 1L);

        assertThat(chatModel.lastPrompt).isNull();
    }

    @Test
    void aiFailureIsSwallowedAndDoesNotChangeSession() {
        chatModel = new RecordingChatModel(prompt -> {
            throw new TransientAiException("上游暂时不可用");
        });
        service = new SessionSummaryService(
                sessionRepository, messageRepository, chatModel, aiProperties, null,
                idempotentConsumptionRepository);
        stubMessages(messages(21));

        assertThatCode(() -> service.generateSummary(10L, 1L)).doesNotThrowAnyException();
        assertThat(session.getSummary()).isNull();
    }

    @Test
    void unexpectedFailureIsSwallowed() {
        chatModel = new RecordingChatModel(prompt -> {
            throw new IllegalStateException("未预期错误");
        });
        service = new SessionSummaryService(
                sessionRepository, messageRepository, chatModel, aiProperties, null,
                idempotentConsumptionRepository);
        stubMessages(messages(21));

        assertThatCode(() -> service.generateSummary(10L, 1L)).doesNotThrowAnyException();
    }

    @Test
    void overLongSummaryIsTruncatedTo1200() {
        chatModel = new RecordingChatModel(prompt -> textResponse("长".repeat(1300)));
        service = new SessionSummaryService(
                sessionRepository, messageRepository, chatModel, aiProperties, null,
                idempotentConsumptionRepository);
        setField(service, "self", service);
        stubMessages(messages(21));

        service.generateSummary(10L, 1L);

        assertThat(session.getSummary()).hasSize(1200);
    }

    @Test
    void emptyLlmOutputSkipsSaving() {
        chatModel = new RecordingChatModel(prompt -> textResponse("   "));
        service = new SessionSummaryService(
                sessionRepository, messageRepository, chatModel, aiProperties, null,
                idempotentConsumptionRepository);
        stubMessages(messages(21));

        service.generateSummary(10L, 1L);

        assertThat(session.getSummary()).isNull();
        verify(sessionRepository, never()).save(any(AiChatSessionEntity.class));
    }

    @Test
    void skipsWhenSessionNoLongerOwned() {
        when(sessionRepository.findByIdAndUserId(99L, 1L)).thenReturn(Optional.empty());

        service.generateSummary(99L, 1L);

        assertThat(chatModel.lastPrompt).isNull();
    }

    private void stubMessages(List<AiChatMessageEntity> messages) {
        when(messageRepository.findBySessionIdOrderByIdAsc(10L)).thenReturn(messages);
    }

    private static List<AiChatMessageEntity> messages(int count) {
        List<AiChatMessageEntity> result = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            AiChatMessageEntity message = new AiChatMessageEntity();
            message.setSessionId(10L);
            message.setRole(i % 2 == 0 ? "assistant" : "user");
            message.setContent("message-" + i);
            setId(message, (long) i);
            result.add(message);
        }
        return result;
    }

    private static ChatResponse textResponse(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    private static void setId(Object entity, Long id) {
        setField(entity, "id", id);
    }

    private static void setField(Object target, String fieldName, Object value) {
        try {
            var field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static final class RecordingChatModel implements ChatModel {

        private final Function<Prompt, ChatResponse> handler;
        private Prompt lastPrompt;

        private RecordingChatModel(Function<Prompt, ChatResponse> handler) {
            this.handler = handler;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            this.lastPrompt = prompt;
            return handler.apply(prompt);
        }

        @Override
        public reactor.core.publisher.Flux<ChatResponse> stream(Prompt prompt) {
            throw new UnsupportedOperationException("本测试只覆盖非流式路径");
        }
    }
}
