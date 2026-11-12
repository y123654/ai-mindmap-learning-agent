package com.example.aimindmap.ai;

/**
 * AI 调用失败（未配置、超时、HTTP 错误、JSON 解析失败等）。
 * code 写入同步任务 error_code（§5.3 / §10.4），失败时不覆盖旧思维导图。
 */
public class AiGatewayException extends RuntimeException {

    private final String code;

    public AiGatewayException(String code, String message) {
        super(message);
        this.code = code;
    }

    public AiGatewayException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public static AiGatewayException notConfigured() {
        return new AiGatewayException("AI_NOT_CONFIGURED",
                "尚未配置 AI 接口（普通段落归纳需要 AI）。请配置环境变量 AI_API_KEY 后重启后端，"
                        + "或本次只使用标题和列表（不含普通段落）。");
    }

    public String getCode() {
        return code;
    }
}