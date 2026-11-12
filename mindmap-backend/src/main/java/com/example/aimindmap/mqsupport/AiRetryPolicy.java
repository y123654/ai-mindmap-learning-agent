package com.example.aimindmap.mqsupport;

/**
 * AI 错误码重试/配置分级策略（方案《阶段六》：429/5xx/超时可重试；配置类 4xx 直接死信）。
 *
 * <p>集中放置、纯函数无状态，可单测；阶段 6-1 同步、6-2 出题、6-3 摘要三条 AI 链路
 * 消费者共用同一套判定，禁止在各业务包内各写一份。
 *
 * <ul>
 *   <li>可重试：{@code AI_TIMEOUT}、{@code AI_HTTP_429}、{@code AI_HTTP_500~599}；</li>
 *   <li>配置类（不可重试，任务落 FAILED 后进 DLQ 作运维信号）：
 *       {@code AI_NOT_CONFIGURED}、{@code AI_HTTP_400~499}（429 除外）；</li>
 *   <li>其余 AI 码（AI_INPUT_TOO_LARGE、AI_RESPONSE_*、AI_JSON_PARSE_FAILED、
 *       AI_STREAM_*、AI_CALL_FAILED 等内容/响应类）：不可重试、非配置类，按业务失败落 FAILED 并 ack。</li>
 * </ul>
 */
public final class AiRetryPolicy {

    /** AI 调用超时（含网络读超时）：瞬时故障，可重试。 */
    public static final String CODE_AI_TIMEOUT = "AI_TIMEOUT";
    /** AI 限流：退避后可重试。 */
    public static final String CODE_AI_RATE_LIMITED = "AI_HTTP_429";
    /** 未配置 AI 接口：配置问题，重试无意义。 */
    public static final String CODE_AI_NOT_CONFIGURED = "AI_NOT_CONFIGURED";

    private static final String AI_HTTP_PREFIX = "AI_HTTP_";

    private AiRetryPolicy() {
    }

    /**
     * 该 AI 错误码是否属于可重试瞬时故障（超时 / 429 / 5xx）。
     * 其余码一律不可重试（含 null、未知码与格式非法的 AI_HTTP_* 码）。
     */
    public static boolean isRetriableCode(String code) {
        if (CODE_AI_TIMEOUT.equals(code) || CODE_AI_RATE_LIMITED.equals(code)) {
            return true;
        }
        Integer httpStatus = parseHttpStatus(code);
        return httpStatus != null && httpStatus >= 500 && httpStatus <= 599;
    }

    /**
     * 该 AI 错误码是否属于配置类问题（未配置 / 4xx 鉴权与请求配置问题，429 除外）。
     * 配置类失败重试无意义：任务落 FAILED 终态后消息进 DLQ 仅作运维信号。
     */
    public static boolean isConfigurationCode(String code) {
        if (CODE_AI_NOT_CONFIGURED.equals(code)) {
            return true;
        }
        Integer httpStatus = parseHttpStatus(code);
        return httpStatus != null && httpStatus >= 400 && httpStatus <= 499 && httpStatus != 429;
    }

    /**
     * 解析 AI_HTTP_&lt;status&gt; 中的 HTTP 状态码；非该前缀、后缀非纯数字时返回 null。
     */
    private static Integer parseHttpStatus(String code) {
        if (code == null || !code.startsWith(AI_HTTP_PREFIX)) {
            return null;
        }
        String suffix = code.substring(AI_HTTP_PREFIX.length());
        if (suffix.isEmpty()) {
            return null;
        }
        for (int i = 0; i < suffix.length(); i++) {
            if (!Character.isDigit(suffix.charAt(i))) {
                return null;
            }
        }
        try {
            return Integer.valueOf(suffix);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
