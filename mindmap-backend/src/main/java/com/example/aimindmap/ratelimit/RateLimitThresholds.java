package com.example.aimindmap.ratelimit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 阶段七-3：限流阈值配置（附录 A 第 363 行，三个键，一个不多）。
 *
 * <table border="1">
 *   <caption>配置键与默认值</caption>
 *   <tr><th>配置键</th><th>环境变量</th><th>默认</th><th>作用域</th></tr>
 *   <tr><td>{@code app.ratelimit.ai-per-min}</td><td>{@code APP_RATELIMIT_AI_PER_MIN}</td>
 *       <td>20</td><td>{@link RateLimitScope#AI_CHAT}</td></tr>
 *   <tr><td>{@code app.ratelimit.pdf-per-min}</td><td>{@code APP_RATELIMIT_PDF_PER_MIN}</td>
 *       <td>5</td><td>{@link RateLimitScope#PDF_UPLOAD}</td></tr>
 *   <tr><td>{@code app.ratelimit.sync-per-min}</td><td>{@code APP_RATELIMIT_SYNC_PER_MIN}</td>
 *       <td>10</td><td>{@link RateLimitScope#SYNC_TRIGGER}</td></tr>
 * </table>
 *
 * <p><b>阈值 &le; 0 的语义 = 该作用域不限流。</b>这是方案 §3.5「所有新能力默认可一键关闭」在
 * 限流上的落点：把三个阈值同时设 0 即整体关闭限流，因此不需要（也不允许）再新增
 * {@code app.ratelimit.enabled} 之类附录 A 之外的开关。全部测试上下文默认压成 0，
 * 见 {@code TestRedisDisableEnvironmentPostProcessor}。
 *
 * <p>阈值在上下文启动时经 {@code @Value} 注入，运行期可用环境变量 / 命令行参数覆盖，
 * 测试用 {@code @TestPropertySource} 或 {@code startContext} 参数即可改（方案 321 行「阈值可配且有测试」）。
 */
@Component
public class RateLimitThresholds {

    private final int aiPerMinute;
    private final int pdfPerMinute;
    private final int syncPerMinute;

    public RateLimitThresholds(@Value("${app.ratelimit.ai-per-min:20}") int aiPerMinute,
                               @Value("${app.ratelimit.pdf-per-min:5}") int pdfPerMinute,
                               @Value("${app.ratelimit.sync-per-min:10}") int syncPerMinute) {
        this.aiPerMinute = aiPerMinute;
        this.pdfPerMinute = pdfPerMinute;
        this.syncPerMinute = syncPerMinute;
    }

    /** 作用域 → 每分钟阈值；&le; 0 表示该作用域不限流。 */
    public int limitOf(RateLimitScope scope) {
        return switch (scope) {
            case AI_CHAT -> aiPerMinute;
            case PDF_UPLOAD -> pdfPerMinute;
            case SYNC_TRIGGER -> syncPerMinute;
        };
    }

    public int aiPerMinute() {
        return aiPerMinute;
    }

    public int pdfPerMinute() {
        return pdfPerMinute;
    }

    public int syncPerMinute() {
        return syncPerMinute;
    }

    @Override
    public String toString() {
        return "RateLimitThresholds[ai-per-min=" + aiPerMinute
                + ", pdf-per-min=" + pdfPerMinute
                + ", sync-per-min=" + syncPerMinute + "]";
    }
}