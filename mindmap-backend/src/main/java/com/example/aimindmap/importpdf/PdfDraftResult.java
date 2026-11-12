package com.example.aimindmap.importpdf;

/** 草稿构建结果：包含最终状态所需的文本指标。 */
public record PdfDraftResult(
        boolean scanned,
        String markdown,
        int pageCount,
        int emptyPages,
        int totalChars,
        int averageCharsPerPage
) {

    public static PdfDraftResult ready(String markdown, PdfRawExtraction raw, int averageCharsPerPage) {
        return new PdfDraftResult(false, markdown, raw.pageCount(), raw.emptyPages(),
                raw.totalTextChars(), averageCharsPerPage);
    }

    public static PdfDraftResult scanned(PdfRawExtraction raw, int averageCharsPerPage) {
        return new PdfDraftResult(true, null, raw.pageCount(), raw.emptyPages(),
                raw.totalTextChars(), averageCharsPerPage);
    }
}
