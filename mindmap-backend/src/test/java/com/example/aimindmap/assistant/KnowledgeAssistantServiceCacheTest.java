package com.example.aimindmap.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.aimindmap.agentcontext.ContextBudgetResult;
import com.example.aimindmap.agentcontext.TokenBudgetService;
import com.example.aimindmap.agentmemory.AgentMemoryService;
import com.example.aimindmap.agentmemory.ExplicitMemoryRecognizer;
import com.example.aimindmap.agentmemory.MemoryVectorService;
import com.example.aimindmap.ai.AiChatMessage;
import com.example.aimindmap.assistant.ChatRecentMessageCache.CachedMessage;
import com.example.aimindmap.auth.ExternalIdCodec;
import com.example.aimindmap.auth.UserEntity;
import com.example.aimindmap.auth.UserRepository;
import com.example.aimindmap.mqsupport.outbox.OutboxEventService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.context.ApplicationEventPublisher;

/**
 * 阶段七-2：KnowledgeAssistantService Cache-Aside 纯 Mockito 单测（严禁真实 Redis）。
 * 锁死四件事：
 * <ol>
 *   <li>未命中 → 查库（DB 原始 DESC → reverse 正序）构造 candidates，并以同一份正序快照回填；</li>
 *   <li>命中 → 不查库、不回填，candidates 与未命中路径逐字节一致（两用例期望值完全相同）；</li>
 *   <li>失效时序 → 用户消息 save 之后、缓存读取之前必须 evictSession；助手消息 save 后立即 evict；</li>
 *   <li>deleteSession 删除后立即 evict。</li>
 * </ol>
 * TokenBudgetService 用 mock 捕获 candidates（只关心进入预算计算的消息序列与内容）。
 */
class KnowledgeAssistantServiceCacheTest {

    private static final long USER_ID = 1L;
    private static final long SESSION_ID = 10L;

    private UserRepository userRepository;
    private AiChatSessionRepository sessionRepository;
    private AiChatMessageRepository messageRepository;
    private ApplicationEventPublisher eventPublisher;
    private MemoryVectorService memoryVectorService;
    private AgentMemoryService agentMemoryService;
    private TokenBudgetService tokenBudgetService;
    private ChatRecentMessageCache recentMessageCache;
    private final ExternalIdCodec idCodec = new ExternalIdCodec("unit-test-external-id-salt");
    private KnowledgeAssistantService service;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        sessionRepository = mock(AiChatSessionRepository.class);
        messageRepository = mock(AiChatMessageRepository.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        memoryVectorService = mock(MemoryVectorService.class);
        agentMemoryService = mock(AgentMemoryService.class);
        tokenBudgetService = mock(TokenBudgetService.class);
        recentMessageCache = mock(ChatRecentMessageCache.class);
        // P1-6 适配：调用点改为二参（userId, 本轮 content）；mock 打桩同步为二参，断言语义零改
        when(agentMemoryService.buildInjection(any(), any()))
                .thenReturn(new AgentMemoryService.MemoryInjection("", List.of()));
        when(tokenBudgetService.compute(anyLong(), anyString(), anyList()))
                .thenAnswer(invocation ->
                        new ContextBudgetResult(invocation.getArgument(2), 1, 2000, 0));
        service = new KnowledgeAssistantService(
                userRepository, sessionRepository, messageRepository, recentMessageCache,
                new KnowledgeAssistantPromptService(), tokenBudgetService, idCodec,
                eventPublisher, new ExplicitMemoryRecognizer(), memoryVectorService,
                agentMemoryService, null, mock(OutboxEventService.class), false);
        setField(service, "self", service);
    }

    @Test
    @SuppressWarnings("unchecked")
    void cacheMissQueriesDbBuildsAscendingCandidatesAndBackfillsSnapshot() {
        stubUserAndSession("新对话", null, 2L);
        // DB 原始返回是 DESC（新→旧），与生产查询方法语义一致
        when(messageRepository.findTop30BySessionIdOrderByIdDesc(SESSION_ID))
                .thenReturn(List.of(
                        message(23L, "user", "第三个问题"),
                        message(22L, "assistant", "第二个回答"),
                        message(21L, "user", "第一个问题")));
        when(recentMessageCache.get(SESSION_ID, 30)).thenReturn(null);

        service.prepareUserMessage(USER_ID, SESSION_ID, "第三个问题");

        ArgumentCaptor<List<AiChatMessage>> candidates = ArgumentCaptor.forClass(List.class);
        verify(tokenBudgetService).compute(eq(SESSION_ID), anyString(), candidates.capture());
        assertThat(candidates.getValue()).containsExactly(
                new AiChatMessage("user", "第一个问题"),
                new AiChatMessage("assistant", "第二个回答"),
                new AiChatMessage("user", "第三个问题"));

        // 回填的是同一份时间正序快照（与 candidates 同构）
        ArgumentCaptor<List<CachedMessage>> snapshots = ArgumentCaptor.forClass(List.class);
        verify(recentMessageCache).put(eq(SESSION_ID), eq(30), snapshots.capture());
        assertThat(snapshots.getValue()).containsExactly(
                new CachedMessage("user", "第一个问题"),
                new CachedMessage("assistant", "第二个回答"),
                new CachedMessage("user", "第三个问题"));

        // 关键时序：用户消息 save → evictSession → 之后才允许读缓存/回源（失效必须先于读）
        InOrder order = inOrder(messageRepository, recentMessageCache);
        order.verify(messageRepository).save(any(AiChatMessageEntity.class));
        order.verify(recentMessageCache).evictSession(SESSION_ID);
        order.verify(recentMessageCache).get(SESSION_ID, 30);
    }

    @Test
    @SuppressWarnings("unchecked")
    void cacheHitSkipsDbAndProducesIdenticalCandidates() {
        stubUserAndSession("新对话", null, 2L);
        when(recentMessageCache.get(SESSION_ID, 30)).thenReturn(List.of(
                new CachedMessage("user", "第一个问题"),
                new CachedMessage("assistant", "第二个回答"),
                new CachedMessage("user", "第三个问题")));

        service.prepareUserMessage(USER_ID, SESSION_ID, "第三个问题");

        verify(messageRepository, never()).findTop30BySessionIdOrderByIdDesc(any());
        verify(messageRepository, never()).findTop10BySessionIdOrderByIdDesc(any());
        verify(recentMessageCache, never()).put(anyLong(), anyInt(), anyList());

        ArgumentCaptor<List<AiChatMessage>> candidates = ArgumentCaptor.forClass(List.class);
        verify(tokenBudgetService).compute(eq(SESSION_ID), anyString(), candidates.capture());
        // 期望值与 cacheMissQueriesDbBuildsAscendingCandidatesAndBackfillsSnapshot 完全相同：
        // 两条路径的 candidates 顺序与内容逐字节一致
        assertThat(candidates.getValue()).containsExactly(
                new AiChatMessage("user", "第一个问题"),
                new AiChatMessage("assistant", "第二个回答"),
                new AiChatMessage("user", "第三个问题"));
    }

    @Test
    void summarizedSessionUsesLimitTenForCacheAndQuery() {
        // 消息数 25+1 > 20 且已有摘要 → 走 top10 口径，缓存 limit 也必须是 10
        stubUserAndSession("长对话", "这是既有的会话摘要。", 25L);
        when(recentMessageCache.get(SESSION_ID, 10)).thenReturn(null);
        when(messageRepository.findTop10BySessionIdOrderByIdDesc(SESSION_ID))
                .thenReturn(List.of(message(99L, "user", "最新问题")));

        service.prepareUserMessage(USER_ID, SESSION_ID, "最新问题");

        verify(recentMessageCache).get(SESSION_ID, 10);
        verify(messageRepository).findTop10BySessionIdOrderByIdDesc(SESSION_ID);
        verify(messageRepository, never()).findTop30BySessionIdOrderByIdDesc(any());
        verify(recentMessageCache).put(eq(SESSION_ID), eq(10), anyList());
    }

    @Test
    void completeAssistantMessageEvictsCacheAfterSave() {
        stubUserAndSession("新对话", null, 1L);

        service.completeAssistantMessage(
                USER_ID, SESSION_ID, "这是助手的完整回答。", "deepseek", "deepseek-chat");

        InOrder order = inOrder(messageRepository, recentMessageCache);
        order.verify(messageRepository).save(any(AiChatMessageEntity.class));
        order.verify(recentMessageCache).evictSession(SESSION_ID);
    }

    @Test
    void deleteSessionEvictsCacheAfterDelete() {
        stubUserAndSession("新对话", null, 0L);

        service.deleteSession(USER_ID, SESSION_ID);

        InOrder order = inOrder(sessionRepository, recentMessageCache);
        order.verify(sessionRepository).delete(any(AiChatSessionEntity.class));
        order.verify(recentMessageCache).evictSession(SESSION_ID);
    }

    private void stubUserAndSession(String title, String summary, long messageCount) {
        UserEntity user = new UserEntity();
        user.setUsername("alice");
        user.setMajor("计算机科学与技术");
        setField(user, "id", USER_ID);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

        AiChatSessionEntity session = new AiChatSessionEntity();
        session.setUserId(USER_ID);
        session.setTitle(title);
        session.setSummary(summary);
        setField(session, "id", SESSION_ID);
        when(sessionRepository.findByIdAndUserId(SESSION_ID, USER_ID))
                .thenReturn(Optional.of(session));
        when(sessionRepository.save(any(AiChatSessionEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        when(messageRepository.countBySessionId(SESSION_ID)).thenReturn(messageCount);
        when(messageRepository.save(any(AiChatMessageEntity.class))).thenAnswer(invocation -> {
            AiChatMessageEntity entity = invocation.getArgument(0);
            if (readField(entity, "id") == null) {
                setField(entity, "id", 100L);
            }
            return entity;
        });
    }

    private static AiChatMessageEntity message(Long id, String role, String content) {
        AiChatMessageEntity message = new AiChatMessageEntity();
        message.setSessionId(SESSION_ID);
        message.setRole(role);
        message.setContent(content);
        setField(message, "id", id);
        return message;
    }

    private static Object readField(Object target, String fieldName) {
        try {
            var field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
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
