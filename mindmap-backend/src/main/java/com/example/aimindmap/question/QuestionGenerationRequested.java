package com.example.aimindmap.question;

/**
 * 题目生成任务创建事务提交后触发后台执行。
 *
 * <p>阶段六-2 起仅在 app.mq.enabled=false 降级模式下发布（§3.5）；MQ 模式（缺省）
 * 创建任务同事务写 outbox，本事件类保留供降级链路使用，不再被 MQ 模式触发。
 */
public record QuestionGenerationRequested(Long jobId) {
}
