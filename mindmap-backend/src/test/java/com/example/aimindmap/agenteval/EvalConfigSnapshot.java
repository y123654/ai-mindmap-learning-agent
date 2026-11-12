package com.example.aimindmap.agenteval;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.core.env.Environment;

/**
 * P1-F 开工前必办③：<b>评测时的开关快照</b>。
 *
 * <p>为什么要有它：P1-D1/E1/E2c/E3b 四段都改过默认行为（工具预算软降级、多查询、LLM 改写默认关、
 * evidence guard），同一个评测集在不同开关组合下的指标<b>不是同一口径</b>。把当时的开关值
 * 跟指标一起落盘，才能回答「这次 P1-F 的 F1 是在哪套开关下测出来的」。
 *
 * <p>两条硬约束：
 * <ol>
 *   <li>值一律从 Spring {@link Environment} 读，<b>不硬编码</b>默认值——写死默认值一旦配置漂移，
 *       报告就会说谎；</li>
 *   <li>缺失键写「（未配置）」，不猜。宁可暴露"这个键没配"，也不假装知道它的值。</li>
 * </ol>
 *
 * <p>同一份快照同时进 {@code AgentEvalIT} 的评测报告（JSON {@code configSnapshot} + MD 1.1 节）
 * 和 {@code AgentOfflineAuditIT} 的离线审计报告（这样不用花钱跑评测，也能先确认本次口径）。
 */
public final class EvalConfigSnapshot {

    /**
     * 需要记录的键（方案 §P1-F 开工前必办③列了 9 个，这里只多不少）。
     *
     * <p>额外加的 4 个都直接影响指标口径：{@code max-tool-calls}（工具调用上限，平均工具数的天花板）、
     * {@code router.llm-enabled}（意图分类是否调小模型）、{@code tool-cache.enabled}（缓存命中数）、
     * {@code app.qdrant.min-score}（检索阈值，直接决定证据条数）。
     */
    public static final List<String> KEYS = List.of(
            "app.agent.runtime.enabled",
            "app.agent.retrieval.multi-query-enabled",
            "app.agent.retrieval.rewrite-llm-enabled",
            "app.agent.retrieval.max-results",
            "app.agent.retrieval.max-chunks-per-document",
            "app.agent.security.evidence-guard-enabled",
            "app.agent.tool-budget-soft",
            "app.agent.timeout-seconds",
            "app.agent.tool-timeout-seconds",
            "app.agent.max-tool-calls",
            "app.agent.runtime.router.llm-enabled",
            "app.agent.tool-cache.enabled",
            "app.qdrant.min-score");

    /** 缺失键的占位符（不猜默认值）。 */
    public static final String MISSING = "（未配置）";

    private EvalConfigSnapshot() {
    }

    /** 按 {@link #KEYS} 的固定顺序抓一份快照（顺序固定 = 报告 diff 稳定）。 */
    public static Map<String, String> capture(Environment environment) {
        Map<String, String> snapshot = new LinkedHashMap<>();
        for (String key : KEYS) {
            String value = environment == null ? null : environment.getProperty(key);
            snapshot.put(key, value == null || value.isBlank() ? MISSING : value);
        }
        return snapshot;
    }

    /** 快照的单行日志形式（键=值，便于在日志里一眼复现口径；不含任何密钥/正文）。 */
    public static String oneLine(Map<String, String> snapshot) {
        StringBuilder line = new StringBuilder();
        snapshot.forEach((key, value) -> {
            if (line.length() > 0) {
                line.append("，");
            }
            line.append(key).append('=').append(value);
        });
        return line.toString();
    }

    /** 快照的 Markdown 表格（报告 1.1 节）。 */
    public static String markdownTable(Map<String, String> snapshot) {
        StringBuilder md = new StringBuilder();
        md.append("| 开关 | 本次取值 |\n|---|---|\n");
        snapshot.forEach((key, value) ->
                md.append("| `").append(key).append("` | `").append(value).append("` |\n"));
        return md.toString();
    }
}
