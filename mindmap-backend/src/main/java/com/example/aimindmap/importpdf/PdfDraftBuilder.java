package com.example.aimindmap.importpdf;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 把 PDFBox 提取的文本行整理成 Markdown 草稿，并判断是否疑似扫描件（v1.1 §15.2/§15.3）。
 *
 * <p>纯文本启发式，不调用 AI：
 * <ul>
 *   <li>去掉页码行与跨页重复的页眉页脚；</li>
 *   <li>按字号相对正文的倍数猜测 ##/### 标题；</li>
 *   <li>识别项目符号行；</li>
 *   <li>合并 PDF 硬换行拆开的同一段落（中文直接拼接、英文按空格拼接）；</li>
 *   <li>同页内纵向空隙明显大于普通行距时，视为两个段落（PDF 里没有空行文本）。</li>
 * </ul>
 */
@Component
public class PdfDraftBuilder {

    /** 单独成行的页码，如 12、- 12 -、第 3 页。 */
    private static final Pattern PAGE_NUMBER = Pattern.compile(
            "^\\s*(?:[-–—]\\s*)?(?:第\\s*)?\\d{1,4}\\s*(?:/\\s*\\d{1,4})?\\s*(?:页)?\\s*[-–—]?\\s*$");
    /** 项目符号或“1.”“1、”“1)”开头的列表行。 */
    private static final Pattern BULLET = Pattern.compile(
            "^\\s*(?:[•·▪◦■●○*-]|\\d{1,2}[.、)])\\s+(.+)$");
    /** 段落结束标点。 */
    private static final Pattern SENTENCE_END = Pattern.compile("[。！？!?；;…：:”\"’』】)]$");
    private static final Set<Character> CJK_FULLWIDTH_PUNCT = Set.of(
            '，', '。', '、', '；', '：', '？', '！', '”', '’', '）', '】', '》', '…', '—');
    /** 英文行尾出现这些闭标点，且下一行以字母/数字开头时，补一个空格。 */
    private static final String ASCII_GAP_AFTER = ".,;:!?)]}\"";

    /** 同页内纵向空隙超过字号的该倍数，视为段落之间的空行（普通行距约 1.5～1.6 倍字号）。 */
    private static final float PARAGRAPH_GAP_FONT_RATIO = 1.9f;

    public PdfDraftResult build(PdfRawExtraction raw, int minCharsPerPage, double maxEmptyPageRatio) {
        int pageCount = Math.max(1, raw.pageCount());
        int averageCharsPerPage = raw.totalTextChars() / pageCount;

        boolean tooSparse = averageCharsPerPage < minCharsPerPage;
        double emptyRatio = raw.pageCount() == 0 ? 1.0 : (double) raw.emptyPages() / raw.pageCount();
        boolean tooManyEmptyPages = emptyRatio > maxEmptyPageRatio;
        if (tooSparse || tooManyEmptyPages || raw.totalTextChars() == 0) {
            return PdfDraftResult.scanned(raw, averageCharsPerPage);
        }

        List<PdfTextLine> lines = removeRepeatingHeaders(removePageNumbers(raw.lines()));
        float bodyFontSize = medianBodyFontSize(lines);
        String markdown = composeMarkdown(lines, bodyFontSize);
        if (markdown.isBlank()) {
            return PdfDraftResult.scanned(raw, averageCharsPerPage);
        }
        return PdfDraftResult.ready(markdown, raw, averageCharsPerPage);
    }

    private List<PdfTextLine> removePageNumbers(List<PdfTextLine> lines) {
        List<PdfTextLine> kept = new ArrayList<>();
        for (PdfTextLine line : lines) {
            if (!PAGE_NUMBER.matcher(line.text()).matches()) {
                kept.add(line);
            }
        }
        return kept;
    }

    /** 同一短行在 3 个及以上不同页面出现，视为页眉/页脚并移除。 */
    private List<PdfTextLine> removeRepeatingHeaders(List<PdfTextLine> lines) {
        Map<String, Set<Integer>> pagesByText = new HashMap<>();
        for (PdfTextLine line : lines) {
            String normalized = line.text().replaceAll("\\d", "#");
            if (normalized.length() <= 40) {
                pagesByText.computeIfAbsent(normalized, key -> new HashSet<>()).add(line.page());
            }
        }
        Set<String> repeating = new HashSet<>();
        pagesByText.forEach((text, pages) -> {
            if (pages.size() >= 3) {
                repeating.add(text);
            }
        });
        if (repeating.isEmpty()) {
            return lines;
        }
        List<PdfTextLine> kept = new ArrayList<>();
        for (PdfTextLine line : lines) {
            String normalized = line.text().replaceAll("\\d", "#");
            if (!repeating.contains(normalized)) {
                kept.add(line);
            }
        }
        return kept;
    }

    private float medianBodyFontSize(List<PdfTextLine> lines) {
        List<Float> sizes = new ArrayList<>();
        for (PdfTextLine line : lines) {
            if (line.text().length() >= 4) {
                sizes.add(line.maxFontSize());
            }
        }
        if (sizes.isEmpty()) {
            return 12f;
        }
        sizes.sort(Float::compare);
        return sizes.get(sizes.size() / 2);
    }

    private String composeMarkdown(List<PdfTextLine> lines, float bodyFontSize) {
        List<Float> bulletIndentStops = bulletIndentStops(lines);
        List<String> blocks = new ArrayList<>();
        StringBuilder paragraph = new StringBuilder();
        PdfTextLine previousTextLine = null;
        boolean previousWasParagraph = false;

        for (PdfTextLine line : lines) {
            String text = line.text().trim();
            if (text.isEmpty()) {
                flushParagraph(blocks, paragraph);
                previousTextLine = null;
                previousWasParagraph = false;
                continue;
            }

            String heading = headingLevel(line, text, bodyFontSize);
            if (heading != null) {
                flushParagraph(blocks, paragraph);
                addBlockSeparator(blocks);
                blocks.add(heading + " " + text);
                blocks.add("");
                previousTextLine = line;
                previousWasParagraph = false;
                continue;
            }

            var bulletMatcher = BULLET.matcher(text);
            if (bulletMatcher.matches()) {
                flushParagraph(blocks, paragraph);
                String item = bulletMatcher.group(1).trim();
                int indentLevel = bulletIndentLevel(line, bulletIndentStops);
                blocks.add("  ".repeat(indentLevel - 1) + "- " + item);
                previousTextLine = line;
                previousWasParagraph = false;
                continue;
            }

            // 同页且纵向空隙明显大于普通行距，说明上一段已结束（PDF 里没有空行文本，只能靠间距判断）；
            // 跨页不拆段：同一段落被分页拆开时仍要继续合并。
            if (previousWasParagraph && previousTextLine != null
                    && previousTextLine.page() == line.page()
                    && isParagraphGap(previousTextLine, line)) {
                flushParagraph(blocks, paragraph);
            }
            appendToParagraph(paragraph, text);
            previousTextLine = line;
            previousWasParagraph = true;
        }
        flushParagraph(blocks, paragraph);

        return normalize(blocks);
    }

    private boolean isParagraphGap(PdfTextLine previous, PdfTextLine current) {
        float gap = current.y() - previous.y();
        float referenceFont = Math.max(8f,
                (previous.maxFontSize() + current.maxFontSize()) / 2f);
        return gap > referenceFont * PARAGRAPH_GAP_FONT_RATIO;
    }

    /**
     * 收集全文项目符号的横向位置。PDF 没有 Markdown 缩进，只能根据左边距还原层级。
     * 相近的 x 坐标（4pt 内）视为同一个缩进档位；不按页重置，避免跨页列表的子项被误判成一级。
     */
    private List<Float> bulletIndentStops(List<PdfTextLine> lines) {
        List<Float> stops = new ArrayList<>();
        for (PdfTextLine line : lines) {
            String text = line.text() == null ? "" : line.text().trim();
            if (!BULLET.matcher(text).matches()) {
                continue;
            }
            float x = line.x();
            boolean sameStop = stops.stream().anyMatch(existing -> Math.abs(existing - x) <= 4f);
            if (!sameStop) {
                stops.add(x);
            }
        }
        stops.sort(Float::compare);
        return stops;
    }

    /** 根据当前行的左边距找到它属于第几级列表，最左为第 1 级。 */
    private int bulletIndentLevel(PdfTextLine line, List<Float> stops) {
        if (stops.isEmpty() || line.x() <= 0f) {
            return 1;
        }
        for (int i = 0; i < stops.size(); i++) {
            if (Math.abs(stops.get(i) - line.x()) <= 4f) {
                return Math.min(i + 1, 6);
            }
        }
        // 理论上不会走到这里；容错为一级，避免因为坐标误差丢内容。
        return 1;
    }

    /** 字号显著大于正文、文本较短、不像句子结尾的行视为标题。 */
    private String headingLevel(PdfTextLine line, String text, float bodyFontSize) {
        if (bodyFontSize <= 0 || text.length() > 60) {
            return null;
        }
        if (SENTENCE_END.matcher(text).find()) {
            return null;
        }
        float ratio = line.maxFontSize() / bodyFontSize;
        if (ratio >= 1.4f) {
            return "##";
        }
        if (ratio >= 1.15f) {
            return "###";
        }
        return null;
    }

    private void appendToParagraph(StringBuilder paragraph, String text) {
        if (paragraph.isEmpty()) {
            paragraph.append(text);
            return;
        }
        // 把新行合并进当前段落：必须先清空再写回，不能 append，
        // 否则之前已累积的段落内容会被整体重复一遍。
        String previous = paragraph.toString();
        paragraph.setLength(0);
        paragraph.append(joinByScript(previous, text));
    }

    private String joinByScript(String left, String right) {
        String base = left;
        // 英文行尾连字符：informa- tion -> information
        if (base.endsWith("-") && startsWithAsciiLetter(right)) {
            base = base.substring(0, base.length() - 1);
            return base + right;
        }
        char last = base.charAt(base.length() - 1);
        char first = right.charAt(0);
        // 任一侧是中文，或左侧是中文全角闭标点：直接拼接（中文不需要空格）
        if (isCjk(last) || isCjk(first) || CJK_FULLWIDTH_PUNCT.contains(last)) {
            return base + right;
        }
        // 半角闭标点（句号、逗号、分号等）后接英文/数字，要补一个空格
        if (ASCII_GAP_AFTER.indexOf(last) >= 0 && isAsciiLetterOrDigit(first)) {
            return base + " " + right;
        }
        // 半角开括号后不补空格
        if (first == '(' || first == '[' || isOpenLike(first)) {
            return base + right;
        }
        // 纯英文/数字行之间按一个空格拼接
        boolean asciiGap = isAsciiLetterOrDigit(last) && isAsciiLetterOrDigit(first);
        return asciiGap ? base + " " + right : base + right;
    }

    private boolean isOpenLike(char c) {
        return c == '（' || c == '《' || c == '【' || c == '“' || c == '‘';
    }

    private boolean isCjk(char c) {
        return Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN;
    }

    private boolean startsWithAsciiLetter(String value) {
        return !value.isEmpty() && isAsciiLetterOrDigit(value.charAt(0));
    }

    private boolean isAsciiLetterOrDigit(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
    }

    private void flushParagraph(List<String> blocks, StringBuilder paragraph) {
        if (paragraph.length() > 0) {
            addBlockSeparator(blocks);
            blocks.add(paragraph.toString());
            paragraph.setLength(0);
        }
    }

    private void addBlockSeparator(List<String> blocks) {
        if (!blocks.isEmpty() && !blocks.get(blocks.size() - 1).isEmpty()) {
            blocks.add("");
        }
    }

    private String normalize(List<String> blocks) {
        List<String> cleaned = new ArrayList<>();
        boolean previousBlank = false;
        for (String block : blocks) {
            if (block.isBlank()) {
                if (!previousBlank) {
                    cleaned.add("");
                }
                previousBlank = true;
            } else {
                // 不能 trim()：列表前导空格就是 Markdown 的嵌套层级
                cleaned.add(block.stripTrailing());
                previousBlank = false;
            }
        }
        while (!cleaned.isEmpty() && cleaned.get(0).isEmpty()) {
            cleaned.remove(0);
        }
        while (!cleaned.isEmpty() && cleaned.get(cleaned.size() - 1).isEmpty()) {
            cleaned.remove(cleaned.size() - 1);
        }
        return String.join("\n", cleaned);
    }
}
