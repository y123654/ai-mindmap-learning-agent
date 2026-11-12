package com.example.aimindmap.agentruntime;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 引用校验器（方案 §P0-3 + §P1-3）：从回答里提取 {@code [E数字]}，对照证据台账判断引用是否合法，
 * 并按「事实段落」计算引用覆盖率；P1-D2 起再检查「关键结论句」是否至少带一个合法引用。
 *
 * <p>刻意做成无状态静态工具而不是 Spring bean：流式回答结束后只跑一次，没有依赖，
 * 也不会把状态带到下一次请求。
 *
 * <p><b>P1-D2 新增口径</b>（不改 P0-3 的任何既有语义）：
 * <ul>
 *   <li>关键结论句 = 含<b>数字</b> / 含<b>定义词</b> / 含<b>因果连接词</b> 三者任一；</li>
 *   <li>该句没有任何<b>合法</b> {@code [E#]} 引用 → 记一条 unsupported（只存 60 字摘要）；</li>
 *   <li>代码块、引用块（{@code >}）、标题行、分隔线不参与判定，避免把提示语/代码当结论句；</li>
 *   <li>免责声明段与「补充说明」段既不进事实段落（覆盖率分母），也不参与逐句判定（防自指）。</li>
 * </ul>
 */
public final class CitationVerifier {

    private static final Logger log = LoggerFactory.getLogger(CitationVerifier.class);

    /** 方案原文的引用格式：{@code [E1]}、{@code [E12]}；只认半角方括号与半角数字。 */
    private static final Pattern CITATION_PATTERN = Pattern.compile("\\[E(\\d+)]");

    /**
     * P1-D2：「补充说明」段的标识。同时用于三处，必须保持唯一来源：
     * <ol>
     *   <li>流结束后追加时判断「是否已经追加过」（防重复追加）；</li>
     *   <li>作为免责段关键字排除，<b>保证它不进覆盖率分母</b>（加不加覆盖率完全相等）；</li>
     *   <li>单测断言文案。</li>
     * </ol>
     */
    public static final String SUPPLEMENT_MARKER = "补充说明：";

    /**
     * 免责声明段 / 补充说明段的判定关键字（方案 §P0-3 + §P1-3）：
     * 命中任一段落即不计入事实段落，也不参与「关键结论句」判定。
     */
    private static final List<String> DISCLAIMER_MARKERS =
            List.of("注：本次回答未能关联", "以下是通用知识", SUPPLEMENT_MARKER);

    /** 定义词的判定关键字（方案 §P1-3 原文：是/指/称为/定义/叫做）。 */
    private static final List<String> DEFINITION_WORDS =
            List.of("是", "指", "称为", "定义", "叫做");

    /** 因果连接词的判定关键字（方案 §P1-3 原文）。 */
    private static final List<String> CAUSAL_WORDS =
            List.of("因为", "所以", "因此", "由于", "导致", "从而", "也就是说");

    /** 单句摘要的最长字符数（含省略号），不存全文。 */
    static final int MAX_SENTENCE_SUMMARY_CHARS = 60;

    /** 超长回答只检查前 200 句（避免长回答把 CPU 拖抖）。 */
    static final int MAX_SENTENCES = 200;

    /** 围栏代码块（``` … ```，未闭合时一直吃到文本末尾）。 */
    private static final Pattern FENCED_CODE_BLOCK = Pattern.compile("(?s)```.*?(?:```|$)");

    /**
     * 切句：中文 {@code 。！？；} 与换行直接切；英文 {@code . ! ? ;} 只在后面跟空白/结尾时切
     * （避免把 {@code 3.5}、{@code v1.2} 这类小数/版本号切成两句）。
     */
    private static final Pattern SENTENCE_BOUNDARY =
            Pattern.compile("[。！？；\\n]|[.!?;](?=\\s|$)");

    /** Markdown 列表符号（{@code - } / {@code * } / {@code + } / {@code 1.} / {@code 1)}）。 */
    private static final Pattern LIST_MARKER = Pattern.compile("^(?:[-*+]|\\d+[.)])\\s+");

    /** 片段开头的引用编号（例如「结论。[E1] 下一句」切句后 [E1] 落在下一片段开头）。 */
    private static final Pattern LEADING_CITATIONS =
            Pattern.compile("^((?:\\[E\\d+][\\s。；，、]*)+)(.*)$", Pattern.DOTALL);

    /** 阿拉伯数字（含全角）。 */
    private static final Pattern ARABIC_NUMBER = Pattern.compile("[0-9０-９]");

    /**
     * 中文数词 + 量词，例如「三种」「两个」「数十条」。
     *
     * <p>两条刻意的限制（避免把普通句子误判成结论句）：
     * <ul>
     *   <li>用 {@code (?<!第)} 排除序数：「第一段回答」「第二点」不是数量；</li>
     *   <li>用 {@code (?!性)} 排除「一次性」「个性化」这类词。</li>
     * </ul>
     */
    private static final Pattern CHINESE_NUMBER_WITH_UNIT = Pattern.compile(
            "(?<!第)[零一二三四五六七八九十百千万两几数]+"
                    + "[个种条次类项倍层段篇页章节天年周月分秒名位份组轮](?!性)");

    private CitationVerifier() {
    }

    /**
     * 校验一条回答。
     *
     * @param answer          模型完整回答（含可能被追加的免责声明前的正文）
     * @param evidence        本次 run 的证据快照
     * @param knowledgeIntent 是否知识类（本 run 工具调用数 &gt; 0）；闲聊不判 ungrounded
     */
    public static CitationCheckResult verify(String answer,
                                             Collection<EvidenceItem> evidence,
                                             boolean knowledgeIntent) {
        String text = answer == null ? "" : answer;
        Set<String> knownKeys = evidence == null ? Set.of() : evidence.stream()
                .map(EvidenceItem::key)
                .filter(key -> key != null && !key.isBlank())
                .map(key -> key.toUpperCase(Locale.ROOT))
                .collect(Collectors.toSet());

        Map<String, Boolean> citations = new LinkedHashMap<>();
        int invalidCount = 0;
        Matcher matcher = CITATION_PATTERN.matcher(text);
        while (matcher.find()) {
            String key = "E" + matcher.group(1);
            boolean valid = knownKeys.contains(key);
            citations.merge(key, valid, (left, right) -> left || right);
            if (!valid) {
                invalidCount++;
            }
        }
        List<CitationCheckResult.Citation> citationList = citations.entrySet().stream()
                .map(entry -> new CitationCheckResult.Citation(entry.getKey(), entry.getValue()))
                .toList();
        int citationCount = (int) citationList.stream().filter(CitationCheckResult.Citation::valid).count();

        List<String> paragraphs = factualParagraphs(text);
        int citedParagraphs = 0;
        for (String paragraph : paragraphs) {
            if (containsValidCitation(paragraph, knownKeys)) {
                citedParagraphs++;
            }
        }
        double coverage = paragraphs.isEmpty() ? 0.0d : citedParagraphs / (double) paragraphs.size();
        boolean grounded = citationCount > 0;
        boolean ungrounded = knowledgeIntent && !grounded;

        // P1-D2：关键结论句逐句检查（只存摘要与「第几点」，不存全文）
        List<String> unsupportedSentences = new ArrayList<>();
        List<Integer> unsupportedPoints = new ArrayList<>();
        collectUnsupportedSentences(text, knownKeys, unsupportedSentences, unsupportedPoints);

        return new CitationCheckResult(citationCount, invalidCount, coverage, grounded, ungrounded,
                citationList, unsupportedSentences, unsupportedPoints);
    }

    /**
     * 收集「有关键结论句成分、但一个合法引用都没有」的句子。
     *
     * <p>编号口径：在<b>关键结论句</b>里从 1 开始递增（「上面第 2 点」指第 2 个关键结论句），
     * 与全句序号无关；遇到非法编号（如 {@code [E9]}）也算「没有合法引用」，
     * 但非法次数仍由 {@link CitationCheckResult#invalidCitationCount()} 单独统计，两边不重复计。
     */
    private static void collectUnsupportedSentences(String text,
                                                    Set<String> knownKeys,
                                                    List<String> summaries,
                                                    List<Integer> points) {
        List<String> sentences = conclusionSentenceCandidates(text);
        if (sentences.size() > MAX_SENTENCES) {
            log.warn("回答句子数 {} 超过上限 {}，只检查前 {} 句（其余跳过，避免 CPU 抖动）",
                    sentences.size(), MAX_SENTENCES, MAX_SENTENCES);
        }
        int limit = Math.min(sentences.size(), MAX_SENTENCES);
        int conclusionIndex = 0;
        for (int i = 0; i < limit; i++) {
            String sentence = sentences.get(i);
            if (!isKeyConclusionSentence(sentence)) {
                continue;
            }
            conclusionIndex++;
            if (containsValidCitation(sentence, knownKeys)) {
                continue;
            }
            summaries.add(summarize(sentence));
            points.add(conclusionIndex);
        }
    }

    /**
     * 拆出候选句子：先去掉围栏代码块，再逐行跳过引用块（{@code >}）、标题、分隔线与免责/补充说明段，
     * 最后剥掉 Markdown 列表符号并按中英文句读切句。
     *
     * <p>切句时保留句末标点（摘要里能看出原句）。引用编号的归属做一次归一：
     * 「结论。[E1] 下一句」按句号切完会把 {@code [E1]} 落到下一片段开头，这里把它并回<b>前一句</b>
     * （引用写在句末是常见写法）；整段只剩编号的片段（如 {@code [E9]}）也并回前一句。
     * 否则纯编号片段会被当成「带数字的结论句」，引用也会记到错的句子上。
     */
    static List<String> conclusionSentenceCandidates(String text) {
        List<String> sentences = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return sentences;
        }
        String withoutCode = FENCED_CODE_BLOCK.matcher(text).replaceAll("\n");
        for (String rawLine : withoutCode.split("\\R", -1)) {
            String line = rawLine.strip();
            if (line.isEmpty() || line.startsWith(">") || isStructuralLine(line)) {
                continue;
            }
            line = LIST_MARKER.matcher(line).replaceFirst("");
            for (String piece : splitSentences(line)) {
                String sentence = absorbLeadingCitations(piece, sentences);
                if (sentence == null || sentence.isEmpty() || isDisclaimer(sentence)) {
                    continue;
                }
                sentences.add(sentence);
            }
        }
        return sentences;
    }

    /**
     * 把片段开头的引用编号并回前一句，返回去掉这些编号后的剩余正文；
     * 片段本身只剩编号（或没有前一句可并）时返回 {@code null} / 原文。
     */
    private static String absorbLeadingCitations(String piece, List<String> sentences) {
        Matcher matcher = LEADING_CITATIONS.matcher(piece);
        if (!matcher.matches()) {
            return piece;
        }
        String citations = matcher.group(1).strip();
        String rest = matcher.group(2).strip();
        if (citations.isEmpty()) {
            return piece;
        }
        if (sentences.isEmpty()) {
            // 第一句就以编号开头（如补检第二段），没有前一句可并：保留原片段
            return piece;
        }
        int last = sentences.size() - 1;
        sentences.set(last, sentences.get(last) + citations);
        return rest;
    }

    /** 按中英文句读切句，保留句末标点。 */
    private static List<String> splitSentences(String line) {
        List<String> pieces = new ArrayList<>();
        Matcher matcher = SENTENCE_BOUNDARY.matcher(line);
        int start = 0;
        while (matcher.find()) {
            String piece = line.substring(start, matcher.end()).strip();
            if (!piece.isEmpty()) {
                pieces.add(piece);
            }
            start = matcher.end();
        }
        if (start < line.length()) {
            String tail = line.substring(start).strip();
            if (!tail.isEmpty()) {
                pieces.add(tail);
            }
        }
        return pieces;
    }

    /** 关键结论句判定：含数字 / 含定义词 / 含因果连接词，三条任一命中。 */
    static boolean isKeyConclusionSentence(String sentence) {
        if (sentence == null || sentence.isBlank()) {
            return false;
        }
        return hasNumber(sentence)
                || containsAny(sentence, DEFINITION_WORDS)
                || containsAny(sentence, CAUSAL_WORDS);
    }

    private static boolean hasNumber(String sentence) {
        return ARABIC_NUMBER.matcher(sentence).find()
                || CHINESE_NUMBER_WITH_UNIT.matcher(sentence).find();
    }

    private static boolean containsAny(String sentence, List<String> words) {
        for (String word : words) {
            if (sentence.contains(word)) {
                return true;
            }
        }
        return false;
    }

    /** 摘要：压平空白并截断到 60 字以内（超长时保留 59 字 + 省略号），不存全文。 */
    static String summarize(String sentence) {
        String flat = sentence.replaceAll("\\s+", " ").strip();
        if (flat.length() <= MAX_SENTENCE_SUMMARY_CHARS) {
            return flat;
        }
        return flat.substring(0, MAX_SENTENCE_SUMMARY_CHARS - 1) + "…";
    }

    private static boolean containsValidCitation(String paragraph, Set<String> knownKeys) {
        Matcher matcher = CITATION_PATTERN.matcher(paragraph);
        while (matcher.find()) {
            if (knownKeys.contains("E" + matcher.group(1))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 切出「事实段落」：按空行切段；Markdown 标题行、分隔线只当分隔符不计入；
     * 免责声明段/补充说明段整段排除（见 {@link #DISCLAIMER_MARKERS}）。
     */
    static List<String> factualParagraphs(String text) {
        List<String> paragraphs = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String rawLine : text.split("\\R", -1)) {
            String line = rawLine.strip();
            if (line.isEmpty() || isStructuralLine(line)) {
                flush(current, paragraphs);
                continue;
            }
            if (current.length() > 0) {
                current.append('\n');
            }
            current.append(line);
        }
        flush(current, paragraphs);
        return paragraphs;
    }

    private static void flush(StringBuilder current, List<String> paragraphs) {
        if (current.length() == 0) {
            return;
        }
        String paragraph = current.toString();
        current.setLength(0);
        if (!isDisclaimer(paragraph)) {
            paragraphs.add(paragraph);
        }
    }

    private static boolean isStructuralLine(String line) {
        return line.startsWith("#") || line.equals("---") || line.equals("***") || line.equals("___");
    }

    private static boolean isDisclaimer(String paragraph) {
        for (String marker : DISCLAIMER_MARKERS) {
            if (paragraph.contains(marker)) {
                return true;
            }
        }
        return false;
    }
}
