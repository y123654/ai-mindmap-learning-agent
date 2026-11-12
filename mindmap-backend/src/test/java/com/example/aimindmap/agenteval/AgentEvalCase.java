package com.example.aimindmap.agenteval;

import java.util.List;

/**
 * 一条 Agent 评测用例（方案 §4 P0-4 的用例 schema，只做加法）。
 *
 * <p>字段与原方案一致：id / intent / question / expect / gold_chunks / tags；
 * 另加两个 P0-A1 增量字段：
 * <ul>
 *   <li>{@code fixture_note}：这条用例依赖哪份 Seeder 固定夹具，方便失败时定位；</li>
 *   <li>{@code expect.refusal_keywords} / {@code expect.forbidden_contains}：
 *       「拒答识别」与「注入泄漏检测」用的关键词表（原 schema 没有）。</li>
 * </ul>
 *
 * <p><b>tools_any 的语义是「至少调用其中一个」</b>（方案原文注释），不是「必须全部调用」。
 * 指标口径见 {@link EvalMetrics}。
 */
public record AgentEvalCase(
        String id,
        String intent,
        String question,
        Expect expect,
        List<String> goldChunks,
        List<String> tags,
        String fixtureNote) {

    public AgentEvalCase {
        goldChunks = goldChunks == null ? List.of() : List.copyOf(goldChunks);
        tags = tags == null ? List.of() : List.copyOf(tags);
        fixtureNote = fixtureNote == null ? "" : fixtureNote;
    }

    /** 该用例期望调用到的工具集合（命中任意一个即算召回成功）。 */
    public List<String> expectedTools() {
        return expect.toolsAny();
    }

    public boolean isChitchat() {
        return "CHITCHAT".equals(intent);
    }

    public boolean hasTag(String tag) {
        return tags.contains(tag);
    }

    public record Expect(
            List<String> toolsAny,
            boolean mustCite,
            boolean refusal,
            List<String> keyPoints,
            List<String> refusalKeywords,
            List<String> forbiddenContains) {

        public Expect {
            toolsAny = toolsAny == null ? List.of() : List.copyOf(toolsAny);
            keyPoints = keyPoints == null ? List.of() : List.copyOf(keyPoints);
            refusalKeywords = refusalKeywords == null ? List.of() : List.copyOf(refusalKeywords);
            forbiddenContains = forbiddenContains == null ? List.of() : List.copyOf(forbiddenContains);
        }
    }
}
