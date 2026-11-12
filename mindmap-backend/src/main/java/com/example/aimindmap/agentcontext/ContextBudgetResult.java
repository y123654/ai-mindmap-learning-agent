package com.example.aimindmap.agentcontext;

import com.example.aimindmap.ai.AiChatMessage;
import java.util.List;

/**
 * 综合升级方案 阶段二 C：上下文预算计算结果。
 *
 * @param retained                 裁剪后保留的消息（时间正序，已含本轮用户提问）
 * @param estimatedInputTokens     估算的本次请求输入 token（系统提示词固定块 + 保留消息）
 * @param effectiveMaxOutputTokens 动态输出上限：取输出封顶值与窗口剩余空间的较小值
 * @param droppedMessages          被裁剪掉的旧消息条数（0 表示未裁剪）
 */
public record ContextBudgetResult(
        List<AiChatMessage> retained,
        int estimatedInputTokens,
        int effectiveMaxOutputTokens,
        int droppedMessages) {
}
