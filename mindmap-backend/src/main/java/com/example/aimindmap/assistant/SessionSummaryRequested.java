package com.example.aimindmap.assistant;

/**
 * 助手回答落库后请求生成/更新会话摘要（方案 §5.4）。
 * 阶段六-3 起仅在 app.mq.enabled=false 降级路径发布；mq=true（缺省）时发布点改为
 * 同事务写 outbox（TaskType.SESSION_SUMMARY），本事件不再发出。
 * 监听方在事务提交后异步执行，失败不影响主聊天。
 */
public record SessionSummaryRequested(Long userId, Long sessionId) {
}
