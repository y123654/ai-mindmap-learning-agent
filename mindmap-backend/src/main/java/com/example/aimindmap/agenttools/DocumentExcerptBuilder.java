package com.example.aimindmap.agenttools;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code read_my_document} 传了 query 时的“大纲 + 相关段落”摘录器（方案 §P1-5 第 6 条）。
 *
 * <p>为什么是纯规则：这一条的目标是“长文档别再整篇塞进上下文”，不需要语义质量——
 * 用标题大纲保证结构不丢，用<b>关键词重合度</b>挑段落保证和问题相关，
 * 这样既不额外调 embedding/LLM（零成本、零超时风险），也能在单测里逐字断言。
 *
 * <p>输出结构（总长度严格 ≤ {@code maxChars}）：
 * <pre>
 * （已按问题筛选：下面只有标题大纲与相关段落）
 * 【大纲】
 * - 进程管理
 *   - 调度
 * 【相关段落】
 * 〔调度〕
 * 时间片轮转……
 * </pre>
 *
 * <p>没有 Markdown 标题时大纲一栏显式写“没有标题”，段落仍按关键词挑；
 * 关键词一个都没命中时按文档顺序摘录前几段（至少不是空回复）。
 * 不做改写、不落证据、不碰数据库——调用方（{@link LearningResourceTools}）负责证据登记。
 */
final class DocumentExcerptBuilder {

    /** 方案原文的硬上限：1500 字。 */
    static final int DEFAULT_MAX_CHARS = 1500;

    /** 大纲最多占用的字符数（避免目录比正文还长）。 */
    static final int OUTLINE_MAX_CHARS = 600;

    private static final Pattern HEADING_PATTERN = Pattern.compile("^(#{1,6})\\s+(.*)$");

    private static final Pattern CJK_RUN_PATTERN = Pattern.compile("[\\u4e00-\\u9fff]+");

    private static final Pattern ASCII_WORD_PATTERN = Pattern.compile("[A-Za-z][A-Za-z0-9_+#.-]{1,}");

    /** 从 query 里抠检索词时要先丢掉的虚词/疑问词（只影响打分，不影响正文）。 */
    private static final Pattern QUERY_STOPWORDS_PATTERN = Pattern.compile(
            "什么|为什么|为何|如何|怎么|哪些|有哪些|请问|帮我|解释|介绍|说明|一下|我们|你们|他们"
                    + "|以及|还有|或者|这个|那个|这些|那些|主要|通常|一般|可以|能够|需要|应该"
                    + "|如果|那么|的|了|吗|呢|吧|啊|呀|是|和|与|及|或|在|有|对|从|到|这|那|些"
                    + "|该|其|被|把|为|于|上|中|下|里|个|请");

    private DocumentExcerptBuilder() {
    }

    static String build(String content, String query, int maxChars) {
        if (content == null || content.isBlank()) {
            return "";
        }
        int budget = Math.max(200, maxChars);
        List<Section> sections = splitSections(content);
        Set<String> terms = queryTerms(query);

        String notice = "（已按问题筛选：下面只有标题大纲与相关段落）\n";
        String outlineHeader = "【大纲】\n";
        String bodyHeader = "【相关段落】\n";
        int fixedChars = notice.length() + outlineHeader.length() + bodyHeader.length();
        int outlineBudget = Math.max(80, Math.min(OUTLINE_MAX_CHARS, (budget - fixedChars) / 2));
        String outline = buildOutline(sections, outlineBudget);
        if (outline.isBlank()) {
            outline = "（该笔记没有 Markdown 标题）\n";
        }

        List<Section> picked = rankSections(sections, terms);
        StringBuilder builder = new StringBuilder(budget);
        builder.append(notice).append(outlineHeader).append(outline).append(bodyHeader);
        if (picked.isEmpty()) {
            builder.append("（没有可摘录的正文段落，请不传 query 读取全文）\n");
        }
        int remaining = budget - builder.length();
        for (Section section : picked) {
            String block = formatBlock(section);
            if (block.isBlank()) {
                continue;
            }
            if (block.length() + 1 > remaining) {
                if (remaining > 1) {
                    builder.append(block, 0, Math.min(block.length(), remaining - 1)).append('…');
                }
                break;
            }
            builder.append(block).append('\n');
            remaining = budget - builder.length();
            if (remaining <= 1) {
                break;
            }
        }
        if (builder.length() > budget) {
            builder.setLength(budget - 1);
            builder.append('…');
        }
        return builder.toString();
    }

    /** 按 Markdown 标题切小节：标题行不进正文，正文按原顺序累积。 */
    static List<Section> splitSections(String content) {
        List<Section> sections = new ArrayList<>();
        String currentTitle = null;
        int currentLevel = 0;
        StringBuilder body = new StringBuilder();
        for (String rawLine : content.split("\r?\n", -1)) {
            Matcher heading = HEADING_PATTERN.matcher(rawLine.strip());
            if (heading.matches()) {
                flush(sections, currentTitle, currentLevel, body);
                currentLevel = heading.group(1).length();
                currentTitle = heading.group(2).strip();
                continue;
            }
            if (body.length() > 0) {
                body.append('\n');
            }
            body.append(rawLine);
        }
        flush(sections, currentTitle, currentLevel, body);
        return sections;
    }

    private static void flush(List<Section> sections, String title, int level, StringBuilder body) {
        String text = body.toString().strip();
        body.setLength(0);
        if (title == null && text.isEmpty()) {
            return;
        }
        sections.add(new Section(level, title, text));
    }

    /** 各级标题 → 缩进大纲；超过 maxChars 时截断并明确提示。 */
    private static String buildOutline(List<Section> sections, int maxChars) {
        StringBuilder outline = new StringBuilder();
        boolean truncated = false;
        for (Section section : sections) {
            if (section.title() == null || section.title().isBlank()) {
                continue;
            }
            int indentLevel = Math.max(0, Math.min(5, section.level() - 1));
            String line = "  ".repeat(indentLevel) + "- " + section.title() + "\n";
            if (outline.length() + line.length() > maxChars) {
                truncated = true;
                break;
            }
            outline.append(line);
        }
        if (truncated) {
            outline.append("…（大纲过长已截断）\n");
        }
        return outline.toString();
    }

    /** 按“包含多少个 query 检索词”排序；一个都没命中时退回文档顺序。 */
    private static List<Section> rankSections(List<Section> sections, Set<String> terms) {
        List<ScoredSection> scored = new ArrayList<>();
        for (int index = 0; index < sections.size(); index++) {
            Section section = sections.get(index);
            if (section.body().isBlank()) {
                continue;
            }
            int sectionScore = score(section, terms);
            if (sectionScore > 0) {
                scored.add(new ScoredSection(section, sectionScore, index));
            }
        }
        if (scored.isEmpty()) {
            List<Section> inDocumentOrder = new ArrayList<>();
            for (Section section : sections) {
                if (!section.body().isBlank()) {
                    inDocumentOrder.add(section);
                }
            }
            return inDocumentOrder;
        }
        scored.sort(Comparator.comparingInt(ScoredSection::score).reversed()
                .thenComparingInt(ScoredSection::index));
        List<Section> ranked = new ArrayList<>(scored.size());
        for (ScoredSection candidate : scored) {
            ranked.add(candidate.section());
        }
        return ranked;
    }

    /** 打分：命中一个检索词 +1；命中标题再 +1（标题命中通常比正文命中更相关）。 */
    private static int score(Section section, Set<String> terms) {
        if (terms.isEmpty()) {
            return 0;
        }
        String title = section.title() == null ? "" : section.title();
        String text = title + "\n" + section.body();
        int score = 0;
        for (String term : terms) {
            if (text.contains(term)) {
                score += title.contains(term) ? 2 : 1;
            }
        }
        return score;
    }

    private static String formatBlock(Section section) {
        String body = section.body();
        if (body.isBlank()) {
            return "";
        }
        if (section.title() == null || section.title().isBlank()) {
            return body;
        }
        return "〔" + section.title() + "〕\n" + body;
    }

    /**
     * 从 query 里抠检索词：英文按单词，中文按二元组（bigram）。
     *
     * <p>中文没有空格，用 bigram 是最省事的“近似分词”：问题里的“时间片轮转”会产生
     * 时间/间片/片轮/轮转，只要正文出现同样的词就会命中，不需要维护词典。
     */
    static Set<String> queryTerms(String query) {
        Set<String> terms = new LinkedHashSet<>();
        if (query == null || query.isBlank()) {
            return terms;
        }
        String normalized = QUERY_STOPWORDS_PATTERN.matcher(query.toLowerCase(Locale.ROOT))
                .replaceAll(" ");
        Matcher ascii = ASCII_WORD_PATTERN.matcher(normalized);
        while (ascii.find()) {
            terms.add(ascii.group());
        }
        Matcher cjk = CJK_RUN_PATTERN.matcher(normalized);
        while (cjk.find()) {
            String run = cjk.group();
            if (run.length() <= 2) {
                terms.add(run);
                continue;
            }
            for (int start = 0; start + 2 <= run.length(); start++) {
                terms.add(run.substring(start, start + 2));
            }
        }
        return terms;
    }

    /** 一个小节：{@code level=0} 且 {@code title=null} 表示“第一个标题之前的正文”。 */
    record Section(int level, String title, String body) {
    }

    private record ScoredSection(Section section, int score, int index) {
    }
}
