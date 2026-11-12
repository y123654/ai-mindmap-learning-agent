package com.example.aimindmap.mqsupport;

/**
 * 消费者可重试任务异常（方案《阶段六》AI 异常分级，6-1/6-2/6-3 三条 AI 链路共用）。
 *
 * <p>语义：基础设施瞬时故障（如数据库短暂不可用）或 AI 限流/超时
 * （AI_TIMEOUT、AI_HTTP_429、AI_HTTP_5xx，判定见 {@link AiRetryPolicy}）。
 * 抛出本异常时业务任务不落任何终态，MQ 消费者按消息头重试计数走
 * 5s/30s/2m 三档延时重试；三档耗尽进 DLQ，任务行留在中间态由对账兜底复位续跑。
 *
 * <p>{@code reason} 为简短原因短语（只允许错误码/异常摘要，禁止携带提示词、
 * 文档正文等敏感内容），随消费日志与重试日志输出，便于运维定位。
 */
public class RetriableTaskException extends RuntimeException {

    private final String reason;

    public RetriableTaskException(String reason, Throwable cause) {
        super(reason, cause);
        this.reason = reason;
    }

    /** 简短可重试原因（错误码/异常摘要），供消费者日志记录，禁止包含敏感正文。 */
    public String getReason() {
        return reason;
    }
}
