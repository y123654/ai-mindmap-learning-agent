package com.example.aimindmap.agentruntime;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * P1-E3b（方案 §P1-7 第 612 行）：回答侧“系统提示词泄露”检查。
 *
 * <p>方案原文要求“回答不得包含系统提示词原文；{@code CitationVerifier} 增加关键词检查”。
 * 本轮<b>没有</b>改 {@link CitationVerifier} 的返回值（{@code grounded} / {@code citationCoverage}
 * 的既有语义一个字都不动，避免污染 P0 的质量指标口径），而是单独做一个纯函数检查器，
 * 由 {@code KnowledgeAssistantStreamer} 在流结束后调用。
 *
 * <h2>两级处置（所有者 2026-10-28 裁定，避免误伤正常提问）</h2>
 * <ul>
 *   <li><b>专属片段</b>（{@link #EXCLUSIVE_RULES}）：只可能来自系统提示词原文的句子
 *       （如“你是…知识助手。”“资料检索与工具使用规则”）。命中 → 记 warn <b>并</b>在回答末尾追加
 *       固定提示（不撤回已输出内容）。</li>
 *   <li><b>通用关键词</b>（{@link #KEYWORD_RULES}）：{@code system prompt} / 系统提示词。
 *       用户问“什么是 system prompt”时回答里<b>必然</b>出现这些词，所以它们<b>只记 warn + 计数</b>、
 *       <b>不追加用户可见提示</b>。</li>
 * </ul>
 *
 * <p><b>回声排除</b>：专属片段如果<b>用户提问原文里就出现了</b>（例如用户自己打了“知识助手”这几个字），
 * 回答里再出现不算泄露，不追加提示。只对专属片段做回声排除——通用关键词本来就只告警，
 * 保留 warn 便于 P1-F 统计。
 *
 * <p><b>能证明什么、不能证明什么</b>：
 * <ul>
 *   <li>能证明：如果模型真的把系统提示词吐到答案里，这套规则能把这次回答<b>识别出来并留痕</b>；</li>
 *   <li><b>不能</b>证明：模型不会泄露、不会执行注入。那必须真实调用模型（留 P1-F 红队用例）。</li>
 * </ul>
 */
public final class AnswerLeakChecker {

    /**
     * 命中后追加到回答末尾的固定提示（唯一来源，供“防重复追加”与单测断言共用）。
     */
    public static final String LEAK_NOTICE =
            "\n\n[安全提示：本次回答中出现了疑似系统提示词片段，已记录；助手不会据此透露内部实现。]";

    /**
     * 一条检查规则。
     *
     * @param name           稳定规则名（进日志，改名等于改日志口径）
     * @param pattern        正则（大小写不敏感）
     * @param userEchoNeedle 用户提问里出现这个短语时本规则不触发（回声排除）；仅专属片段使用
     */
    public record Rule(String name, Pattern pattern, String userEchoNeedle) {
    }

    /**
     * 检查结果。
     *
     * @param leaked           是否命中任一规则（含“只告警”的通用关键词）
     * @param ruleNames        命中的规则名（专属在前、通用在后，保持规则定义顺序）
     * @param noticeRuleNames  命中的<b>专属片段</b>规则（只有非空才追加用户可见提示）
     * @param keywordRuleNames 命中的<b>通用关键词</b>规则（只告警 + 计数）
     */
    public record Result(boolean leaked,
                         List<String> ruleNames,
                         List<String> noticeRuleNames,
                         List<String> keywordRuleNames) {

        public Result {
            ruleNames = ruleNames == null ? List.of() : List.copyOf(ruleNames);
            noticeRuleNames = noticeRuleNames == null ? List.of() : List.copyOf(noticeRuleNames);
            keywordRuleNames = keywordRuleNames == null ? List.of() : List.copyOf(keywordRuleNames);
        }

        private static final Result NONE = new Result(false, List.of(), List.of(), List.of());

        /** 未命中的常量结果。 */
        public static Result none() {
            return NONE;
        }

        /** 是否需要给用户追加那条固定提示（只有专属片段命中才需要）。 */
        public boolean needsUserVisibleNotice() {
            return !noticeRuleNames.isEmpty();
        }
    }

    /**
     * 专属片段：{@code KnowledgeAssistantPromptService} 里固定出现的句子。
     *
     * <p>正常回答几乎不可能一字不差地出现这些串；出现就说明模型在复述系统提示词。
     */
    private static final List<Rule> EXCLUSIVE_RULES = List.of(
            rule("prompt-role-line", "你是[^\\n]{0,24}知识助手。", "知识助手"),
            rule("prompt-scope-line", "知识范围：本科", "知识范围"),
            rule("prompt-answer-rules", "回答规则：", "回答规则"),
            rule("prompt-tool-section", "资料检索与工具使用规则", "资料检索与工具使用规则"),
            rule("prompt-closing-line", "后续所有对话严格遵守以上规则", "后续所有对话严格遵守以上规则"));

    /**
     * 通用关键词：只告警 + 计数，<b>不</b>追加用户可见提示。
     *
     * <p>原因：用户主动问“什么是 system prompt / 系统提示词”时，回答里必然出现这些词，
     * 如果据此弹提示，就成了纯噪音（所有者 2026-10-28 裁定）。
     */
    private static final List<Rule> KEYWORD_RULES = List.of(
            rule("system-prompt-en", "system\\s*prompt", null),
            rule("system-prompt-cn", "系统提示词", null));

    private AnswerLeakChecker() {
    }

    /** 全部规则名（专属在前、通用在后）。 */
    public static List<String> ruleNames() {
        List<String> names = new ArrayList<>(EXCLUSIVE_RULES.size() + KEYWORD_RULES.size());
        for (Rule rule : EXCLUSIVE_RULES) {
            names.add(rule.name());
        }
        for (Rule rule : KEYWORD_RULES) {
            names.add(rule.name());
        }
        return List.copyOf(names);
    }

    /** 只检查回答（没有用户提问可对比，等价于不做回声排除）。 */
    public static Result check(String answer) {
        return check(answer, null);
    }

    /**
     * 检查一条完整回答。
     *
     * @param answer      模型完整回答
     * @param userMessage 本轮用户提问原文（用于回声排除；{@code null}/空白则不做排除）
     */
    public static Result check(String answer, String userMessage) {
        if (answer == null || answer.isBlank()) {
            return Result.none();
        }
        String userEcho = normalize(userMessage);
        List<String> noticeRules = new ArrayList<>(2);
        List<String> keywordRules = new ArrayList<>(2);
        for (Rule rule : EXCLUSIVE_RULES) {
            if (rule.pattern().matcher(answer).find() && !echoedByUser(rule, userEcho)) {
                noticeRules.add(rule.name());
            }
        }
        for (Rule rule : KEYWORD_RULES) {
            if (rule.pattern().matcher(answer).find()) {
                keywordRules.add(rule.name());
            }
        }
        List<String> all = new ArrayList<>(noticeRules.size() + keywordRules.size());
        all.addAll(noticeRules);
        all.addAll(keywordRules);
        return all.isEmpty() ? Result.none() : new Result(true, all, noticeRules, keywordRules);
    }

    /**
     * 追加固定提示（不撤回已输出内容）；已含提示时原样返回，避免重复追加。
     */
    public static String appendNotice(String answer) {
        String text = answer == null ? "" : answer;
        if (text.contains(LEAK_NOTICE.strip())) {
            return text;
        }
        return text + LEAK_NOTICE;
    }

    /**
     * 回声排除：用户提问原文里已经出现过这条规则的标志短语 → 回答里出现不算泄露。
     *
     * <p>只用于专属片段（通用关键词不做排除，见类注释）。
     */
    private static boolean echoedByUser(Rule rule, String normalizedUserMessage) {
        if (normalizedUserMessage.isEmpty() || rule.userEchoNeedle() == null) {
            return false;
        }
        return normalizedUserMessage.contains(normalize(rule.userEchoNeedle()));
    }

    /** 归一化：小写 + 空白折叠成单个空格（{@code SYSTEM   PROMPT} 与 {@code system prompt} 等价）。 */
    private static String normalize(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        return text.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static Rule rule(String name, String regex, String userEchoNeedle) {
        return new Rule(name,
                Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE),
                userEchoNeedle);
    }
}
