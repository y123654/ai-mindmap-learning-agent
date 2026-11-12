package com.example.aimindmap.ai;

/**
 * OpenAI 兼容 usage 字段（服务商未返回时为 0）。
 */
public record AiUsage(
        int promptTokens,
        int completionTokens,
        int totalTokens
) {
    public static AiUsage empty() {
        return new AiUsage(0, 0, 0);
    }

    /** 合并多个 AI 小批次的 token 用量。 */
    public AiUsage plus(AiUsage other) {
        if (other == null) {
            return this;
        }
        return new AiUsage(
                promptTokens + other.promptTokens,
                completionTokens + other.completionTokens,
                totalTokens + other.totalTokens);
    }
}