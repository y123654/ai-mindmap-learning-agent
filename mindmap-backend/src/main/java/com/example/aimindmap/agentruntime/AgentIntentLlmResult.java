package com.example.aimindmap.agentruntime;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 小模型意图分类的返回结构（方案 §P1-1）：只认一个字段 {@code intent}。
 *
 * <p>用 {@link JsonIgnoreProperties}{@code (ignoreUnknown = true)} 是刻意的：
 * 小模型经常会多返回 {@code reason}、{@code confidence} 之类的字段，
 * 多字段不应该让分类失败（失败会平白降级成 RETRIEVE）。
 *
 * @param intent 意图名，<b>大小写不敏感</b>；必须是 {@link AgentIntent} 的枚举名
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AgentIntentLlmResult(String intent) {
}
