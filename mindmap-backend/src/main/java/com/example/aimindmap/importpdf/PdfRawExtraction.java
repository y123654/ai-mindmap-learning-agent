package com.example.aimindmap.importpdf;

import java.util.List;

/** PDFBox 提取的原始结果：行列表、页数、空白页数、可提取文字总字符数。 */
public record PdfRawExtraction(
        List<PdfTextLine> lines,
        int pageCount,
        int emptyPages,
        int totalTextChars
) {
}
