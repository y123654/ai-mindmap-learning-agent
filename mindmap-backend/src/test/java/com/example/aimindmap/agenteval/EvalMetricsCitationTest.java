package com.example.aimindmap.agenteval;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * P0-C2 引用指标汇总的纯单测：<b>不调模型、不连数据库</b>，只喂构造好的
 * {@link AgentEvalRunner.CaseResult} 验证「怎么汇总」。
 *
 * <p>为什么单独锁死这些断言：引用数字是本阶段给组长的验收依据，
 * 分母口径（全部用例 vs 只统计有证据的用例）一旦写错，报告会悄悄失真：
 * 把「闲聊/该拒答的用例」算进覆盖率，覆盖率会被拉低；把「没有证据」当 0 写进对比表，
 * 又违反了「旧报告写 n/a、不得当 0」的既有约定。这里用一个 3 条数据的小样本把口径钉住：
 * <ol>
 *   <li>有证据 + 有引用（coverage 0.75，grounded）；</li>
 *   <li>有证据 + 只有非法编号（证据存在但模型没引用，coverage 0，不 grounded）；</li>
 *   <li>没有证据（闲聊，覆盖率与 grounded 都不进分母）。</li>
 * </ol>
 */
class EvalMetricsCitationTest {

    @Test
    @DisplayName("引用指标汇总：平均值按全部用例、覆盖率/grounded 率只按有证据的用例")
    void aggregatesCitationMetricsWithEvidenceOnlyDenominator() {
        AgentEvalRunner.CaseResult cited = result("cite-001",
                "结论：FCFS 按到达顺序分配 CPU [E1]，缺点是平均等待时间长 [E2]。\n\n"
                        + "补充：时间片过小会频繁切换 [E3]。",
                3, 2, 0, 0, 0.75d, true);
        AgentEvalRunner.CaseResult evidenceButNoCitation = result("cite-002",
                "这个问题你的笔记里没有讲 [E9]，我也不确定 [E9]。",
                2, 0, 1, 2, 0.0d, false);
        AgentEvalRunner.CaseResult noEvidence = result("cite-003",
                "你好，我是学习助手，可以帮你查笔记、导图和题目。",
                0, 0, 0, 0, 0.0d, false);

        EvalMetrics.CitationStats stats = EvalMetrics.compute(
                List.of(), List.of(cited, evidenceButNoCitation, noEvidence)).citationStats();

        // 平均证据数 / 平均有效引用：分母是全部 3 条用例（闲聊没有证据，算 0 是事实）
        assertThat(stats.avgEvidenceCount()).isEqualTo(round4(5 / 3.0d));
        assertThat(stats.avgValidCitations()).isEqualTo(round4(2 / 3.0d));

        // 非法编号：去重 1 个（E9），出现 2 次
        assertThat(stats.invalidCitationsTotal()).isEqualTo(1);
        assertThat(stats.invalidCitationOccurrencesTotal()).isEqualTo(2);

        // 覆盖率 / grounded 率：只统计有证据的 2 条，闲聊不进分母
        assertThat(stats.casesWithEvidence()).isEqualTo(2);
        assertThat(stats.avgCitationCoverage()).isEqualTo(round4((0.75d + 0.0d) / 2));
        assertThat(stats.groundedCases()).isEqualTo(1);
        assertThat(stats.groundedRate()).isEqualTo(0.5d);
        assertThat(stats.coverageText()).isEqualTo("0.3750");
        assertThat(stats.groundedText()).isEqualTo("0.5000");

        // 「模型未引用但证据存在」就是第 2 条这种：证据有、合法引用 0
        assertThat(stats.casesWithEvidenceButNoCitation()).isEqualTo(1);
        // [E 出现总次数：3 + 2 + 0
        assertThat(stats.citationMarkersTotal()).isEqualTo(5);
    }

    @Test
    @DisplayName("没有任何有证据的用例时：覆盖率与 grounded 率是 n/a（不用 0 冒充）")
    void citationCoverageIsNotApplicableWithoutEvidence() {
        EvalMetrics.CitationStats stats = EvalMetrics.compute(
                List.of(), List.of(result("cite-010", "你好，我是学习助手。", 0, 0, 0, 0, 0.0d, false)))
                .citationStats();

        assertThat(stats.casesWithEvidence()).isZero();
        assertThat(stats.avgCitationCoverage()).isNull();
        assertThat(stats.groundedRate()).isNull();
        assertThat(stats.coverageText()).isEqualTo("n/a");
        assertThat(stats.groundedText()).isEqualTo("n/a");
        // 平均值仍有定义（全部用例都没证据 ⇒ 0），但不能当成覆盖率结论
        assertThat(stats.avgEvidenceCount()).isZero();
        assertThat(stats.avgValidCitations()).isZero();
        assertThat(stats.casesWithEvidenceButNoCitation()).isZero();
    }

    @Test
    @DisplayName("[E 编号计数与生产 CitationVerifier 同一正则：[E1]/[E12] 计数，[E]/[e1]/E1 不算")
    void countsCitationMarkersLikeProductionVerifier() {
        assertThat(EvalMetrics.countCitationMarkers(null)).isZero();
        assertThat(EvalMetrics.countCitationMarkers("")).isZero();
        assertThat(EvalMetrics.countCitationMarkers("没有任何引用")).isZero();
        assertThat(EvalMetrics.countCitationMarkers("[E1] [E1] [E12]")).isEqualTo(3);
        assertThat(EvalMetrics.countCitationMarkers("[E] [e1] E1 [E:] [E 1]")).isZero();
    }

    /** 构造一条 CaseResult：只关心引用相关字段，其余给稳定占位值。 */
    private static AgentEvalRunner.CaseResult result(String caseId, String answer,
                                                     int evidenceCount, int validCitations,
                                                     int invalidCitations, int invalidOccurrences,
                                                     double coverage, boolean grounded) {
        return new AgentEvalRunner.CaseResult(
                caseId,
                "RETRIEVE",
                "构造数据用的问题",
                "PASS",
                List.of(),
                List.of("search_my_documents"),
                List.of(),
                1,
                0,
                false,
                answer,
                null,
                null,
                1000L,
                null,
                null,
                null,
                "SUCCEEDED",
                1L,
                1L,
                false,
                List.of(),
                List.of(),
                List.of(),
                evidenceCount,
                validCitations,
                invalidCitations,
                invalidOccurrences,
                coverage,
                grounded);
    }

    private static double round4(double value) {
        return Math.round(value * 10000.0d) / 10000.0d;
    }
}
