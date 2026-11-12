package com.example.aimindmap.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.aimindmap.ai.AiProperties;
import com.example.aimindmap.assistant.SessionSummaryService.SummaryOutcome;
import com.example.aimindmap.mqsupport.RetriableTaskException;
import com.example.aimindmap.mqsupport.outbox.IdempotentConsumptionEntity;
import com.example.aimindmap.mqsupport.outbox.IdempotentConsumptionRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * 阶段六-3：MQ 入口 handleSummaryMessage 的纯 Mockito 单测
 * （不连真实 LLM/DB/MQ）。降级入口 generateSummary 的 catch-all 行为保持也在此覆盖。
 */
class SessionSummaryServiceMqTest {

    private static final long SESSION_ID = 10L;
    private static final long USER_ID = 1L;
    private static final long TRIGGER_MESSAGE_ID = 50L;
    private static final String EXPECTED_MESSAGE_ID = "SESSION_SUMMARY:" + TRIGGER_MESSAGE_ID;

    private AiChatSessionRepository sessionRepository;
    private AiChatMessageRepository messageRepository;
    private ChatModel chatModel;
    private IdempotentConsumptionRepository idempotentRepository;
    private AiChatSessionEntity session;
    private SessionSummaryService service;

    @BeforeEach
    void setUp() {
        sessionRepository = mock(AiChatSessionRepository.class);
        messageRepository = mock(AiChatMessageRepository.class);
        chatModel = mock(ChatModel.class);
        idempotentRepository = mock(IdempotentConsumptionRepository.class);
        AiProperties aiProperties = new AiProperties();

        session = new AiChatSessionEntity();
        session.setUserId(USER_ID);
        setId(session, SESSION_ID);

        AiChatMessageEntity trigger = new AiChatMessageEntity();
        trigger.setSessionId(SESSION_ID);
        trigger.setRole("assistant");
        setId(trigger, TRIGGER_MESSAGE_ID);

        when(messageRepository.findById(TRIGGER_MESSAGE_ID)).thenReturn(Optional.of(trigger));
        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));
        when(sessionRepository.findByIdAndUserId(SESSION_ID, USER_ID)).thenReturn(Optional.of(session));
        when(sessionRepository.save(any(AiChatSessionEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(chatModel.call(any(Prompt.class))).thenReturn(textResponse("摘".repeat(900)));

        service = new SessionSummaryService(
                sessionRepository, messageRepository, chatModel, aiProperties, null,
                idempotentRepository);
        setField(service, "self", service);
    }

    // ---------- SUMMARIZED：三列更新 + 幂等行同事务写入 ----------

    @Test
    void summarizedUpdatesColumnsAndWritesIdempotentRow() {
        stubMessages(22);

        SummaryOutcome outcome = service.handleSummaryMessage(TRIGGER_MESSAGE_ID);

        assertThat(outcome).isEqualTo(SummaryOutcome.SUMMARIZED);
        assertThat(session.getSummary()).hasSize(900);
        assertThat(session.getSummarizedUntilMessageId()).isEqualTo(12L);
        assertThat(session.getSummaryUpdatedAt()).isNotNull();
        ArgumentCaptor<IdempotentConsumptionEntity> captor =
                ArgumentCaptor.forClass(IdempotentConsumptionEntity.class);
        verify(idempotentRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getMessageId()).isEqualTo(EXPECTED_MESSAGE_ID);
    }

    // ---------- SKIPPED 三种核心来源 + 会话不存在：都写幂等行 ----------

    @Test
    void skippedWhenMessagesAtThresholdWritesMarkConsumed() {
        stubMessages(20);

        SummaryOutcome outcome = service.handleSummaryMessage(TRIGGER_MESSAGE_ID);

        assertThat(outcome).isEqualTo(SummaryOutcome.SKIPPED);
        verify(chatModel, never()).call(any(Prompt.class));
        verifyIdempotentRow();
    }

    @Test
    void skippedWhenNoNewBatchWritesMarkConsumed() {
        session.setSummary("旧摘要内容");
        session.setSummarizedUntilMessageId(15L);
        stubMessages(25);

        SummaryOutcome outcome = service.handleSummaryMessage(TRIGGER_MESSAGE_ID);

        assertThat(outcome).isEqualTo(SummaryOutcome.SKIPPED);
        verify(chatModel, never()).call(any(Prompt.class));
        verifyIdempotentRow();
    }

    @Test
    void skippedWhenLlmReturnsBlankWritesMarkConsumed() {
        stubMessages(21);
        when(chatModel.call(any(Prompt.class))).thenReturn(textResponse("   "));

        SummaryOutcome outcome = service.handleSummaryMessage(TRIGGER_MESSAGE_ID);

        assertThat(outcome).isEqualTo(SummaryOutcome.SKIPPED);
        assertThat(session.getSummary()).isNull();
        verifyIdempotentRow();
    }

    @Test
    void skippedWhenSessionMissingWritesMarkConsumed() {
        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.empty());

        SummaryOutcome outcome = service.handleSummaryMessage(TRIGGER_MESSAGE_ID);

        assertThat(outcome).isEqualTo(SummaryOutcome.SKIPPED);
        verify(chatModel, never()).call(any(Prompt.class));
        verifyIdempotentRow();
    }

    // ---------- CONFIGURATION：不落业务状态、不写幂等行 ----------

    @Test
    void nonTransientAiExceptionReturnsConfiguration() {
        stubMessages(21);
        when(chatModel.call(any(Prompt.class)))
                .thenThrow(new NonTransientAiException("配置错误"));

        SummaryOutcome outcome = service.handleSummaryMessage(TRIGGER_MESSAGE_ID);

        assertThat(outcome).isEqualTo(SummaryOutcome.CONFIGURATION);
        assertThat(session.getSummary()).isNull();
        verifyNoInteractions(idempotentRepository);
    }

    @Test
    void webClient400ReturnsConfiguration() {
        stubMessages(21);
        when(chatModel.call(any(Prompt.class))).thenThrow(httpError(400));

        SummaryOutcome outcome = service.handleSummaryMessage(TRIGGER_MESSAGE_ID);

        assertThat(outcome).isEqualTo(SummaryOutcome.CONFIGURATION);
        verifyNoInteractions(idempotentRepository);
    }

    @Test
    void webClient401ReturnsConfiguration() {
        stubMessages(21);
        when(chatModel.call(any(Prompt.class))).thenThrow(httpError(401));

        SummaryOutcome outcome = service.handleSummaryMessage(TRIGGER_MESSAGE_ID);

        assertThat(outcome).isEqualTo(SummaryOutcome.CONFIGURATION);
        verifyNoInteractions(idempotentRepository);
    }

    // ---------- TRIGGER_MISSING：触发消息行不存在 ----------

    @Test
    void missingTriggerMessageReturnsTriggerMissing() {
        when(messageRepository.findById(TRIGGER_MESSAGE_ID)).thenReturn(Optional.empty());

        SummaryOutcome outcome = service.handleSummaryMessage(TRIGGER_MESSAGE_ID);

        assertThat(outcome).isEqualTo(SummaryOutcome.TRIGGER_MISSING);
        verify(sessionRepository, never()).findById(any());
        verifyNoInteractions(idempotentRepository);
    }

    // ---------- 可重试：Transient / 429 / 5xx / DataAccessException → RetriableTaskException ----------

    @Test
    void transientAiExceptionThrowsRetriable() {
        stubMessages(21);
        when(chatModel.call(any(Prompt.class)))
                .thenThrow(new TransientAiException("上游暂时不可用"));

        assertThatThrownBy(() -> service.handleSummaryMessage(TRIGGER_MESSAGE_ID))
                .isInstanceOf(RetriableTaskException.class);
        verifyNoInteractions(idempotentRepository);
    }

    @Test
    void webClient429ThrowsRetriable() {
        stubMessages(21);
        when(chatModel.call(any(Prompt.class))).thenThrow(httpError(429));

        assertThatThrownBy(() -> service.handleSummaryMessage(TRIGGER_MESSAGE_ID))
                .isInstanceOf(RetriableTaskException.class);
    }

    @Test
    void webClient500ThrowsRetriable() {
        stubMessages(21);
        when(chatModel.call(any(Prompt.class))).thenThrow(httpError(503));

        assertThatThrownBy(() -> service.handleSummaryMessage(TRIGGER_MESSAGE_ID))
                .isInstanceOf(RetriableTaskException.class);
    }

    @Test
    void dataAccessExceptionLoadingTriggerThrowsRetriable() {
        when(messageRepository.findById(TRIGGER_MESSAGE_ID))
                .thenThrow(new DataAccessResourceFailureException("db down"));

        assertThatThrownBy(() -> service.handleSummaryMessage(TRIGGER_MESSAGE_ID))
                .isInstanceOf(RetriableTaskException.class);
    }

    @Test
    void dataAccessExceptionLoadingMessagesThrowsRetriable() {
        stubMessages(21);
        when(messageRepository.findBySessionIdOrderByIdAsc(SESSION_ID))
                .thenThrow(new DataAccessResourceFailureException("db down"));

        assertThatThrownBy(() -> service.handleSummaryMessage(TRIGGER_MESSAGE_ID))
                .isInstanceOf(RetriableTaskException.class);
    }

    // ---------- 降级入口 catch-all 行为保持：异常不外抛 ----------

    @Test
    void legacyEntrypointSwallowsTransientAiException() {
        stubMessages(21);
        when(chatModel.call(any(Prompt.class)))
                .thenThrow(new TransientAiException("上游暂时不可用"));

        assertThatCode(() -> service.generateSummary(SESSION_ID, USER_ID))
                .doesNotThrowAnyException();
        assertThat(session.getSummary()).isNull();
    }

    @Test
    void legacyEntrypointSwallowsUnexpectedException() {
        stubMessages(21);
        when(chatModel.call(any(Prompt.class))).thenThrow(new IllegalStateException("boom"));

        assertThatCode(() -> service.generateSummary(SESSION_ID, USER_ID))
                .doesNotThrowAnyException();
    }

    // ---------- helpers ----------

    private void verifyIdempotentRow() {
        ArgumentCaptor<IdempotentConsumptionEntity> captor =
                ArgumentCaptor.forClass(IdempotentConsumptionEntity.class);
        verify(idempotentRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getMessageId()).isEqualTo(EXPECTED_MESSAGE_ID);
    }

    private void stubMessages(int count) {
        List<AiChatMessageEntity> messages = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            AiChatMessageEntity message = new AiChatMessageEntity();
            message.setSessionId(SESSION_ID);
            message.setRole(i % 2 == 0 ? "assistant" : "user");
            message.setContent("message-" + i);
            setId(message, (long) i);
            messages.add(message);
        }
        when(messageRepository.findBySessionIdOrderByIdAsc(SESSION_ID)).thenReturn(messages);
    }

    private static WebClientResponseException httpError(int status) {
        return WebClientResponseException.create(status, "error-" + status, new HttpHeaders(),
                new byte[0], null);
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
}
