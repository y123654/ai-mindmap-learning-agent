package com.example.aimindmap.agentruntime;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 多查询改写（方案 §P1-5）的模型返回结构：只认一个字段 {@code query}。
 *
 * <p>用 {@link JsonIgnoreProperties}{@code (ignoreUnknown = true)} 是刻意的：改写模型常会多返回
 * {@code reason}、{@code keywords} 之类的字段，多字段不应该让改写失败（失败就白花一次调用）。
 * 只返回一个字段也让“非法 JSON / 空对象”这类失败很容易被判出来（见
 * {@link MultiQueryRetriever#buildQuerySet}）。
 *
 * @param query 改写后的一条检索串；为空或空白视为这次改写失败
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record QueryRewriteResult(String query) {
}
