package com.example.aimindmap.importpdf;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class PdfDraftBuilderTest {

    private final PdfDraftBuilder builder = new PdfDraftBuilder();
    private final AtomicInteger lineIndex = new AtomicInteger();

    private PdfTextLine line(int page, float fontSize, String text) {
        // y 坐标每行错开 19pt（小于 12pt × 1.9 段落阈值，视为同一段换行）
        return new PdfTextLine(page, 700 - lineIndex.getAndIncrement() * 19, fontSize, text);
    }

    private PdfTextLine lineAt(int page, float y, float fontSize, String text) {
        return new PdfTextLine(page, y, fontSize, text);
    }

    private PdfRawExtraction raw(int pageCount, int emptyPages, int totalChars, List<PdfTextLine> lines) {
        return new PdfRawExtraction(lines, pageCount, emptyPages, totalChars);
    }

    @Test
    void noTextAtAllIsScanned() {
        PdfDraftResult result = builder.build(raw(2, 2, 0, List.of()), 100, 0.5);

        assertThat(result.scanned()).isTrue();
        assertThat(result.markdown()).isNull();
        assertThat(result.pageCount()).isEqualTo(2);
    }

    @Test
    void sparseTextIsScanned() {
        List<PdfTextLine> lines = List.of(line(1, 12, "只有少量文字"));
        PdfDraftResult result = builder.build(raw(1, 0, 18, lines), 100, 0.5);

        assertThat(result.scanned()).isTrue();
    }

    @Test
    void tooManyEmptyPagesIsScanned() {
        // 平均字数达标（每页 100 字），但 3 页里有 2 页空白
        List<PdfTextLine> lines = List.of(
                line(1, 12, "第一页有足够多的文字内容用来保证平均每页字符数达到设定的阈值"));
        PdfDraftResult result = builder.build(raw(3, 2, 300, lines), 100, 0.5);

        assertThat(result.scanned()).isTrue();
        assertThat(result.emptyPages()).isEqualTo(2);
    }

    @Test
    void buildsHeadingsBulletsAndMergesWrappedLines() {
        List<PdfTextLine> lines = new ArrayList<>();
        lines.add(line(1, 18, "第一章 概述"));
        lines.add(line(1, 12, "这是一个中文段落被硬换"));
        lines.add(line(1, 12, "行拆开的第二行"));
        lines.add(line(1, 12, "补充说明内容也需要足够多的文字才能通过扫描阈值判断。"));
        lines.add(line(1, 12, "• 项目一"));
        lines.add(line(1, 12, "1. 有序项目"));
        lines.add(line(1, 15, "小节标题"));
        lines.add(line(1, 12, "This is an English sentence"));
        lines.add(line(1, 12, "broken across lines"));
        lines.add(line(1, 12, "informa-"));
        lines.add(line(1, 12, "tion"));

        int totalChars = lines.stream().mapToInt(l -> l.text().strip().length()).sum();
        PdfDraftResult result = builder.build(raw(1, 0, totalChars, List.copyOf(lines)), 100, 0.5);

        assertThat(result.scanned()).isFalse();
        String markdown = result.markdown();
        assertThat(markdown).contains("## 第一章 概述");
        assertThat(markdown).contains("### 小节标题");
        assertThat(markdown).contains("- 项目一");
        assertThat(markdown).contains("- 有序项目");
        // 中文硬换行直接拼接，不产生空格
        assertThat(markdown).contains("硬换行拆开的第二行");
        // 英文硬换行按空格拼接，行尾连字符被还原
        assertThat(markdown).contains("This is an English sentence broken across lines information");
        assertThat(markdown).doesNotContain("informa-");
    }

    @Test
    void bulletHorizontalPositionIsConvertedToMarkdownIndentation() {
        List<PdfTextLine> lines = new ArrayList<>();
        lines.add(new PdfTextLine(1, 700, 60f, 12f, "• 父级要点"));
        lines.add(new PdfTextLine(1, 681, 78f, 12f, "• 子级要点"));
        // 跨页后仍沿用全文缩进档位：78pt 依然是第二级，不能重置成一级
        lines.add(new PdfTextLine(2, 20, 78f, 12f, "• 跨页子级要点"));
        int totalChars = lines.stream().mapToInt(l -> l.text().strip().length()).sum();

        PdfDraftResult result = builder.build(raw(2, 0, totalChars, List.copyOf(lines)), 1, 0.9);

        assertThat(result.scanned()).isFalse();
        assertThat(result.markdown()).contains("- 父级要点");
        assertThat(result.markdown()).contains("  - 子级要点");
        assertThat(result.markdown()).contains("  - 跨页子级要点");
    }

    @Test
    void sentenceEndingLineIsNotDuplicatedWhenMergingNextLine() {
        // 回归：上一行以句号结尾时，旧实现会把已累积的段落整体重复一遍
        List<PdfTextLine> lines = new ArrayList<>();
        lines.add(line(1, 12, "这是第一句话已经完整结束了。"));
        lines.add(line(1, 12, "这是紧接着的第二句话也要拼接进来。"));
        int totalChars = lines.stream().mapToInt(l -> l.text().strip().length()).sum();

        PdfDraftResult result = builder.build(raw(1, 0, totalChars, List.copyOf(lines)), 10, 0.5);

        assertThat(result.scanned()).isFalse();
        String markdown = result.markdown();
        int first = markdown.indexOf("第一句话");
        int second = markdown.indexOf("第一句话", first + 1);
        assertThat(second).as("段落内容不能重复").isNegative();
        assertThat(markdown).contains("结束了。这是紧接着");
    }

    @Test
    void verticalGapOnSamePageStartsNewParagraphButPageBreakDoesNot() {
        // 普通行距 19pt；段落之间空一行约 27pt（大于 12pt × 1.9）
        List<PdfTextLine> lines = new ArrayList<>();
        lines.add(lineAt(1, 700, 12, "第一段第一行内容写得稍微长一点凑够字数"));
        lines.add(lineAt(1, 719, 12, "第一段第二行仍然属于同一段落内容"));
        lines.add(lineAt(1, 746, 12, "第二段第一行与前面有明显纵向空隙"));
        // 跨页即使 y 跳变也不能拆段
        lines.add(lineAt(2, 40, 12, "这是跨页后继续的同一段文字内容"));
        int totalChars = lines.stream().mapToInt(l -> l.text().strip().length()).sum();

        PdfDraftResult result = builder.build(raw(2, 0, totalChars, List.copyOf(lines)), 10, 0.5);

        assertThat(result.scanned()).isFalse();
        String[] paragraphs = result.markdown().split("\n\n");
        assertThat(paragraphs).hasSize(2);
        assertThat(paragraphs[0]).contains("第一段第二行仍然属于同一段落内容");
        assertThat(paragraphs[0]).doesNotContain("第二段");
        assertThat(paragraphs[1]).contains("纵向空隙").contains("跨页后继续");
    }
    @Test
    void englishPunctuationWrappedLinesKeepSingleSpace() {
        List<PdfTextLine> lines = new ArrayList<>();
        lines.add(line(1, 14, "Subheading Here"));
        lines.add(line(1, 12, "The scheduler picks a process, and the"));
        lines.add(line(1, 12, "kernel assigns a time slice to it. Round"));
        lines.add(line(1, 12, "robin repeats this cycle fairly. Extra padding text follows here."));

        int totalChars = lines.stream().mapToInt(l -> l.text().strip().length()).sum();
        PdfDraftResult result = builder.build(raw(1, 0, totalChars, List.copyOf(lines)), 50, 0.5);

        assertThat(result.scanned()).isFalse();
        String markdown = result.markdown();
        // 14pt 相对 12pt 正文识别为三级标题
        assertThat(markdown).contains("### Subheading Here");
        assertThat(markdown).contains("process, and the kernel assigns");
        assertThat(markdown).contains("it. Round robin repeats");
        assertThat(markdown).doesNotContain(",and").doesNotContain(".Round");
    }
    @Test
    void removesPageNumbersAndRepeatingHeaders() {
        List<PdfTextLine> lines = new ArrayList<>();
        lines.add(line(1, 12, "课件标题"));
        lines.add(line(1, 12, "正文内容第一部分写得足够长一些以保证本页提取字数充足不会被判定扫描件"));
        lines.add(line(1, 12, "- 1 -"));
        lines.add(line(2, 12, "课件标题"));
        lines.add(line(2, 12, "正文内容第二部分同样写得足够长一些以保证本页提取字数充足不会被判定扫描件"));
        lines.add(line(2, 12, "- 2 -"));
        lines.add(line(3, 12, "课件标题"));
        lines.add(line(3, 12, "正文内容第三部分也写得足够长一些以保证本页提取字数充足不会被判定扫描件"));
        lines.add(line(3, 12, "- 3 -"));

        int totalChars = lines.stream().mapToInt(l -> l.text().strip().length()).sum();
        PdfDraftResult result = builder.build(raw(3, 0, totalChars, List.copyOf(lines)), 10, 0.5);

        assertThat(result.scanned()).isFalse();
        String markdown = result.markdown();
        // 同一短行在 3 页出现 -> 页眉去掉；单独成行的页码去掉
        assertThat(markdown).doesNotContain("课件标题");
        assertThat(markdown).doesNotContain("- 1 -").doesNotContain("- 2 -").doesNotContain("- 3 -");
        assertThat(markdown).contains("正文内容第一部分");
        assertThat(markdown).contains("正文内容第二部分");
        assertThat(markdown).contains("正文内容第三部分");
    }
}
