package com.example.aimindmap.common.error;

/**
 * 阶段七-3：接口限流超限异常，由 {@code RateLimitInterceptor} 在 {@code preHandle} 抛出，
 * 经 {@link GlobalExceptionHandler} 统一转成 HTTP 429 + {@code code=RATE_LIMITED} + 中文提示。
 *
 * <p>不复用 {@link ApiException}：429 的响应必须显式声明 {@code Content-Type: application/json}
 * （AI 问答 {@code POST /stream} 的请求 Accept 是 {@code text/event-stream}，走默认内容协商会
 * 因没有转换器能按该 Accept 写 JSON 而退化成 406），所以单独一个异常类型 + 单独一个 handler，
 * 避免改动既有 {@code handleApiException} 的通用行为。响应体格式仍是既有的
 * {@code {code, message, time}} 三字段。
 */
public class RateLimitExceededException extends RuntimeException {

    /** 作用域键（ai-chat / pdf-upload / sync-trigger），只用于日志与排查，不进响应体。 */
    private final String scope;

    public RateLimitExceededException(String scope, String message) {
        super(message);
        this.scope = scope;
    }

    public String getScope() {
        return scope;
    }
}