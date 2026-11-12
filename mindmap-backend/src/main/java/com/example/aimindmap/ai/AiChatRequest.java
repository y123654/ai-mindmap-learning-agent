package com.example.aimindmap.ai;

import java.util.List;

/**
 * AI 知识助手使用的普通 Markdown 对话请求。
 */
public record AiChatRequest(
        String systemPrompt,
        List<AiChatMessage> messages,
        int maxOutputTokens,
        double temperature
) {
}
