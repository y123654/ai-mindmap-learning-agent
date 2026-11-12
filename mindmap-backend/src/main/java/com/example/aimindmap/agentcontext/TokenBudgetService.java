package com.example.aimindmap.agentcontext;

import com.example.aimindmap.ai.AiChatMessage;
import com.example.aimindmap.ai.AiProperties;
import com.example.aimindmap.common.error.ApiException;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 综合升级方案 阶段二 C：基于 token 保守估算的上下文裁剪与动态输出预算。
 *
 * <p>候选消息口径沿用阶段二之前的现状：有会话摘要取最近 top10、无摘要取最近 top30，
 * 调用方负责取出并按时间正序传入。系统提示词（含长期记忆块 / 会话摘要块）作为固定部分，
 * 只计 token、永不裁剪；记忆块自身的 1200 字符预算维持现状，不在本服务处理。</p>
 *
 * <p>裁剪顺序固定为“从最旧消息开始移除”，但最后一条用户消息（本轮提问）必须保留；
 * 即使只保留本轮提问，输出空间仍低于下限时抛出现有错误码 ASSISTANT_CONTEXT_TOO_LONG。</p>
 */
@Service
public class TokenBudgetService {

    /** 复用知识助手既有错误码，禁止新造。 */
    public static final String CONTEXT_TOO_LONG_CODE = "ASSISTANT_CONTEXT_TOO_LONG";
    private static final String CONTEXT_TOO_LONG_MESSAGE =
            "本次提问和上下文过长，请开启新对话或把问题拆小后重试";

    private static final Logger log = LoggerFactory.getLogger(TokenBudgetService.class);

    private final TokenEstimator tokenEstimator;
    private final AiProperties aiProperties;

    public TokenBudgetService(TokenEstimator tokenEstimator, AiProperties aiProperties) {
        this.tokenEstimator = tokenEstimator;
        this.aiProperties = aiProperties;
    }

    /**
     * @param sessionId  会话 ID（仅用于日志）
     * @param systemPrompt 系统提示词全文（含长期记忆块 / 摘要块），永不裁剪
     * @param candidates 候选消息，时间正序，最后一条为本轮用户提问
     */
    public ContextBudgetResult compute(Long sessionId, String systemPrompt,
                                       List<AiChatMessage> candidates) {
        int fixedTokens = tokenEstimator.estimateText(systemPrompt);

        int windowInputBudget = aiProperties.getContextWindowTokens()
                - aiProperties.getOutputReserveTokens()
                - aiProperties.getMinOutputTokens();
        int effectiveInputBudget = Math.min(aiProperties.getMaxInputTokens(), windowInputBudget);

        List<AiChatMessage> retained = new ArrayList<>(candidates);
        int anchorIndex = lastUserMessageIndex(retained);
        int estimatedInputTokens = fixedTokens + sumMessageTokens(retained);

        while (estimatedInputTokens > effectiveInputBudget && canRemoveOldest(retained, anchorIndex)) {
            AiChatMessage removed = retained.remove(0);
            if (anchorIndex >= 0) {
                anchorIndex--;
            }
            estimatedInputTokens -= tokenEstimator.estimateMessage(removed.role(), removed.content());
        }

        int available = aiProperties.getContextWindowTokens() - estimatedInputTokens
                - aiProperties.getOutputReserveTokens();
        if (available < aiProperties.getMinOutputTokens()) {
            log.warn("上下文超出窗口下限，判定上下文过长 sessionId={} candidateCount={} retainedCount={} "
                            + "estimatedInputTokens={} availableTokens={} minOutputTokens={}",
                    sessionId, candidates.size(), retained.size(), estimatedInputTokens,
                    available, aiProperties.getMinOutputTokens());
            throw ApiException.badRequest(CONTEXT_TOO_LONG_CODE, CONTEXT_TOO_LONG_MESSAGE);
        }

        int effectiveMaxOutputTokens = Math.min(aiProperties.getMaxOutputTokens(), available);
        int droppedMessages = candidates.size() - retained.size();
        if (droppedMessages > 0) {
            log.info("长会话按 token 裁剪上下文 sessionId={} candidateCount={} retainedCount={} "
                            + "droppedMessages={} estimatedInputTokens={} effectiveMaxOutputTokens={}",
                    sessionId, candidates.size(), retained.size(), droppedMessages,
                    estimatedInputTokens, effectiveMaxOutputTokens);
        }
        return new ContextBudgetResult(
                List.copyOf(retained), estimatedInputTokens, effectiveMaxOutputTokens, droppedMessages);
    }

    private int sumMessageTokens(List<AiChatMessage> messages) {
        int total = 0;
        for (AiChatMessage message : messages) {
            total += tokenEstimator.estimateMessage(message.role(), message.content());
        }
        return total;
    }

    /** 最后一条用户消息即本轮提问，是裁剪锚点，任何情况下都不能被移除。 */
    private static int lastUserMessageIndex(List<AiChatMessage> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if ("user".equals(messages.get(i).role())) {
                return i;
            }
        }
        return -1;
    }

    /** 只允许从最旧（index=0）移除；锚点位于 index 0 或只剩一条时停止裁剪。 */
    private static boolean canRemoveOldest(List<AiChatMessage> retained, int anchorIndex) {
        return retained.size() > 1 && anchorIndex != 0;
    }
}
