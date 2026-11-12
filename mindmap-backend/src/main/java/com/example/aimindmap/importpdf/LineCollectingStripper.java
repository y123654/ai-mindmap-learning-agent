package com.example.aimindmap.importpdf;

import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

/**
 * 基于 PDFBox 的字号感知文本行提取器（v1.1 §15.2）。
 *
 * <p>不依赖父类的排版字符串输出，而是在 {@code processTextPosition} 回调中按纵向位置聚合同一行字符，
 * 记录该行的横向起点与最大字号，供后续“根据字号猜测标题、根据 x 坐标还原列表缩进”使用。
 */
class LineCollectingStripper extends PDFTextStripper {

    /** 一个待拼接的字符段：字符、横向起点、宽度。 */
    private record Segment(String unicode, float x, float width) {
    }

    private final List<PdfTextLine> collected = new ArrayList<>();
    private final List<Segment> currentChars = new ArrayList<>();
    private float currentY = Float.NaN;
    private float currentX = Float.NaN;
    private float currentMaxFont;
    private int currentPage = 1;
    private int pageLineStart;
    private int emptyPages;
    private int totalTextChars;

    LineCollectingStripper() throws java.io.IOException {
        super();
        setSortByPosition(true);
    }

    PdfRawExtraction build(int pageCount) {
        return new PdfRawExtraction(List.copyOf(collected), pageCount, emptyPages, totalTextChars);
    }

    @Override
    public void processPage(PDPage page) throws java.io.IOException {
        pageLineStart = collected.size();
        super.processPage(page);
        flushLine();
        boolean pageHasText = false;
        for (int i = pageLineStart; i < collected.size(); i++) {
            if (!collected.get(i).text().isBlank()) {
                pageHasText = true;
                break;
            }
        }
        if (!pageHasText) {
            emptyPages += 1;
        }
        currentPage += 1;
    }

    @Override
    protected void processTextPosition(TextPosition text) {
        String unicode = text.getUnicode();
        if (unicode == null) {
            return;
        }
        float y = text.getYDirAdj();
        if (currentChars.isEmpty() || Float.isNaN(currentY) || Math.abs(y - currentY) > 2.5f) {
            flushLine();
            currentY = y;
            currentX = text.getXDirAdj();
            currentMaxFont = text.getFontSize();
        }
        if (!Float.isNaN(currentX)) {
            currentX = Math.min(currentX, text.getXDirAdj());
        } else {
            currentX = text.getXDirAdj();
        }

        if (!currentChars.isEmpty()) {
            Segment previous = currentChars.get(currentChars.size() - 1);
            float gap = text.getXDirAdj() - (previous.x() + previous.width());
            float spaceThreshold = Math.max(1.2f, 0.35f * text.getFontSize());
            if (gap > spaceThreshold) {
                currentChars.add(new Segment(" ", previous.x() + previous.width(), 0f));
            }
        }
        currentChars.add(new Segment(unicode, text.getXDirAdj(), text.getWidth()));
        currentMaxFont = Math.max(currentMaxFont, text.getFontSize());
        if (!unicode.isBlank()) {
            totalTextChars += unicode.strip().length();
        }
    }

    private void flushLine() {
        if (currentChars.isEmpty()) {
            return;
        }
        StringBuilder builder = new StringBuilder();
        for (Segment segment : currentChars) {
            builder.append(segment.unicode());
        }
        String line = builder.toString().trim().replaceAll("\\s+", " ");
        if (!line.isEmpty()) {
            collected.add(new PdfTextLine(currentPage, currentY, currentX, currentMaxFont, line));
        }
        currentChars.clear();
        currentY = Float.NaN;
        currentX = Float.NaN;
        currentMaxFont = 0f;
    }
}
