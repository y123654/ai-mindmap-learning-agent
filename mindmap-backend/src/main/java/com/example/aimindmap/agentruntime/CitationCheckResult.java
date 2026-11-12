package com.example.aimindmap.agentruntime;

import java.util.List;

/**
 * 引用校验结果（方案 §P0-3 + §P1-3）。
 *
 * <p>前 6 个字段是 P0-3 的既有口径，<b>语义一律不变</b>：
 *
 * @param citationCount         合法引用的<b>不同编号</b>数量（等于 {@code citations} 中 valid=true 的条数）
 * @param invalidCitationCount  非法编号的出现次数（模型引用了台账里没有的 E 编号）
 * @param citationCoverage      引用覆盖率 = 有合法引用的「事实段落」数 / 事实段落总数（0~1）
 * @param grounded              是否至少有一个合法引用
 * @param ungrounded            知识类回答且没有任何合法引用（需要追加免责提示）
 * @param citations             本次回答出现过的全部编号及是否合法（去重，供 agent_citations 落库）
 *
 * <p>P1-D2 新增两个<b>并列</b>字段（只做观测与「补充说明」，不参与上面 4 个指标的算法）：
 *
 * @param unsupportedSentences  「关键结论句」里没有任何合法 {@code [E#]} 引用时的<b>短摘要</b>
 *                              （最长 60 字，不存全文；顺序 = 出现顺序）
 * @param unsupportedPoints     与 {@link #unsupportedSentences} 一一对应的「第几点」
 *                              （编号口径：在<b>关键结论句</b>里从 1 开始计数，不是全句序号）
 */
public record CitationCheckResult(
        int citationCount,
        int invalidCitationCount,
        double citationCoverage,
        boolean grounded,
        boolean ungrounded,
        List<Citation> citations,
        List<String> unsupportedSentences,
        List<Integer> unsupportedPoints) {

    public CitationCheckResult {
        citations = citations == null ? List.of() : List.copyOf(citations);
        unsupportedSentences = unsupportedSentences == null
                ? List.of() : List.copyOf(unsupportedSentences);
        unsupportedPoints = unsupportedPoints == null ? List.of() : List.copyOf(unsupportedPoints);
        if (unsupportedSentences.size() != unsupportedPoints.size()) {
            throw new IllegalArgumentException("unsupportedSentences 与 unsupportedPoints 必须一一对应");
        }
    }

    /** P0-3 兼容构造：不关心逐句检查的调用方（旧单测/旧装配）仍可用 6 参构造。 */
    public CitationCheckResult(int citationCount,
                               int invalidCitationCount,
                               double citationCoverage,
                               boolean grounded,
                               boolean ungrounded,
                               List<Citation> citations) {
        this(citationCount, invalidCitationCount, citationCoverage, grounded, ungrounded, citations,
                List.of(), List.of());
    }

    /** 无引用关键结论句的数量（= {@link #unsupportedSentences()} 的长度）。 */
    public int unsupportedSentenceCount() {
        return unsupportedSentences.size();
    }

    /** 单条引用：编号 + 是否能在证据台账里找到。 */
    public record Citation(String key, boolean valid) {
    }
}
