package com.example.aimindmap.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.aimindmap.agentmemory.AgentMemoryService;
import com.example.aimindmap.agentmemory.ExplicitMemoryRecognizer;
import com.example.aimindmap.agentmemory.MemoryVectorService;
import com.example.aimindmap.agentcontext.TokenBudgetService;
import com.example.aimindmap.agentcontext.TokenEstimator;
import com.example.aimindmap.ai.AiProperties;
import com.example.aimindmap.auth.ExternalIdCodec;
import com.example.aimindmap.auth.UserRepository;
import com.example.aimindmap.mqsupport.TaskMessage;
import com.example.aimindmap.mqsupport.TaskType;
import com.example.aimindmap.mqsupport.outbox.OutboxEventService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

/**
 * 阶段六-3：completeAssistantMessage 双模式纯 Mockito 单测。
 * mq=true 同事务写 outbox（taskId=助手消息 ID、entityId=sessionId、op=SUMMARY）且不发事件；
 * mq=false 发 SessionSummaryRequested 事件且不写 outbox。
 */
class KnowledgeAssistantServiceMqTest {

    private UserRepository userRepository;
    private AiChatSessionRepository sessionRepository;
    private AiChatMessageRepository messageRepository;
    private ChatRecentMessageCache recentMessageCache;
    private ApplicationEventPublisher eventPublisher;
    private MemoryVectorService memoryVectorService;
    private AgentMemoryService agentMemoryService;
    private OutboxEventService outboxEventService;
    private final ExternalIdCodec idCodec = new ExternalIdCodec("unit-test-external-id-salt");

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        sessionRepository = mock(AiChatSessionRepository.class);
        messageRepository = mock(AiChatMessageRepository.class);
        // 阶段七-2 适配：显式 stub get 恒 null（未命中），既有断言语义零改
        // （Mockito 对 List 返回默认给空集合，会被当成「命中空快照」，故必须显式 stub）
        recentMessageCache = mock(ChatRecentMessageCache.class);
        when(recentMessageCache.get(any(), anyInt())).thenReturn(null);
        eventPublisher = mock(ApplicationEventPublisher.class);
        memoryVectorService = mock(MemoryVectorService.class);
        agentMemoryService = mock(AgentMemoryService.class);
        outboxEventService = mock(OutboxEventService.class);

        AiChatSessionEntity session = new AiChatSessionEntity();
        session.setUserId(1L);
        setId(session, 10L);
        when(sessionRepository.findByIdAndUserId(10L, 1L)).thenReturn(Optional.of(session));
        when(messageRepository.save(any(AiChatMessageEntity.class))).thenAnswer(invocation -> {
            AiChatMessageEntity entity = invocation.getArgument(0);
            setId(entity, 30L);
            return entity;
        });
        when(sessionRepository.save(any(AiChatSessionEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void mqEnabledWritesOutboxInSameTransactionAndDoesNotPublishEvent() {
        KnowledgeAssistantService service = newService(true);

        service.completeAssistantMessage(1L, 10L, "这是助手的完整回答。", "deepseek", "deepseek-chat");

        ArgumentCaptor<TaskMessage> captor = ArgumentCaptor.forClass(TaskMessage.class);
        verify(outboxEventService).recordPending(captor.capture());
        TaskMessage message = captor.getValue();
        assertThat(message.taskType()).isEqualTo(TaskType.SESSION_SUMMARY);
        assertThat(message.taskId()).isEqualTo(30L);
        assertThat(message.entityId()).isEqualTo("10");
        assertThat(message.op()).isEqualTo("SUMMARY");
        assertThat(message.occurredAt()).isNotNull();
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void mqDisabledPublishesSpringEventAndDoesNotWriteOutbox() {
        KnowledgeAssistantService service = newService(false);

        service.completeAssistantMessage(1L, 10L, "这是助手的完整回答。", "deepseek", "deepseek-chat");

        ArgumentCaptor<SessionSummaryRequested> captor =
                ArgumentCaptor.forClass(SessionSummaryRequested.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().sessionId()).isEqualTo(10L);
        assertThat(captor.getValue().userId()).isEqualTo(1L);
        verify(outboxEventService, never()).recordPending(any());
    }

    private KnowledgeAssistantService newService(boolean mqEnabled) {
        AiProperties aiProperties = new AiProperties();
        TokenBudgetService tokenBudgetService =
                new TokenBudgetService(new TokenEstimator(aiProperties), aiProperties);
        KnowledgeAssistantService service = new KnowledgeAssistantService(
                userRepository, sessionRepository, messageRepository, recentMessageCache,
                new KnowledgeAssistantPromptService(), tokenBudgetService, idCodec,
                eventPublisher, new ExplicitMemoryRecognizer(), memoryVectorService,
                agentMemoryService, null, outboxEventService, mqEnabled);
        setField(service, "self", service);
        return service;
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
