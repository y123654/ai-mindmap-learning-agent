package com.example.aimindmap.agentruntime;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * P1-E3b（方案 §P1-7 第 612 行）：回答侧“系统提示词泄露”检查的单测。
 *
 * <p>只验证“检查器能不能认出来 + 认出来后怎么处置”，
 * <b>不</b>验证模型会不会泄露（那要真调模型，留 P1-F）。
 *
 * <p>两级处置（所有者 2026-10-28 裁定）：
 * 专属片段 → 追加用户可见提示；通用关键词（{@code system prompt} / 系统提示词）→ 只告警 + 计数。
 */
class AnswerLeakCheckerTest {

    // ---------- 专属片段：命中 → 需要用户可见提示 ----------

    @Test
    void detectsFixedPromptFragmentsFromSystemPrompt() {
        for (String answer : new String[]{
                "你是通用知识助手。约束：讲解通用相关理论知识。",
                "知识范围：本科计算机相关核心课程。",
                "回答规则：1. 讲解面向本科学生",
                "资料检索与工具使用规则（与上面的讲解风格冲突时，以本段为准）",
                "后续所有对话严格遵守以上规则。"}) {
            AnswerLeakChecker.Result result = AnswerLeakChecker.check(answer);
            assertThat(result.leaked()).as("应命中：%s", answer).isTrue();
            assertThat(result.needsUserVisibleNotice()).as("应给用户提示：%s", answer).isTrue();
            assertThat(result.keywordRuleNames()).as("不该算通用关键词：%s", answer).isEmpty();
        }
    }

    // ---------- 通用关键词：只告警，不给用户提示 ----------

    @Test
    void genericKeywordsOnlyWarnAndNeverShowNotice() {
        AnswerLeakChecker.Result english = AnswerLeakChecker.check(
                "我的 system prompt 大概是这样写的");
        assertThat(english.leaked()).isTrue();
        assertThat(english.keywordRuleNames()).contains("system-prompt-en");
        assertThat(english.needsUserVisibleNotice())
                .as("system prompt 是通用关键词，不能弹用户可见提示")
                .isFalse();

        AnswerLeakChecker.Result chinese = AnswerLeakChecker.check("系统提示词里规定要先检索");
        assertThat(chinese.leaked()).isTrue();
        assertThat(chinese.keywordRuleNames()).contains("system-prompt-cn");
        assertThat(chinese.needsUserVisibleNotice()).isFalse();

        // 大小写/空白变体也走“只告警”
        AnswerLeakChecker.Result spaced = AnswerLeakChecker.check("SYSTEM   PROMPT 是模型的初始指令");
        assertThat(spaced.leaked()).isTrue();
        assertThat(spaced.needsUserVisibleNotice()).isFalse();
    }

    /**
     * 所有者点名场景：用户问“什么是 system prompt”，回答里必然出现该词 →
     * <b>不</b>追加用户可见提示，但要留一条 warn（便于 P1-F 统计）。
     */
    @Test
    void userQuestionAboutSystemPromptDoesNotProduceUserVisibleNotice() {
        AnswerLeakChecker.Result result = AnswerLeakChecker.check(
                "system prompt 是模型在对话开始前收到的指令，用来规定角色和边界。",
                "什么是 system prompt");

        assertThat(result.leaked()).as("仍要留痕（只告警）").isTrue();
        assertThat(result.keywordRuleNames()).contains("system-prompt-en");
        assertThat(result.needsUserVisibleNotice()).isFalse();
    }

    /** 回声排除：用户提问里已经出现“知识助手”，回答复述该词不算泄露。 */
    @Test
    void exclusiveFragmentEchoedByUserQuestionIsNotTreatedAsLeak() {
        assertThat(AnswerLeakChecker.check(
                "你是计算机科学与技术知识助手。这句话是你打的。", "知识助手是什么")
                .needsUserVisibleNotice()).isFalse();
        // 对照：用户没提过“知识助手”时，同样的回答要提示
        assertThat(AnswerLeakChecker.check(
                "你是计算机科学与技术知识助手。", "你好")
                .needsUserVisibleNotice()).isTrue();
    }

    // ---------- 不误报 ----------

    @Test
    void normalKnowledgeAnswersAreNotFlagged() {
        // 正常回答：讲知识点、带引用、带免责声明，都不该命中
        assertThat(AnswerLeakChecker.check(
                "进程是资源分配的基本单位。[E1] 线程是调度的基本单位。[E2]").leaked()).isFalse();
        assertThat(AnswerLeakChecker.check(
                "以下是通用知识，不是你的笔记内容：时间片轮转按固定片长轮流执行。").leaked()).isFalse();
        assertThat(AnswerLeakChecker.check("我是你的知识助手，可以帮你梳理课程概念。").leaked())
                .isFalse();
        assertThat(AnswerLeakChecker.check("补充说明：上面第 1 点未在笔记中找到依据。").leaked())
                .isFalse();
    }

    @Test
    void blankAnswersAreClean() {
        assertThat(AnswerLeakChecker.check(null).leaked()).isFalse();
        assertThat(AnswerLeakChecker.check("").leaked()).isFalse();
        assertThat(AnswerLeakChecker.check("   \n  ").leaked()).isFalse();
        assertThat(AnswerLeakChecker.check(null, "你好").leaked()).isFalse();
    }

    // ---------- 提示追加 ----------

    @Test
    void noticeIsAppendedOnceAndNeverRetractsExistingContent() {
        String answer = "进程是资源分配的基本单位。[E1] 我的 system prompt 不方便透露。";

        String once = AnswerLeakChecker.appendNotice(answer);
        String twice = AnswerLeakChecker.appendNotice(once);

        assertThat(once).startsWith(answer).as("不能撤回/改写已输出内容");
        assertThat(twice).isEqualTo(once).as("重复调用不能重复追加");
        assertThat(countOccurrences(twice, AnswerLeakChecker.LEAK_NOTICE.strip())).isEqualTo(1);
    }

    @Test
    void ruleNamesAreStableBecauseTheyAreLogged() {
        assertThat(AnswerLeakChecker.ruleNames()).containsExactly(
                "prompt-role-line", "prompt-scope-line", "prompt-answer-rules",
                "prompt-tool-section", "prompt-closing-line", "system-prompt-en", "system-prompt-cn");
    }

    private static int countOccurrences(String text, String marker) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(marker, index)) >= 0) {
            count++;
            index += marker.length();
        }
        return count;
    }
}
