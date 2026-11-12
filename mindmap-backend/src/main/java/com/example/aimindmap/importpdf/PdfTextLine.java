package com.example.aimindmap.importpdf;

/** PDF 中提取出的一行文本及其版面线索（页码、纵向位置、横向起点、最大字号）。 */
public record PdfTextLine(
        int page,
        float y,
        float x,
        float maxFontSize,
        String text
) {
    /** 测试/没有横向坐标信息时的兼容构造方法：按一级列表处理。 */
    public PdfTextLine(int page, float y, float maxFontSize, String text) {
        this(page, y, 0f, maxFontSize, text);
    }
}
