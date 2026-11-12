package com.example.aimindmap.agentruntime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 检索质量配置（方案 §P1-5）。
 *
 * <p><b>多查询开关</b>：{@code app.agent.retrieval.multi-query-enabled}（环境变量
 * {@code AGENT_RETRIEVAL_MULTI_QUERY_ENABLED}）
 * <ul>
 *   <li><b>true（默认）</b>：{@code search_my_documents / search_mindmap_nodes / search_my_questions}
 *       走 {@link MultiQueryRetriever}（原 query + 至多 2 个改写、RRF 融合、每篇最多 N 个 chunk）；</li>
 *   <li><b>false</b>：<b>完全走旧路径</b>——1 次向量检索、每篇 1 个 chunk，用于回滚与 A/B。</li>
 * </ul>
 *
 * <p><b>LLM 改写开关</b>：{@code app.agent.retrieval.rewrite-llm-enabled}（环境变量
 * {@code AGENT_RETRIEVAL_REWRITE_LLM_ENABLED}）
 * <ul>
 *   <li><b>false（默认，所有者 2026-10-28 决定）</b>：{@code MultiQueryRetriever} <b>完全不碰
 *       {@code AiGateway}</b>（连 {@code configured()} 探测都不做），只用规则改写（原 query +
 *       至多 1 个清洗变体），延迟与旧基线同量级；</li>
 *   <li><b>true</b>：恢复“LLM 优先（2 路改写）、失败/超时降级规则改写”的旧行为。</li>
 * </ul>
 * 默认关的依据是两次真实测量（{@code docs/P1-E2-检索质量测量验收记录-20261028.md}、
 * {@code docs/P1-E2b-干扰压力实验验收记录-20261028.md}）：LLM 改写 <b>P50 +780~863ms</b>
 * （960~1043ms vs 176~198ms），<b>召回 +0.0pp</b>（干净夹具与干扰夹具都没测出收益）。
 * 多查询与 RRF 机制本身<b>保留且默认开</b>（规则改写零 LLM 成本），等真实语料再验证。
 *
 * <p><b>两个上限做成配置</b>（所有者 2026-10-27 裁定）：下一段要用它们扫
 * “cap 5 vs 8”“每篇 1 vs 2”的组合来定最优点，P1-F 也要靠它们做 A/B 与回滚，写死就没法测。
 * <b>默认值与改造前完全一致（5 / 2）</b>，因此不配置时行为不变。
 *
 * <p><b>只有多查询路径受这两个上限约束</b>：开关关闭时条数仍由
 * {@code VectorSearchService} 里既有的 {@code app.qdrant.search-max-results} 收敛，逐字节保持旧行为。
 *
 * <p>越界值不会被接受：{@code max-results} 必须在 1~10、{@code max-chunks-per-document} 必须在 1~3，
 * 越界<strong>回退默认值并打 warn</strong>（避免运维手抖配出 500 条把上下文炸掉）。
 *
 * <p>仍在规格里固定的口径（不配置）：改写超时 2s / 改写合计 4s / 检索合计 4s / RRF 常数 60。
 * 其中两个改写预算只在 {@code rewrite-llm-enabled=true} 时才会消耗；默认关时检索预算仍是 4s。
 *
 * <p>本类不包含任何密钥。
 */
@ConfigurationProperties(prefix = "app.agent.retrieval")
public class RetrievalProperties {

    private static final Logger log = LoggerFactory.getLogger(RetrievalProperties.class);

    /** {@code max-results} 的默认值（= 改造前的 {@code app.qdrant.search-max-results} 默认值 5）。 */
    public static final int DEFAULT_MAX_RESULTS = 5;

    /** {@code max-chunks-per-document} 的默认值（= 方案 §P1-5 原文的 2）。 */
    public static final int DEFAULT_MAX_CHUNKS_PER_DOCUMENT = 2;

    /** {@code max-results} 允许区间下限。 */
    public static final int MIN_MAX_RESULTS = 1;

    /** {@code max-results} 允许区间上限。 */
    public static final int MAX_MAX_RESULTS = 10;

    /** {@code max-chunks-per-document} 允许区间下限。 */
    public static final int MIN_MAX_CHUNKS_PER_DOCUMENT = 1;

    /** {@code max-chunks-per-document} 允许区间上限。 */
    public static final int MAX_MAX_CHUNKS_PER_DOCUMENT = 3;

    /** {@code rewrite-llm-enabled} 的默认值：关（所有者 2026-10-28 决定，依据 E2/E2b 两次测量）。 */
    public static final boolean DEFAULT_REWRITE_LLM_ENABLED = false;

    /** 多查询 + RRF 总开关；关掉时完全走旧路径（单 query、每篇 1 chunk）。 */
    private boolean multiQueryEnabled = true;

    /**
     * LLM 查询改写开关；<b>默认关</b>（只用规则改写，零 LLM 调用）。
     *
     * <p>关着时 {@link MultiQueryRetriever} 连 {@code AiGateway.configured()} 都不探测；
     * 打开才恢复“LLM 优先、失败降级规则”的旧行为。
     */
    private boolean rewriteLlmEnabled = DEFAULT_REWRITE_LLM_ENABLED;

    /** 多查询融合后最多返回多少条（1~10，默认 5）。 */
    private int maxResults = DEFAULT_MAX_RESULTS;

    /** 同一篇文档最多保留多少个 chunk（1~3，默认 2）。 */
    private int maxChunksPerDocument = DEFAULT_MAX_CHUNKS_PER_DOCUMENT;

    public boolean isMultiQueryEnabled() {
        return multiQueryEnabled;
    }

    public void setMultiQueryEnabled(boolean multiQueryEnabled) {
        this.multiQueryEnabled = multiQueryEnabled;
    }

    public boolean isRewriteLlmEnabled() {
        return rewriteLlmEnabled;
    }

    public void setRewriteLlmEnabled(boolean rewriteLlmEnabled) {
        this.rewriteLlmEnabled = rewriteLlmEnabled;
    }

    public int getMaxResults() {
        return maxResults;
    }

    /** 越界（<1 或 >10）时不生效：回退默认值并 warn，避免一次误配把上下文炸掉。 */
    public void setMaxResults(int maxResults) {
        if (maxResults < MIN_MAX_RESULTS || maxResults > MAX_MAX_RESULTS) {
            log.warn("配置 app.agent.retrieval.max-results={} 越界（允许 {}~{}），回退默认值 {}",
                    maxResults, MIN_MAX_RESULTS, MAX_MAX_RESULTS, DEFAULT_MAX_RESULTS);
            this.maxResults = DEFAULT_MAX_RESULTS;
            return;
        }
        this.maxResults = maxResults;
    }

    public int getMaxChunksPerDocument() {
        return maxChunksPerDocument;
    }

    /** 越界（<1 或 >3）时不生效：回退默认值并 warn。 */
    public void setMaxChunksPerDocument(int maxChunksPerDocument) {
        if (maxChunksPerDocument < MIN_MAX_CHUNKS_PER_DOCUMENT
                || maxChunksPerDocument > MAX_MAX_CHUNKS_PER_DOCUMENT) {
            log.warn("配置 app.agent.retrieval.max-chunks-per-document={} 越界（允许 {}~{}），回退默认值 {}",
                    maxChunksPerDocument, MIN_MAX_CHUNKS_PER_DOCUMENT, MAX_MAX_CHUNKS_PER_DOCUMENT,
                    DEFAULT_MAX_CHUNKS_PER_DOCUMENT);
            this.maxChunksPerDocument = DEFAULT_MAX_CHUNKS_PER_DOCUMENT;
            return;
        }
        this.maxChunksPerDocument = maxChunksPerDocument;
    }
}
