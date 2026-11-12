package com.example.aimindmap.ai;

/**
 * AI 调用结果：解析后的对象 + token 用量 + 耗时毫秒（§16.4）。
 */
public record AiJsonResult<T>(
        T value,
        AiUsage usage,
        long elapsedMillis
) {
}