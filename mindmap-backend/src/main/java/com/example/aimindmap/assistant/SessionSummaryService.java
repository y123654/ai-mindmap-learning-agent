package com.example.aimindmap.assistant;

import com.example.aimindmap.ai.AiProperties;
import com.example.aimindmap.mqsupport.RetriableTaskException;
import com.example.aimindmap.mqsupport.TaskType;
import com.example.aimindmap.mqsupport.outbox.IdempotentConsumptionEntity;
import com.example.aimindmap.mqsupport.outbox.IdempotentConsumptionRepository;
import jakarta.transaction.Transactional;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * 会话摘要服务（方案 §5.3 / §5.4，阶段六-3 迁入 MQ）。
 * 规则：消息总数 > 20 才工作；保留最近 10 条原文；更早且未摘要的消息增量并入摘要；
 * 摘要目标长度 800~1200 字符。
 *
 * <p>两个入口共用同一套摘要核心逻辑（禁止复制两份业务逻辑）：
 * <ul>
 *   <li>{@link #generateSummary(Long, Long)}：app.mq.enabled=false 降级入口，保持历史
 *       "任何异常只记日志、不外抛、不影响聊天"的行为逐条不变；</li>
 *   <li>{@link #handleSummaryMessage(long)}：MQ 消费者入口，按异常类型/HTTP 状态分级：
 *       TransientAiException、WebClientResponseException(429/5xx)、DataAccessException
 *       抛 {@link RetriableTaskException} 走三档重试；NonTransientAiException 与其他
 *       WebClientResponseException(4xx 配置类，含未配 Key 的 401/403) 返回
 *       {@link SummaryOutcome#CONFIGURATION}，不落任何业务状态、消息进 DLQ 作运维信号；
 *       会话不存在/消息不足等正常跳过返回 {@link SummaryOutcome#SKIPPED} 并写幂等行；
 *       未预期异常记 error 后仍按业务跳过（尽力而为，不制造重试风暴）。</li>
 * </ul>
 */
@Service
public class SessionSummaryService {

    private static final Logger log = LoggerFactory.getLogger(SessionSummaryService.class);

    static final int SUMMARY_TRIGGER_MESSAGE_COUNT = 20;
    static final int KEEP_RECENT_MESSAGE_COUNT = 10;
    static final int SUMMARY_MIN_CHARS = 800;
    static final int SUMMARY_MAX_CHARS = 1200;
    /** 1200 中文字符约需千余 token，预留余量避免输出被截断。 */
    static final int SUMMARY_MAX_TOKENS = 1500;

    private final AiChatSessionRepository sessionRepository;
    private final AiChatMessageRepository messageRepository;
    private final ChatModel chatModel;
    private final AiProperties aiProperties;
    private final SessionSummaryService self;
    private final IdempotentConsumptionRepository idempotentConsumptionRepository;

    public SessionSummaryService(AiChatSessionRepository sessionRepository,
                                 AiChatMessageRepository messageRepository,
                                 ChatModel chatModel,
                                 AiProperties aiProperties,
                                 @Lazy SessionSummaryService self,
                                 IdempotentConsumptionRepository idempotentConsumptionRepository) {
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
        this.chatModel = chatModel;
        this.aiProperties = aiProperties;
        this.self = self;
        this.idempotentConsumptionRepository = idempotentConsumptionRepository;
    }

    /**
     * MQ 消费结果（阶段六-3）。
     * <ul>
     *   <li>{@link #SUMMARIZED}：摘要已更新，幂等行已在保存事务内写入；</li>
     *   <li>{@link #SKIPPED}：会话不存在/消息不足阈值/无新增批次/LLM 返回空白/未预期异常，
     *       属正常跳过，幂等行已写入，消费者 ack；</li>
     *   <li>{@link #CONFIGURATION}：AI 配置类失败（NonTransient/4xx 非 429），不落任何
     *       业务状态、不写幂等行，消息进 DLQ 作运维信号；</li>
     *   <li>{@link #TRIGGER_MISSING}：触发消息（助手消息行）不存在，永久毒消息，进 DLQ。</li>
     * </ul>
     */
    public enum SummaryOutcome {
        SUMMARIZED, SKIPPED, CONFIGURATION, TRIGGER_MISSING
    }

    /**
     * 降级入口（app.mq.enabled=false）：不在数据库事务内调用 LLM（沿用"AI 调用不放长事务"
     * 的约定）。行为与阶段六-3 之前逐条一致：会话不存在 warn、不足阈值/空白批次 debug、
     * 空白返回 warn、AI 可预期异常 warn、兜底 error，任何异常不外抛、不影响正常聊天。
     */
    public void generateSummary(Long sessionId, Long userId) {
        try {
            AiChatSessionEntity session = sessionRepository.findByIdAndUserId(sessionId, userId)
                    .orElse(null);
            if (session == null) {
                log.warn("会话摘要跳过：会话不存在或无权访问 sessionId={} userId={}", sessionId, userId);
                return;
            }
            runSummaryCore(session, null);
        } catch (TransientAiException | NonTransientAiException
                 | WebClientResponseException e) {
            log.warn("会话摘要失败（AI 服务可预期异常），不影响正常聊天 sessionId={} userId={} error={}",
                    sessionId, userId, e.toString());
        } catch (Exception e) {
            log.error("会话摘要发生未预期异常，不影响正常聊天 sessionId={} userId={}",
                    sessionId, userId, e);
        }
    }

    /**
     * MQ 入口（app.mq.enabled=true）：以触发消息（本次助手消息）ID 为幂等锚点。
     * 消息正文/提示词/AI 返回正文一律不打日志，只打 sessionId/userId/消息 id/长度/错误类型。
     */
    public SummaryOutcome handleSummaryMessage(long triggerMessageId) {
        String messageId = TaskType.SESSION_SUMMARY.wireName() + ":" + triggerMessageId;

        AiChatMessageEntity triggerMessage;
        try {
            triggerMessage = messageRepository.findById(triggerMessageId).orElse(null);
        } catch (DataAccessException e) {
            log.error("会话摘要加载触发消息时数据库异常，可重试 triggerMessageId={} errorType={}",
                    triggerMessageId, e.getClass().getSimpleName(), e);
            throw new RetriableTaskException(
                    "summary trigger load DataAccessException: " + e.getClass().getSimpleName(), e);
        }
        if (triggerMessage == null) {
            log.error("会话摘要触发消息不存在，不可重试，消息进 DLQ triggerMessageId={}", triggerMessageId);
            return SummaryOutcome.TRIGGER_MISSING;
        }

        Long sessionId = triggerMessage.getSessionId();
        AiChatSessionEntity session;
        try {
            // userId 从会话行取，做归属一致；MQ 消息只携带 sessionId，不信任消息外的用户参数。
            session = sessionRepository.findById(sessionId).orElse(null);
        } catch (DataAccessException e) {
            log.error("会话摘要加载会话时数据库异常，可重试 triggerMessageId={} sessionId={} errorType={}",
                    triggerMessageId, sessionId, e.getClass().getSimpleName(), e);
            throw new RetriableTaskException(
                    "summary session load DataAccessException: " + e.getClass().getSimpleName(), e);
        }
        if (session == null) {
            log.warn("会话摘要跳过：会话已不存在，写幂等行后 ack triggerMessageId={} sessionId={}",
                    triggerMessageId, sessionId);
            self.markConsumed(messageId);
            return SummaryOutcome.SKIPPED;
        }

        Long userId = session.getUserId();
        boolean summarized;
        try {
            summarized = runSummaryCore(session, triggerMessageId);
        } catch (TransientAiException e) {
            log.warn("会话摘要 AI 瞬时异常，可重试 triggerMessageId={} sessionId={} userId={} errorType={}",
                    triggerMessageId, sessionId, userId, e.getClass().getSimpleName());
            throw new RetriableTaskException(
                    "retriable AI failure: " + e.getClass().getSimpleName(), e);
        } catch (NonTransientAiException e) {
            log.warn("会话摘要配置类失败（AI 不可重试），不落业务状态，消息进 DLQ "
                            + "triggerMessageId={} sessionId={} userId={} errorType={}",
                    triggerMessageId, sessionId, userId, e.getClass().getSimpleName());
            return SummaryOutcome.CONFIGURATION;
        } catch (WebClientResponseException e) {
            int status = e.getStatusCode().value();
            if (status == 429 || (status >= 500 && status <= 599)) {
                log.warn("会话摘要 AI HTTP {}，可重试 triggerMessageId={} sessionId={} userId={}",
                        status, triggerMessageId, sessionId, userId);
                throw new RetriableTaskException("retriable AI failure: AI_HTTP_" + status, e);
            }
            log.warn("会话摘要配置类失败（HTTP {} 非可重试状态），不落业务状态，消息进 DLQ "
                            + "triggerMessageId={} sessionId={} userId={}",
                    status, triggerMessageId, sessionId, userId);
            return SummaryOutcome.CONFIGURATION;
        } catch (DataAccessException e) {
            log.error("会话摘要数据库异常，可重试 triggerMessageId={} sessionId={} userId={} errorType={}",
                    triggerMessageId, sessionId, userId, e.getClass().getSimpleName(), e);
            throw new RetriableTaskException(
                    "summary DataAccessException: " + e.getClass().getSimpleName(), e);
        } catch (RuntimeException e) {
            // 摘要链路是尽力而为：未预期错误不变成重试风暴，记 error 后按业务跳过并写幂等行。
            log.error("会话摘要发生未预期异常，按业务跳过（不重试） triggerMessageId={} sessionId={} userId={}",
                    triggerMessageId, sessionId, userId, e);
            self.markConsumed(messageId);
            return SummaryOutcome.SKIPPED;
        }

        if (!summarized) {
            self.markConsumed(messageId);
            return SummaryOutcome.SKIPPED;
        }
        return SummaryOutcome.SUMMARIZED;
    }

    /**
     * 摘要核心逻辑（mq=true / mq=false 两个入口共用，禁止复制业务逻辑）。
     *
     * @param triggerMessageId MQ 入口传本次助手消息 ID（幂等锚点）；mq=false 降级入口传 null，
     *                         此时保存摘要不写幂等行。
     * @return true 表示摘要已更新；false 表示正常跳过（消息不足阈值/无新增批次/LLM 空白）
     */
    private boolean runSummaryCore(AiChatSessionEntity session, Long triggerMessageId) {
        Long sessionId = session.getId();
        Long userId = session.getUserId();

        List<AiChatMessageEntity> messages =
                messageRepository.findBySessionIdOrderByIdAsc(sessionId);
        if (messages.size() <= SUMMARY_TRIGGER_MESSAGE_COUNT) {
            log.debug("会话摘要跳过：消息数未超阈值 sessionId={} userId={} count={}",
                    sessionId, userId, messages.size());
            return false;
        }

        int recentStart = messages.size() - KEEP_RECENT_MESSAGE_COUNT;
        long lastSummarizedId = session.getSummarizedUntilMessageId() == null
                ? 0L : session.getSummarizedUntilMessageId();
        List<AiChatMessageEntity> batch = new ArrayList<>();
        for (int i = 0; i < recentStart; i++) {
            AiChatMessageEntity message = messages.get(i);
            if (message.getId() != null && message.getId() > lastSummarizedId) {
                batch.add(message);
            }
        }
        if (batch.isEmpty()) {
            log.debug("会话摘要跳过：没有待摘要的新消息 sessionId={} userId={} lastSummarizedId={}",
                    sessionId, userId, lastSummarizedId);
            return false;
        }
        Long newUntilMessageId = batch.get(batch.size() - 1).getId();

        log.info("开始生成会话摘要 sessionId={} userId={} totalMessages={} batchSize={} incremental={}",
                sessionId, userId, messages.size(), batch.size(),
                session.getSummary() != null && !session.getSummary().isBlank());
        String summary = requestSummary(session.getSummary(), batch);

        if (summary.isBlank()) {
            log.warn("会话摘要失败：LLM 返回空内容 sessionId={} userId={}", sessionId, userId);
            return false;
        }
        if (summary.length() > SUMMARY_MAX_CHARS) {
            log.warn("会话摘要超出目标长度，按上限截断 sessionId={} userId={} actualChars={} maxChars={}",
                    sessionId, userId, summary.length(), SUMMARY_MAX_CHARS);
            summary = summary.substring(0, SUMMARY_MAX_CHARS);
        }

        if (triggerMessageId == null) {
            self.saveSummary(sessionId, userId, summary, newUntilMessageId);
        } else {
            self.saveSummary(sessionId, userId, summary, newUntilMessageId, triggerMessageId);
        }
        log.info("会话摘要已更新 sessionId={} userId={} summaryChars={} summarizedUntilMessageId={}",
                sessionId, userId, summary.length(), newUntilMessageId);
        return true;
    }

    /** 降级入口（mq=false）使用：只更新会话三列，不写幂等行。 */
    @Transactional
    public void saveSummary(Long sessionId, Long userId, String summary, Long untilMessageId) {
        updateSummaryColumns(sessionId, userId, summary, untilMessageId);
    }

    /**
     * MQ 入口使用：更新会话三列与幂等行同一事务；幂等行唯一冲突外抛
     * （DataIntegrityViolationException），由消费者按重复投递 ack，业务更新随事务回滚。
     */
    @Transactional
    public void saveSummary(Long sessionId, Long userId, String summary, Long untilMessageId,
                            Long triggerMessageId) {
        updateSummaryColumns(sessionId, userId, summary, untilMessageId);
        idempotentConsumptionRepository.saveAndFlush(new IdempotentConsumptionEntity(
                TaskType.SESSION_SUMMARY.wireName() + ":" + triggerMessageId));
    }

    /** SKIPPED 场景写幂等行（独立事务）；唯一冲突外抛由消费者按重复投递 ack。 */
    @Transactional
    public void markConsumed(String messageId) {
        idempotentConsumptionRepository.saveAndFlush(new IdempotentConsumptionEntity(messageId));
    }

    private void updateSummaryColumns(Long sessionId, Long userId, String summary,
                                      Long untilMessageId) {
        AiChatSessionEntity session = sessionRepository.findByIdAndUserId(sessionId, userId)
                .orElseThrow(() -> new IllegalStateException(
                        "待更新摘要的会话不存在: sessionId=" + sessionId + " userId=" + userId));
        session.setSummary(summary);
        session.setSummarizedUntilMessageId(untilMessageId);
        session.setSummaryUpdatedAt(java.time.Instant.now());
        sessionRepository.save(session);
    }

    private String requestSummary(String previousSummary, List<AiChatMessageEntity> batch) {
        SystemMessage systemMessage = new SystemMessage("""
                你是会话摘要助手。请根据给定的对话内容生成或更新中文会话摘要，要求：
                1. 摘要长度控制在 800~1200 字符；
                2. 概括讨论主题、关键知识点、已得出的结论、用户的疑问与薄弱点；
                3. 保留对后续回答有用的事实，丢弃寒暄与重复内容；
                4. 只输出摘要正文，不输出标题、前缀、解释或标记符号。
                """);
        StringBuilder userContent = new StringBuilder();
        if (previousSummary != null && !previousSummary.isBlank()) {
            userContent.append("已有摘要：\n").append(previousSummary.strip())
                    .append("\n\n以下是本阶段新增的对话记录，")
                    .append("请把已有摘要与新对话合并、压缩成一段新的摘要：\n");
        } else {
            userContent.append("以下是会话中较早的对话记录，请生成摘要：\n");
        }
        for (AiChatMessageEntity message : batch) {
            userContent.append("assistant".equals(message.getRole()) ? "[助手] " : "[用户] ")
                    .append(message.getContent()).append('\n');
        }
        Message userMessage = new UserMessage(userContent.toString());
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(aiProperties.getModel())
                .temperature(aiProperties.getTemperature())
                .maxTokens(SUMMARY_MAX_TOKENS)
                .build();
        ChatResponse response = chatModel.call(new Prompt(List.of(systemMessage, userMessage), options));
        if (response == null || response.getResult() == null
                || response.getResult().getOutput() == null) {
            return "";
        }
        String text = response.getResult().getOutput().getText();
        return text == null ? "" : text.trim();
    }
}
