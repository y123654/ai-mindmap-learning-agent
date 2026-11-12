package com.example.aimindmap.agentruntime;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class CitationVerifierTest {

    private static final List<EvidenceItem> TWO_ITEMS = List.of(
            new EvidenceItem("E1", "document_chunk", "42", 0, 0.71d, "操作系统", "进程是资源分配的基本单位"),
            new EvidenceItem("E2", "document_chunk", "42", 1, 0.52d, "操作系统", "线程是调度基本单位"));

    @Test
    void countsValidAndInvalidCitations() {
        CitationCheckResult result = CitationVerifier.verify(
                "进程是资源分配的基本单位。[E1] 另外结论见 [E9]。", TWO_ITEMS, true);

        assertThat(result.citationCount()).isEqualTo(1);
        assertThat(result.invalidCitationCount()).isEqualTo(1);
        assertThat(result.grounded()).isTrue();
        assertThat(result.ungrounded()).isFalse();
        assertThat(result.citations()).containsExactly(
                new CitationCheckResult.Citation("E1", true),
                new CitationCheckResult.Citation("E9", false));
    }

    @Test
    void repeatedCitationIsCountedOncePerKey() {
        CitationCheckResult result = CitationVerifier.verify(
                "第一句。[E1]\n\n第二句也来自同一来源。[E1]", TWO_ITEMS, true);

        assertThat(result.citationCount()).isEqualTo(1);
        assertThat(result.citations()).hasSize(1);
        assertThat(result.citationCoverage()).isEqualTo(1.0d);
    }

    @Test
    void coverageCountsCitedFactParagraphs() {
        String answer = "时间片轮转按时间片轮流分配 CPU。[E1]\n\n"
                + "它适合交互式系统，响应更及时。\n\n"
                + "FCFS 实现简单但平均等待时间较长。[E2]\n\n"
                + "SJF 需要预估运行时间。";

        CitationCheckResult result = CitationVerifier.verify(answer, TWO_ITEMS, true);

        // 4 个事实段落，其中 2 个带合法引用
        assertThat(result.citationCoverage()).isEqualTo(0.5d);
        assertThat(result.citationCount()).isEqualTo(2);
    }

    @Test
    void headingsAreNotCountedAsFactParagraphs() {
        String answer = "## 进程调度\n\n时间片轮转按时间片分配 CPU。[E1]\n\n### 小结\n\n响应更及时。";

        CitationCheckResult result = CitationVerifier.verify(answer, TWO_ITEMS, true);

        // 标题不计入事实段落，只剩两段正文
        assertThat(result.citationCoverage()).isEqualTo(0.5d);
    }

    @Test
    void disclaimerParagraphsAreExcludedFromCoverage() {
        String answer = "时间片轮转按时间片分配 CPU。[E1]\n\n"
                + "> 注：本次回答未能关联到你的笔记内容，请谨慎参考。\n\n"
                + "以下是通用知识，不是你的笔记内容：操作系统的调度目标是公平。";

        CitationCheckResult result = CitationVerifier.verify(answer, TWO_ITEMS, true);

        // 两段免责声明被排除，只剩 1 个事实段落且带合法引用
        assertThat(result.citationCoverage()).isEqualTo(1.0d);
    }

    @Test
    void knowledgeAnswerWithoutCitationIsUngrounded() {
        CitationCheckResult result = CitationVerifier.verify(
                "进程是资源分配的基本单位，线程是调度基本单位。", TWO_ITEMS, true);

        assertThat(result.grounded()).isFalse();
        assertThat(result.ungrounded()).isTrue();
        assertThat(result.citationCount()).isZero();
        assertThat(result.citationCoverage()).isZero();
        assertThat(result.citations()).isEmpty();
    }

    @Test
    void chitchatWithoutCitationIsNotUngrounded() {
        CitationCheckResult result = CitationVerifier.verify("你好，我是学习助手。", List.of(), false);

        assertThat(result.ungrounded()).isFalse();
        assertThat(result.grounded()).isFalse();
    }

    @Test
    void citationToUnknownKeyIsNotGrounded() {
        CitationCheckResult result = CitationVerifier.verify(
                "这句话引用了不存在的编号。[E7]", TWO_ITEMS, true);

        assertThat(result.grounded()).isFalse();
        assertThat(result.ungrounded()).isTrue();
        assertThat(result.invalidCitationCount()).isEqualTo(1);
        assertThat(result.citations()).containsExactly(new CitationCheckResult.Citation("E7", false));
    }

    @Test
    void malformedCitationIsIgnored() {
        CitationCheckResult result = CitationVerifier.verify(
                "编号写法不对：[E] [Eabc] 【E1】", TWO_ITEMS, true);

        assertThat(result.citations()).isEmpty();
        assertThat(result.invalidCitationCount()).isZero();
        // 没有合法引用 → 知识类仍判为无依据
        assertThat(result.ungrounded()).isTrue();
    }

    @Test
    void emptyAnswerHasZeroCoverage() {
        CitationCheckResult result = CitationVerifier.verify("", TWO_ITEMS, true);

        assertThat(result.citationCoverage()).isZero();
        assertThat(result.ungrounded()).isTrue();
    }

    // ===== P1-D2：关键结论句自检（数字 / 定义 / 因果 三条规则 + 切句 + 排除规则） =====

    @Test
    void numberConclusionSentenceWithoutCitationIsUnsupported() {
        CitationCheckResult result = CitationVerifier.verify("调度有三种算法。", TWO_ITEMS, true);

        assertThat(result.unsupportedSentences()).containsExactly("调度有三种算法。");
        assertThat(result.unsupportedPoints()).containsExactly(1);
        assertThat(result.unsupportedSentenceCount()).isEqualTo(1);
        // 既有 4 个指标口径完全不变：没有引用 → not grounded、知识类 ungrounded
        assertThat(result.citationCount()).isZero();
        assertThat(result.grounded()).isFalse();
        assertThat(result.ungrounded()).isTrue();
    }

    @Test
    void arabicNumberConclusionSentenceWithoutCitationIsUnsupported() {
        CitationCheckResult result = CitationVerifier.verify(
                "常见调度算法有 3 类。", TWO_ITEMS, true);

        assertThat(result.unsupportedSentences()).containsExactly("常见调度算法有 3 类。");
        assertThat(result.unsupportedPoints()).containsExactly(1);
    }

    @Test
    void definitionConclusionSentenceWithoutCitationIsUnsupported() {
        CitationCheckResult result = CitationVerifier.verify(
                "进程是资源分配的基本单位。", TWO_ITEMS, true);

        assertThat(result.unsupportedSentences()).containsExactly("进程是资源分配的基本单位。");
        assertThat(result.unsupportedPoints()).containsExactly(1);
    }

    @Test
    void causalConclusionSentenceWithoutCitationIsUnsupported() {
        CitationCheckResult result = CitationVerifier.verify(
                "因为时间片过小，所以切换开销变大。", TWO_ITEMS, true);

        assertThat(result.unsupportedSentences()).containsExactly("因为时间片过小，所以切换开销变大。");
        assertThat(result.unsupportedPoints()).containsExactly(1);
    }

    @Test
    void plainSentenceWithoutAnyRuleIsNotJudged() {
        CitationCheckResult result = CitationVerifier.verify("你好，我是学习助手。", List.of(), false);

        // 「我是学习助手」含定义词「是」→ 命中；这里断言的是「没有关键成分的句子不判」
        CitationCheckResult plain = CitationVerifier.verify("你好，欢迎回来。", List.of(), false);
        assertThat(plain.unsupportedSentences()).isEmpty();
        assertThat(result.unsupportedSentences()).hasSize(1);
    }

    @Test
    void sentenceBoundariesHandleChineseAndEnglishTerminators() {
        // 引用紧贴句末：中文 。！？； 与英文 . ; 切开后每句都带合法引用 → 全部不算 unsupported
        String cited = "三种算法[E1]。时间片轮转是抢占式的[E2]！对吗[E1]？"
                + "FCFS is simple and fair [E2]. SJF needs 3 estimates [E1]; done [E2].";
        CitationCheckResult allCited = CitationVerifier.verify(cited, TWO_ITEMS, true);
        assertThat(allCited.unsupportedSentences()).isEmpty();

        // 小数/版本号 3.5 不被英文句号切开（切坏会把一句话拆成两句，编号跟着乱）
        CitationCheckResult decimal = CitationVerifier.verify(
                "平均等待时间是 3.5 秒，属于可接受范围。", TWO_ITEMS, true);
        assertThat(decimal.unsupportedSentences()).hasSize(1);
        assertThat(decimal.unsupportedSentences().get(0)).contains("3.5");
    }

    @Test
    void markdownListItemsAreNotMisjudgedOrMisnumbered() {
        String answer = "- 进程是资源分配的单位。[E1]\n"
                + "- 三种调度算法。\n"
                + "1. 响应更及时。";

        CitationCheckResult result = CitationVerifier.verify(answer, TWO_ITEMS, true);

        // 列表符号不算句子，也不会把「1.」的句点当成一句的结尾；
        // 只有第 2 句（三种调度算法）是无引用的关键结论句
        assertThat(result.unsupportedSentences()).containsExactly("三种调度算法。");
        assertThat(result.unsupportedPoints()).containsExactly(2);
    }

    @Test
    void codeBlockAndBlockquoteSentencesAreNotConclusionSentences() {
        String answer = "示例代码：\n\n"
                + "```java\nint count = 3;\n因此需要三份拷贝。\n```\n\n"
                + "> 提示：这里有三种可能，所以不要照搬。\n\n"
                + "真实结论没有引用。";

        CitationCheckResult result = CitationVerifier.verify(answer, TWO_ITEMS, true);

        // 代码块与 `>` 引用块整块跳过，句子编号不会把它们算进来
        assertThat(result.unsupportedSentences()).isEmpty();
        assertThat(result.unsupportedPoints()).isEmpty();
    }

    @Test
    void unsupportedSummaryIsTruncatedToSixtyChars() {
        String longSentence = "这里的结论包含三种情况" + "很".repeat(80) + "。";

        CitationCheckResult result = CitationVerifier.verify(longSentence, TWO_ITEMS, true);

        assertThat(result.unsupportedSentences()).hasSize(1);
        String summary = result.unsupportedSentences().get(0);
        assertThat(summary.length()).isLessThanOrEqualTo(60);
        assertThat(summary).endsWith("…");
    }

    @Test
    void invalidCitationSentenceIsUnsupportedWithoutChangingInvalidCount() {
        CitationCheckResult result = CitationVerifier.verify(
                "进程是资源分配的基本单位。[E9]", TWO_ITEMS, true);

        // 两个口径各自独立，不重复计：invalidCitationCount 记「非法编号出现次数」，
        // unsupportedSentenceCount 记「没有合法引用的关键结论句数」
        assertThat(result.invalidCitationCount()).isEqualTo(1);
        assertThat(result.citations()).containsExactly(new CitationCheckResult.Citation("E9", false));
        assertThat(result.unsupportedSentenceCount()).isEqualTo(1);
        assertThat(result.unsupportedPoints()).containsExactly(1);
        assertThat(result.grounded()).isFalse();
        assertThat(result.ungrounded()).isTrue();
    }

    @Test
    void appendingSupplementDoesNotChangeCitationCoverage() {
        String answer = "时间片轮转按时间片分配 CPU。[E1]\n\n三种算法各有优缺点。";
        String supplement = "\n\n> 补充说明：上面第 2 点未在笔记中找到依据。";

        CitationCheckResult before = CitationVerifier.verify(answer, TWO_ITEMS, true);
        CitationCheckResult after = CitationVerifier.verify(answer + supplement, TWO_ITEMS, true);

        // 补充说明段被免责关键字排除：加不加它，覆盖率分母与所有既有指标必须完全相等
        assertThat(after.citationCoverage()).isEqualTo(before.citationCoverage());
        assertThat(after.citationCount()).isEqualTo(before.citationCount());
        assertThat(after.invalidCitationCount()).isEqualTo(before.invalidCitationCount());
        assertThat(after.grounded()).isEqualTo(before.grounded());
        assertThat(after.ungrounded()).isEqualTo(before.ungrounded());
        assertThat(after.unsupportedPoints()).isEqualTo(before.unsupportedPoints());
        assertThat(before.citationCoverage()).isEqualTo(0.5d);
    }

    @Test
    void tooLongAnswerOnlyChecksFirstTwoHundredSentences() {
        StringBuilder answer = new StringBuilder();
        for (int i = 0; i < 250; i++) {
            answer.append("这是第").append(i + 1).append("种情况说明。");
        }

        CitationCheckResult result = CitationVerifier.verify(answer.toString(), TWO_ITEMS, true);

        // >200 句只查前 200 句（超限打 warn），编号连续、不抛异常
        assertThat(result.unsupportedSentenceCount()).isEqualTo(200);
        assertThat(result.unsupportedPoints()).hasSize(200);
        assertThat(result.unsupportedPoints().get(0)).isEqualTo(1);
        assertThat(result.unsupportedPoints().get(199)).isEqualTo(200);
    }
}
