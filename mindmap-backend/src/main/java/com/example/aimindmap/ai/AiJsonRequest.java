package com.example.aimindmap.ai;

/**
 * v1.1 §13.1 AI JSON 请求（字段为方案要求的最小集合）。
 *
 * @param systemPrompt    系统提示词
 * @param userPrompt      用户提示词
 * @param requireJson     是否要求 JSON 输出
 * @param maxOutputTokens 回复最大 token 数
 * @param temperature     生成随机性
 */
public record AiJsonRequest(
        String systemPrompt,
        String userPrompt,
        boolean requireJson,
        int maxOutputTokens,
        double temperature
) {
}