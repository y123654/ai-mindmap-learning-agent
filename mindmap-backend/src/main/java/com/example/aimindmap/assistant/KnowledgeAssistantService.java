package com.example.aimindmap.assistant;

import com.example.aimindmap.agentmemory.AgentMemoryEntity;
import com.example.aimindmap.agentmemory.AgentMemoryService;
import com.example.aimindmap.agentmemory.ExplicitMemoryRecognizer;
import com.example.aimindmap.agentmemory.MemoryDecision;
import com.example.aimindmap.agentmemory.MemoryVectorService;
import com.example.aimindmap.agentcontext.ContextBudgetResult;
import com.example.aimindmap.agentcontext.TokenBudgetService;
import com.example.aimindmap.ai.AiChatMessage;
import com.example.aimindmap.auth.ExternalIdCodec;
import com.example.aimindmap.auth.UserEntity;
import com.example.aimindmap.auth.UserRepository;
import com.example.aimindmap.common.error.ApiException;
import com.example.aimindmap.mqsupport.TaskMessage;
import com.example.aimindmap.mqsupport.TaskType;
import com.example.aimindmap.mqsupport.outbox.OutboxEventService;
import jakarta.transaction.Transactional;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.time.Instant;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Lazy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class KnowledgeAssistantService {

    private static final int MAX_CONTENT_LENGTH = 4000;
    private static final int MAX_CONTEXT_MESSAGES = 30;
    private static final int RECENT_MESSAGES_WITH_SUMMARY = 10;
    private static final int SUMMARY_TRIGGER_MESSAGE_COUNT = 20;
    private static final int TITLE_MAX_LENGTH = 40;

    private final UserRepository userRepository;
    private final AiChatSessionRepository sessionRepository;
    private final AiChatMessageRepository messageRepository;
    private final ChatRecentMessageCache recentMessageCache;
    private final KnowledgeAssistantPromptService promptService;
    private final TokenBudgetService tokenBudgetService;
    private final ExternalIdCodec idCodec;
    private final ApplicationEventPublisher eventPublisher;
    private final ExplicitMemoryRecognizer explicitMemoryRecognizer;
    private final MemoryVectorService memoryVectorService;
    private final AgentMemoryService agentMemoryService;
    private final KnowledgeAssistantService self;
    private final OutboxEventService outboxEventService;
    private final boolean mqEnabled;

    public KnowledgeAssistantService(UserRepository userRepository,
                                     AiChatSessionRepository sessionRepository,
                                     AiChatMessageRepository messageRepository,
                                     ChatRecentMessageCache recentMessageCache,
                                     KnowledgeAssistantPromptService promptService,
                                     TokenBudgetService tokenBudgetService,
                                     ExternalIdCodec idCodec,
                                     ApplicationEventPublisher eventPublisher,
                                     ExplicitMemoryRecognizer explicitMemoryRecognizer,
                                     MemoryVectorService memoryVectorService,
                                     AgentMemoryService agentMemoryService,
                                     @Lazy KnowledgeAssistantService self,
                                     OutboxEventService outboxEventService,
                                     @Value("${app.mq.enabled:true}") boolean mqEnabled) {
        this.userRepository = userRepository;
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
        this.recentMessageCache = recentMessageCache;
        this.promptService = promptService;
        this.tokenBudgetService = tokenBudgetService;
        this.idCodec = idCodec;
        this.eventPublisher = eventPublisher;
        this.explicitMemoryRecognizer = explicitMemoryRecognizer;
        this.memoryVectorService = memoryVectorService;
        this.agentMemoryService = agentMemoryService;
        this.self = self;
        this.outboxEventService = outboxEventService;
        this.mqEnabled = mqEnabled;
    }

    @Transactional
    public List<KnowledgeAssistantDtos.SessionSummaryResponse> listSessions(Long userId) {
        requireUser(userId);
        return sessionRepository.findByUserIdOrderByUpdatedAtDescIdDesc(userId).stream()
                .map(entity -> KnowledgeAssistantDtos.SessionSummaryResponse.from(entity, idCodec))
                .toList();
    }

    @Transactional
    public KnowledgeAssistantDtos.SessionSummaryResponse createSession(Long userId,
                                                                        KnowledgeAssistantDtos.CreateSessionRequest request) {
        requireUser(userId);
        AiChatSessionEntity session = new AiChatSessionEntity();
        session.setUserId(userId);
        session.setTitle(normalizeTitle(request == null ? null : request.title()));
        return KnowledgeAssistantDtos.SessionSummaryResponse.from(sessionRepository.save(session), idCodec);
    }

    @Transactional
    public KnowledgeAssistantDtos.SessionDetailResponse getSession(Long userId, Long sessionId) {
        AiChatSessionEntity session = getOwnedSession(sessionId, userId);
        List<KnowledgeAssistantDtos.MessageResponse> messages =
                messageRepository.findBySessionIdOrderByIdAsc(sessionId).stream()
                        .map(entity -> KnowledgeAssistantDtos.MessageResponse.from(entity, idCodec))
                        .toList();
        return new KnowledgeAssistantDtos.SessionDetailResponse(
                idCodec.encode(session.getId()), session.getTitle(), session.getCreatedAt(),
                session.getUpdatedAt(), messages);
    }

    /** 修订第 23 条：删除会话；消息通过外键 ON DELETE CASCADE 一并物理删除。 */
    @Transactional
    public void deleteSession(Long userId, Long sessionId) {
        AiChatSessionEntity session = getOwnedSession(sessionId, userId);
        sessionRepository.delete(session);
        // 阶段七-2：会话删除（消息 FK 级联物理删除）后主动失效最近消息缓存；
        // evict 异常已在缓存实现内吞掉，不影响删除主流程
        recentMessageCache.evictSession(sessionId);
    }

    /**
     * 阶段 4-B：事务外编排（任务五）。
     * 显式记忆的识别、去重检索、LLM 合并都在无事务状态完成（外部调用不占数据库长事务）；
     * 记忆注入块（P1-6，含一次 embedding + 向量检索）也在本方法事务外算好，
     * PG 落库与上下文组装在 persistUserTurn 的事务内完成。
     */
    public ChatStreamContext prepareUserMessage(Long userId, Long sessionId, String rawContent) {
        String content = normalizeContent(rawContent);

        MemoryDecision decision = new MemoryDecision.None();
        ExplicitMemoryRecognizer.ExplicitMemory explicit = explicitMemoryRecognizer.recognize(content);
        if (explicit != null) {
            decision = memoryVectorService.decideExplicitMemory(
                    userId, explicit.memoryType(), explicit.content());
        }
        // 方案 §P1-6：注入块在事务外计算（这里才允许发生 embedding + Qdrant 检索）
        AgentMemoryService.MemoryInjection injection =
                agentMemoryService.buildInjection(userId, content);
        return self.persistUserTurn(userId, sessionId, content, decision, injection);
    }

    /**
     * 用户消息落库 + 上下文组装（事务内）。
     *
     * <p>P1-6：{@code injection} 由 {@link #prepareUserMessage} 在事务外算好传入，
     * 本方法内<b>不再</b>做任何 embedding / 向量检索；只按既有语义更新被注入记忆的
     * {@code last_used_at}（写库留在事务内是正常的）。
     *
     * <p>已知副作用：本轮刚新建/合并的显式记忆不在本轮注入块里（注入在落库前已算完），
     * 下一轮生效。
     */
    @Transactional
    public ChatStreamContext persistUserTurn(Long userId,
                                             Long sessionId,
                                             String content,
                                             MemoryDecision decision,
                                             AgentMemoryService.MemoryInjection injection) {
        UserEntity user = requireUser(userId);
        AiChatSessionEntity session = getOwnedSession(sessionId, userId);

        long beforeCount = messageRepository.countBySessionId(sessionId);
        AiChatMessageEntity userMessage = new AiChatMessageEntity();
        userMessage.setSessionId(sessionId);
        userMessage.setRole("user");
        userMessage.setContent(content);
        messageRepository.save(userMessage);
        // 阶段七-2 Cache-Aside 主动失效：必须发生在 save 之后、下方 buildGatewayBudget 读之前，
        // 保证本事务内的读不会命中不含新消息的旧缓存（取舍见 buildGatewayBudget javadoc）
        recentMessageCache.evictSession(sessionId);

        if (beforeCount == 0 || "新对话".equals(session.getTitle())) {
            session.setTitle(makeTitle(content));
        }
        session.touch();
        sessionRepository.save(session);

        AgentMemoryEntity changedMemory = applyMemoryDecision(
                userId, decision, sessionId, userMessage.getId());

        agentMemoryService.touchLastUsed(injection.memories());

        long totalCount = beforeCount + 1;
        boolean useSummary = totalCount > SUMMARY_TRIGGER_MESSAGE_COUNT
                && session.getSummary() != null && !session.getSummary().isBlank();

        String systemPrompt = promptService.buildSystemPrompt(user.getMajor());
        if (!injection.block().isBlank()) {
            systemPrompt = systemPrompt + "\n\n" + injection.block();
        }
        if (useSummary) {
            systemPrompt = appendSummaryBlock(systemPrompt, session.getSummary());
        }

        ContextBudgetResult budget = buildGatewayBudget(sessionId, systemPrompt, useSummary);

        if (changedMemory != null) {
            agentMemoryService.syncPointAfterCommit(userId, changedMemory);
        }

        return new ChatStreamContext(
                userId,
                sessionId,
                systemPrompt,
                budget.retained(),
                budget.effectiveMaxOutputTokens(),
                budget.estimatedInputTokens(),
                0.3d
        );
    }

    @Transactional
    public KnowledgeAssistantDtos.StreamCompleted completeAssistantMessage(Long userId,
                                                                            Long sessionId,
                                                                            String answer,
                                                                            String provider,
                                                                            String model) {
        getOwnedSession(sessionId, userId);
        String content = normalizeContent(answer);

        AiChatMessageEntity message = new AiChatMessageEntity();
        message.setSessionId(sessionId);
        message.setRole("assistant");
        message.setContent(content);
        message.setAiProvider(provider);
        message.setAiModel(model);
        AiChatMessageEntity saved = messageRepository.save(message);
        // 阶段七-2：助手消息落库后立即失效最近消息缓存
        recentMessageCache.evictSession(sessionId);

        AiChatSessionEntity session = getOwnedSession(sessionId, userId);
        session.touch();
        AiChatSessionEntity savedSession = sessionRepository.save(session);

        if (mqEnabled) {
            // 阶段六-3：同事务写 outbox，taskId 锚定本次助手消息 ID（每次触发天然唯一），
            // entityId 放 sessionId；relay 投递后由 SessionSummaryMessageConsumer 消费。
            outboxEventService.recordPending(new TaskMessage(
                    TaskType.SESSION_SUMMARY, saved.getId(), String.valueOf(sessionId),
                    "SUMMARY", Instant.now()));
        } else {
            // 降级路径（§3.5）：AFTER_COMMIT 由 SessionSummaryWorker @Async 执行。
            eventPublisher.publishEvent(new SessionSummaryRequested(userId, sessionId));
        }

        return new KnowledgeAssistantDtos.StreamCompleted(
                KnowledgeAssistantDtos.MessageResponse.from(saved, idCodec),
                KnowledgeAssistantDtos.SessionSummaryResponse.from(savedSession, idCodec),
                null
        );
    }

    private AgentMemoryEntity applyMemoryDecision(Long userId, MemoryDecision decision,
                                                  Long sourceSessionId, Long sourceMessageId) {
        if (decision instanceof MemoryDecision.Create create) {
            return agentMemoryService.createExplicitActiveMemory(
                    userId, create, sourceSessionId, sourceMessageId);
        }
        if (decision instanceof MemoryDecision.Merge merge) {
            return agentMemoryService.mergeExplicitMemory(userId, merge);
        }
        return null;
    }

    /**
     * 阶段二 C/D：候选消息口径不变（有摘要 top10 / 无摘要 top30，时间正序），
     * 裁剪与输出预算改由 TokenBudgetService 按真实 token 估算处理。
     *
     * <p>阶段七-2 Cache-Aside：先读 {@link ChatRecentMessageCache} 快照（时间正序），命中直接
     * 构造 candidates；未命中查库（原始 DESC）→ reverse 成时间正序 → 构造快照并回填。两条路径
     * 在「正序快照 → 逐条 map 成 AiChatMessage」处汇合，顺序与内容逐字节一致
     * （KnowledgeAssistantServiceCacheTest 锁死）。缓存值为 role/content 快照 record，
     * 不序列化 JPA 实体；PostgreSQL 仍是唯一事实源，缓存丢失可重建。
     *
     * <p><b>事务与竞态的已知取舍（照方案接受）：</b>evict 在消息 save 后立即于事务内执行，
     * 保证 persistUserTurn 下方的读点不会命中旧缓存——正确性优先；代价是 put 可能把未提交
     * 数据写入缓存，若事务回滚会产生最长一个 TTL（默认 60s）的幻影缓存。PG 是唯一事实源、
     * 下一轮写消息/删会话会再次主动失效，风险有界，不引入 afterCommit 同步复杂化。
     *
     * <p><b>命中率事实（诚实披露）：</b>当前全仓唯一读点就是本方法，且紧跟同事务内
     * persistUserTurn 的写+失效，正常顺序流量下 get 几乎总是 miss（写→evict→读 miss→回填）。
     * 本缓存的现实价值=方案规定的高并发基础设施+阶段八命中率指标落点+并发重复读保护
     * +Cache-Aside 模式实现样例，不是现有顺序链路的提速。
     */
    private ContextBudgetResult buildGatewayBudget(Long sessionId, String systemPrompt, boolean summarized) {
        int limit = summarized ? RECENT_MESSAGES_WITH_SUMMARY : MAX_CONTEXT_MESSAGES;
        List<ChatRecentMessageCache.CachedMessage> snapshots = recentMessageCache.get(sessionId, limit);
        if (snapshots == null) {
            List<AiChatMessageEntity> recentEntities = summarized
                    ? messageRepository.findTop10BySessionIdOrderByIdDesc(sessionId)
                    : messageRepository.findTop30BySessionIdOrderByIdDesc(sessionId);
            List<AiChatMessageEntity> recent = new ArrayList<>(recentEntities);
            Collections.reverse(recent);
            snapshots = recent.stream()
                    .map(entity -> new ChatRecentMessageCache.CachedMessage(
                            entity.getRole(), entity.getContent()))
                    .toList();
            recentMessageCache.put(sessionId, limit, snapshots);
        }

        List<AiChatMessage> candidates = snapshots.stream()
                .map(snapshot -> new AiChatMessage(snapshot.role(), snapshot.content()))
                .toList();

        return tokenBudgetService.compute(sessionId, systemPrompt, candidates);
    }

    /**
     * 方案 §5.1 / §5.4：长会话时把摘要以明确分隔段拼入系统提示词。
     * 选择拼入 systemPrompt 而非新增 system 消息：ChatClient 只放置一次 system，
     * 可保持现有单系统消息结构与 KnowledgeAgentService 的 user/assistant 映射不变，改动最小。
     * 顺序为"长期记忆块 → 会话摘要块"，对应 §5.1 组成顺序。
     */
    private String appendSummaryBlock(String systemPrompt, String summary) {
        return systemPrompt
                + "\n\n----- 会话摘要（本会话较早对话的概括，请结合它理解后续问题） -----\n"
                + summary.strip()
                + "\n----- 会话摘要结束 -----";
    }

    private UserEntity requireUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> ApiException.unauthorized("AUTH_USER_NOT_FOUND", "账号不存在或登录已失效"));
    }

    private AiChatSessionEntity getOwnedSession(Long sessionId, Long userId) {
        return sessionRepository.findByIdAndUserId(sessionId, userId)
                .orElseThrow(() -> ApiException.notFound("AI 对话不存在或无权访问：" + sessionId));
    }

    private String normalizeContent(String content) {
        if (content == null || content.trim().isBlank()) {
            throw ApiException.badRequest("ASSISTANT_CONTENT_INVALID", "消息内容不能为空");
        }
        String trimmed = content.trim();
        if (trimmed.length() > MAX_CONTENT_LENGTH) {
            throw ApiException.badRequest("ASSISTANT_CONTENT_INVALID", "单次提问不能超过 4000 字");
        }
        return trimmed;
    }

    private String normalizeTitle(String title) {
        if (title == null || title.trim().isBlank()) {
            return "新对话";
        }
        String trimmed = title.trim().replaceAll("\s+", " ");
        return trimmed.length() > 100 ? trimmed.substring(0, 100) : trimmed;
    }

    private String makeTitle(String content) {
        String oneLine = content.replaceAll("\s+", " ").trim();
        return oneLine.length() > TITLE_MAX_LENGTH ? oneLine.substring(0, TITLE_MAX_LENGTH) : oneLine;
    }

    public record ChatStreamContext(
            Long userId,
            Long sessionId,
            String systemPrompt,
            List<AiChatMessage> messages,
            int effectiveMaxOutputTokens,
            int estimatedInputTokens,
            double temperature
    ) {
    }
}
