package com.example.aimindmap.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.aimindmap.agentmemory.AgentMemoryEntity;
import com.example.aimindmap.agentmemory.AgentMemoryRepository;
import com.example.aimindmap.agentmemory.AgentMemoryService;
import com.example.aimindmap.agentmemory.ExplicitMemoryRecognizer;
import com.example.aimindmap.agentmemory.MemoryDecision;
import com.example.aimindmap.agentmemory.MemoryVectorService;
import com.example.aimindmap.agentcontext.TokenBudgetService;
import com.example.aimindmap.agentcontext.TokenEstimator;
import com.example.aimindmap.ai.AiProperties;
import com.example.aimindmap.auth.ExternalIdCodec;
import com.example.aimindmap.auth.UserEntity;
import com.example.aimindmap.auth.UserRepository;
import com.example.aimindmap.common.error.ApiException;
import com.example.aimindmap.mqsupport.outbox.OutboxEventService;
import com.example.aimindmap.vectorindex.VectorSearchService;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class KnowledgeAssistantServiceTest {

    private UserRepository userRepository;
    private AiChatSessionRepository sessionRepository;
    private AiChatMessageRepository messageRepository;
    private ChatRecentMessageCache recentMessageCache;
    private ApplicationEventPublisher eventPublisher;
    private MemoryVectorService memoryVectorService;
    private AgentMemoryService agentMemoryService;
    private final AiProperties aiProperties = new AiProperties();
    private final ExternalIdCodec idCodec = new ExternalIdCodec("unit-test-external-id-salt");
    private final ExplicitMemoryRecognizer recognizer = new ExplicitMemoryRecognizer();
    private KnowledgeAssistantService service;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        sessionRepository = mock(AiChatSessionRepository.class);
        messageRepository = mock(AiChatMessageRepository.class);
        // 阶段七-2 适配：显式 stub get 恒 null（未命中），既有用例仍走查库回源路径，断言语义零改。
        // 注意 Mockito 对 List 返回默认给空集合（会被当成「命中空快照」短路查库），必须显式 stub
        recentMessageCache = mock(ChatRecentMessageCache.class);
        when(recentMessageCache.get(any(), anyInt())).thenReturn(null);
        eventPublisher = mock(ApplicationEventPublisher.class);
        memoryVectorService = mock(MemoryVectorService.class);
        agentMemoryService = mock(AgentMemoryService.class);
        // P1-6 适配：调用点改为二参（userId, 本轮 content）；mock 打桩同步为二参，断言语义零改
        when(agentMemoryService.buildInjection(any(), any()))
                .thenReturn(new AgentMemoryService.MemoryInjection("", List.of()));
        service = newService(agentMemoryService);
        setField(service, "self", service);
    }

    /** 用给定的记忆服务组装被测服务（P1-6 事务边界用例需要真实的 AgentMemoryService）。 */
    private KnowledgeAssistantService newService(AgentMemoryService memoryService) {
        TokenBudgetService tokenBudgetService =
                new TokenBudgetService(new TokenEstimator(aiProperties), aiProperties);
        KnowledgeAssistantService created = new KnowledgeAssistantService(
                userRepository, sessionRepository, messageRepository, recentMessageCache,
                new KnowledgeAssistantPromptService(), tokenBudgetService, idCodec,
                eventPublisher, recognizer, memoryVectorService, memoryService, null,
                mock(OutboxEventService.class), false);
        setField(created, "self", created);
        return created;
    }

    @Test
    void createSessionUsesDefaultTitleAndCurrentUserId() {
        UserEntity user = user(1L, "alice", "计算机科学与技术");
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(sessionRepository.save(any(AiChatSessionEntity.class))).thenAnswer(invocation -> {
            AiChatSessionEntity entity = invocation.getArgument(0);
            setId(entity, 10L);
            return entity;
        });

        var response = service.createSession(1L, new KnowledgeAssistantDtos.CreateSessionRequest(null));

        assertThat(response.id()).isEqualTo(idCodec.encode(10L));
        assertThat(response.title()).isEqualTo("新对话");
        ArgumentCaptor<AiChatSessionEntity> captor = ArgumentCaptor.forClass(AiChatSessionEntity.class);
        verify(sessionRepository).save(captor.capture());
        assertThat(captor.getValue().getUserId()).isEqualTo(1L);
    }

    @Test
    void prepareUserMessageSavesQuestionMakesTitleAndLoadsHistory() {
        stubUserAndSession(1L, 10L, "新对话");
        AiChatMessageEntity oldAssistant = message(20L, 10L, "assistant", "你好，我是你的知识助手。");
        AiChatMessageEntity userQuestion = message(21L, 10L, "user", "请解释进程和线程的区别");
        when(messageRepository.countBySessionId(10L)).thenReturn(1L);
        when(messageRepository.save(any(AiChatMessageEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(messageRepository.findTop30BySessionIdOrderByIdDesc(10L))
                .thenReturn(List.of(userQuestion, oldAssistant));
        when(sessionRepository.save(any(AiChatSessionEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var context = service.prepareUserMessage(1L, 10L, "  请解释进程和线程的区别  ");

        assertThat(context.userId()).isEqualTo(1L);
        assertThat(context.sessionId()).isEqualTo(10L);
        assertThat(context.systemPrompt()).contains("你是计算机科学与技术知识助手");
        assertThat(context.systemPrompt()).contains("我是你的知识助手");
        assertThat(context.messages()).hasSize(2);
        assertThat(context.messages().get(1).role()).isEqualTo("user");
        assertThat(context.messages().get(1).content()).isEqualTo("请解释进程和线程的区别");
        // 阶段二 C：短对话不裁剪，动态输出仍是封顶值 2000，并带出估算输入 token
        assertThat(context.effectiveMaxOutputTokens()).isEqualTo(2000);
        assertThat(context.estimatedInputTokens()).isPositive();
    }

    @Test
    void longHistoryIsTrimmedByTokenBudgetAndKeepsLastUserQuestion() {
        stubUserAndSession(1L, 10L, "新对话");
        String historyText = "重复的历史对话内容，需要稳定占用一批 cl100k token 数量用于裁剪测试。";
        String currentQuestion = "这是本轮最新提出的问题，裁剪时必须保留下来";
        List<AiChatMessageEntity> descending = new ArrayList<>();
        descending.add(message(26L, 10L, "user", currentQuestion));
        descending.add(message(25L, 10L, "assistant", historyText));
        descending.add(message(24L, 10L, "user", historyText));
        descending.add(message(23L, 10L, "assistant", historyText));
        descending.add(message(22L, 10L, "user", historyText));
        descending.add(message(21L, 10L, "assistant", historyText));
        when(messageRepository.countBySessionId(10L)).thenReturn(5L);
        when(messageRepository.save(any(AiChatMessageEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(messageRepository.findTop30BySessionIdOrderByIdDesc(10L)).thenReturn(descending);
        when(sessionRepository.save(any(AiChatSessionEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        TokenEstimator estimator = new TokenEstimator(aiProperties);
        String systemPrompt =
                new KnowledgeAssistantPromptService().buildSystemPrompt("计算机科学与技术");
        int fixedTokens = estimator.estimateText(systemPrompt);
        int roomForLatestTwo = estimator.estimateMessage("assistant", historyText)
                + estimator.estimateMessage("user", currentQuestion);
        // 输入预算只够“系统提示词 + 最新两条消息”，最旧的四条必须被裁掉
        aiProperties.setMaxInputTokens(fixedTokens + roomForLatestTwo);

        var context = service.prepareUserMessage(1L, 10L, currentQuestion);

        assertThat(context.messages()).hasSize(2);
        assertThat(context.messages().get(0).role()).isEqualTo("assistant");
        assertThat(context.messages().get(0).content()).isEqualTo(historyText);
        assertThat(context.messages().get(1).role()).isEqualTo("user");
        assertThat(context.messages().get(1).content()).isEqualTo(currentQuestion);
        assertThat(context.effectiveMaxOutputTokens()).isEqualTo(2000);
        assertThat(context.estimatedInputTokens()).isPositive();
    }

    @Test
    void contextTooLongWhenEvenSingleQuestionExceedsTokenWindow() {
        stubUserAndSession(1L, 10L, "新对话");
        String longQuestion = "请详细解释操作系统的进程调度机制，".repeat(60);
        when(messageRepository.countBySessionId(10L)).thenReturn(0L);
        when(messageRepository.save(any(AiChatMessageEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(messageRepository.findTop30BySessionIdOrderByIdDesc(any()))
                .thenReturn(List.of(message(31L, 10L, "user", longQuestion)));
        when(sessionRepository.save(any(AiChatSessionEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        TokenEstimator estimator = new TokenEstimator(aiProperties);
        String systemPrompt =
                new KnowledgeAssistantPromptService().buildSystemPrompt("计算机科学与技术");
        int fixedTokens = estimator.estimateText(systemPrompt);
        // 窗口只比系统提示词大 300 token，千余 token 的本轮提问即使独自保留也放不下
        aiProperties.setContextWindowTokens(fixedTokens + 300);
        aiProperties.setOutputReserveTokens(50);
        aiProperties.setMinOutputTokens(50);

        assertThatThrownBy(() -> service.prepareUserMessage(1L, 10L, longQuestion))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus().value()).isEqualTo(400);
                    assertThat(ex.getCode()).isEqualTo("ASSISTANT_CONTEXT_TOO_LONG");
                });
    }

    @Test
    void ordinaryChatNeverWritesMemory() {
        stubUserAndSession(1L, 10L, "新对话");
        when(messageRepository.countBySessionId(10L)).thenReturn(0L);
        when(messageRepository.save(any(AiChatMessageEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(messageRepository.findTop30BySessionIdOrderByIdDesc(any()))
                .thenAnswer(invocation -> List.of(message(1L, 10L, "user", "我记住了上次的错误")));
        when(sessionRepository.save(any(AiChatSessionEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.prepareUserMessage(1L, 10L, "我记住了上次的错误");

        verify(memoryVectorService, never()).decideExplicitMemory(any(), any(), any());
        verify(agentMemoryService, never())
                .createExplicitActiveMemory(any(), any(), any(), any());
        verify(agentMemoryService, never()).syncPointAfterCommit(any(), any());
    }

    @Test
    void explicitRememberCreatesActiveMemoryAndSyncsPointBeforeStream() {
        stubUserAndSession(1L, 10L, "新对话");
        String rememberBody = "我喜欢用代码例子学习";
        when(messageRepository.countBySessionId(10L)).thenReturn(0L);
        when(messageRepository.save(any(AiChatMessageEntity.class)))
                .thenAnswer(invocation -> {
                    AiChatMessageEntity saved = invocation.getArgument(0);
                    setId(saved, 77L);
                    return saved;
                });
        when(messageRepository.findTop30BySessionIdOrderByIdDesc(any()))
                .thenAnswer(invocation -> List.of(message(77L, 10L, "user", "记住，" + rememberBody)));
        when(sessionRepository.save(any(AiChatSessionEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(memoryVectorService.decideExplicitMemory(eq(1L), eq("preference"), eq(rememberBody)))
                .thenReturn(new MemoryDecision.Create("preference", rememberBody));
        AgentMemoryEntity savedMemory = memory(50L, 1L, "active", "preference", rememberBody);
        when(agentMemoryService.createExplicitActiveMemory(eq(1L), any(), eq(10L), eq(77L)))
                .thenReturn(savedMemory);

        var context = service.prepareUserMessage(1L, 10L, "记住，我喜欢用代码例子学习");

        verify(memoryVectorService).decideExplicitMemory(1L, "preference", rememberBody);
        verify(agentMemoryService).createExplicitActiveMemory(eq(1L), any(), eq(10L), eq(77L));
        verify(agentMemoryService).syncPointAfterCommit(1L, savedMemory);
        assertThat(context).isNotNull();
    }

    @Test
    void explicitRememberMergeUpdatesSameMemory() {
        stubUserAndSession(1L, 10L, "新对话");
        String merged = "用户喜欢用代码例子学习，不喜欢纯理论讲解";
        when(messageRepository.countBySessionId(10L)).thenReturn(0L);
        when(messageRepository.save(any(AiChatMessageEntity.class)))
                .thenAnswer(invocation -> {
                    AiChatMessageEntity saved = invocation.getArgument(0);
                    setId(saved, 88L);
                    return saved;
                });
        when(messageRepository.findTop30BySessionIdOrderByIdDesc(any()))
                .thenAnswer(invocation -> List.of(message(88L, 10L, "user", "记住，我更喜欢代码例子")));
        when(sessionRepository.save(any(AiChatSessionEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(memoryVectorService.decideExplicitMemory(any(), any(), any()))
                .thenReturn(new MemoryDecision.Merge(50L, merged));
        AgentMemoryEntity updated = memory(50L, 1L, "active", "preference", merged);
        when(agentMemoryService.mergeExplicitMemory(eq(1L), any())).thenReturn(updated);

        service.prepareUserMessage(1L, 10L, "记住，我更喜欢代码例子，不喜欢纯理论");

        verify(agentMemoryService).mergeExplicitMemory(eq(1L), any());
        verify(agentMemoryService, never())
                .createExplicitActiveMemory(any(), any(), any(), any());
        verify(agentMemoryService).syncPointAfterCommit(1L, updated);
    }

    @Test
    void cannotOpenAnotherUsersSession() {
        UserEntity user = user(1L, "alice", "计算机科学与技术");
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(sessionRepository.findByIdAndUserId(99L, 1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getSession(1L, 99L))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus().value()).isEqualTo(404);
                    assertThat(ex.getCode()).isEqualTo("NOT_FOUND");
                });
    }

    @Test
    void completeAssistantMessagePersistsFullAnswerAndPublishesSummaryEvent() {
        UserEntity user = user(1L, "alice", "计算机科学与技术");
        AiChatSessionEntity session = session(10L, 1L, "你好");
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(sessionRepository.findByIdAndUserId(10L, 1L)).thenReturn(Optional.of(session));
        when(messageRepository.save(any(AiChatMessageEntity.class))).thenAnswer(invocation -> {
            AiChatMessageEntity entity = invocation.getArgument(0);
            setId(entity, 30L);
            return entity;
        });
        when(sessionRepository.save(any(AiChatSessionEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var completed = service.completeAssistantMessage(
                1L, 10L, "## 进程与线程\n进程是资源分配单位。", "deepseek", "deepseek-chat");

        assertThat(completed.message().id()).isEqualTo(idCodec.encode(30L));
        assertThat(completed.message().role()).isEqualTo("assistant");
        assertThat(completed.message().provider()).isEqualTo("deepseek");
        assertThat(completed.session().id()).isEqualTo(idCodec.encode(10L));
        ArgumentCaptor<SessionSummaryRequested> captor =
                ArgumentCaptor.forClass(SessionSummaryRequested.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().sessionId()).isEqualTo(10L);
    }

    @Test
    void emptyQuestionIsRejected() {
        assertThatThrownBy(() -> service.prepareUserMessage(1L, 10L, "   "))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void deleteSessionDeletesOwnedSession() {
        UserEntity user = user(1L, "alice", "计算机科学与技术");
        AiChatSessionEntity session = session(10L, 1L, "Dijkstra");
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(sessionRepository.findByIdAndUserId(10L, 1L)).thenReturn(Optional.of(session));

        service.deleteSession(1L, 10L);

        verify(sessionRepository).delete(session);
    }

    @Test
    void prepareUserMessagePassesNormalizedQuestionToMemoryInjection() {
        stubUserAndSession(1L, 10L, "新对话");
        when(messageRepository.countBySessionId(10L)).thenReturn(0L);
        when(messageRepository.save(any(AiChatMessageEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(messageRepository.findTop30BySessionIdOrderByIdDesc(10L)).thenReturn(List.of());
        when(sessionRepository.save(any(AiChatSessionEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.prepareUserMessage(1L, 10L, "  解释进程和线程  ");

        // 方案 §P1-6：注入用的是本轮归一化后的用户问题
        verify(agentMemoryService).buildInjection(1L, "解释进程和线程");
        verify(agentMemoryService, never()).buildInjection(any());
    }

    /**
     * P1-6 的证据用例：记忆相关性检索（embedding + Qdrant）必须发生在事务外；
     * 事务内的 persistUserTurn 不得再做任何检索。
     *
     * <p>单测里没有 Spring 事务代理，所以用 {@link TransactionSynchronizationManager}
     * 手工模拟"代理已开启事务"的边界：
     * 先证明检索在无事务时发生（捕获到的 flag 为 false），
     * 再在"模拟事务"内直接调用 persistUserTurn，证明它不再触发检索（计数仍为 1）。
     * 若有人把 buildInjection 挪回事务内，第二次调用会让计数变 2 且 flag 变 true，用例失败。
     */
    @Test
    void memoryInjectionSearchRunsOutsideTransactionAndPersistTurnReusesIt() {
        AgentMemoryRepository memoryRepository = mock(AgentMemoryRepository.class);
        when(memoryRepository.findByUserIdAndStatusOrderByImportanceDescUpdatedAtDesc(1L, "active"))
                .thenReturn(List.of(memory(50L, 1L, "active", "fact", "进程调度是重点")));
        AtomicBoolean transactionActiveDuringSearch = new AtomicBoolean(true);
        when(memoryVectorService.searchMemories(any(), any(), anyDouble(), anyInt()))
                .thenAnswer(invocation -> {
                    transactionActiveDuringSearch.set(
                            TransactionSynchronizationManager.isActualTransactionActive());
                    return List.of(new VectorSearchService.VectorHit(
                            "memory", "50", 0, "title", "content", List.of(), 0.92d, null));
                });
        AgentMemoryService realMemoryService =
                new AgentMemoryService(memoryRepository, memoryVectorService);
        KnowledgeAssistantService serviceWithRealMemory = newService(realMemoryService);

        stubUserAndSession(1L, 10L, "新对话");
        when(messageRepository.countBySessionId(10L)).thenReturn(0L);
        when(messageRepository.save(any(AiChatMessageEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(messageRepository.findTop30BySessionIdOrderByIdDesc(10L)).thenReturn(List.of());
        when(sessionRepository.save(any(AiChatSessionEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        String question = "线程和进程的区别";
        var context = serviceWithRealMemory.prepareUserMessage(1L, 10L, question);

        // 注入块里确实含相关记忆
        assertThat(context.systemPrompt()).contains("进程调度是重点");
        assertThat(transactionActiveDuringSearch)
                .as("记忆相关性检索必须发生在事务外").isFalse();
        verify(memoryVectorService, times(1))
                .searchMemories(any(), any(), anyDouble(), anyInt());

        // 模拟 @Transactional 代理：手工开一个"实际事务"后再调用 persistUserTurn
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
        try {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                    .as("模拟事务已生效（控制断言，防止上面的 false 是空断言）").isTrue();
            serviceWithRealMemory.persistUserTurn(1L, 10L, question, new MemoryDecision.None(),
                    new AgentMemoryService.MemoryInjection("", List.of()));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }

        assertThat(transactionActiveDuringSearch)
                .as("事务内的 persistUserTurn 不得触发记忆检索").isFalse();
        verify(memoryVectorService, times(1))
                .searchMemories(any(), any(), anyDouble(), anyInt());
    }

    private void stubUserAndSession(Long userId, Long sessionId, String title) {
        when(userRepository.findById(userId)).thenReturn(Optional.of(user(userId, "alice", "计算机科学与技术")));
        when(sessionRepository.findByIdAndUserId(sessionId, userId))
                .thenReturn(Optional.of(session(sessionId, userId, title)));
    }

    private static UserEntity user(Long id, String username, String major) {
        UserEntity user = new UserEntity();
        user.setUsername(username);
        user.setMajor(major);
        setId(user, id);
        return user;
    }

    private static AiChatSessionEntity session(Long id, Long userId, String title) {
        AiChatSessionEntity session = new AiChatSessionEntity();
        session.setUserId(userId);
        session.setTitle(title);
        setId(session, id);
        return session;
    }

    private static AiChatMessageEntity message(Long id, Long sessionId, String role, String content) {
        AiChatMessageEntity message = new AiChatMessageEntity();
        message.setSessionId(sessionId);
        message.setRole(role);
        message.setContent(content);
        setId(message, id);
        return message;
    }

    private static AgentMemoryEntity memory(Long id, Long userId, String status, String type, String content) {
        AgentMemoryEntity memory = new AgentMemoryEntity();
        setId(memory, id);
        memory.setUserId(userId);
        memory.setStatus(status);
        memory.setMemoryType(type);
        memory.setContent(content);
        memory.setImportance((short) 3);
        memory.setConfidence((short) 100);
        return memory;
    }

    private static void setId(Object entity, Long id) {
        setField(entity, "id", id);
    }

    private static void setField(Object entity, String fieldName, Object value) {
        try {
            var field = entity.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(entity, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
