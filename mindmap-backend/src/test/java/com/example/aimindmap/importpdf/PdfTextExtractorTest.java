package com.example.aimindmap.importpdf;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PdfTextExtractorTest {

    private final PdfTextExtractor extractor = new PdfTextExtractor();
    private final PdfDraftBuilder builder = new PdfDraftBuilder();

    @TempDir
    Path tempDir;

    @Test
    void extractsNormalTextPdfIntoDraft(@TempDir Path dir) throws Exception {
        Path pdf = dir.resolve("normal.pdf");
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(document, page)) {
                PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
                cs.beginText();
                cs.setFont(font, 18);
                cs.newLineAtOffset(80, 720);
                cs.showText("Chapter Overview Heading");
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(0, -30);
                cs.showText("This is a normal text PDF with enough characters on the single page to pass");
                cs.newLineAtOffset(0, -20);
                cs.showText("the scanned detection threshold and produce a readable markdown draft body.");
                cs.endText();
            }
            document.save(pdf.toFile());
        }

        PdfRawExtraction raw = extractor.extract(pdf, 100);
        assertThat(raw.pageCount()).isEqualTo(1);
        assertThat(raw.totalTextChars()).isGreaterThan(100);

        PdfDraftResult result = builder.build(raw, 100, 0.5);
        assertThat(result.scanned()).isFalse();
        assertThat(result.markdown()).contains("Chapter Overview Heading");
        assertThat(result.markdown()).contains("##");
        assertThat(result.markdown()).contains("readable markdown draft body");
    }

    @Test
    void blankPdfIsTreatedAsScanned(@TempDir Path dir) throws Exception {
        Path pdf = dir.resolve("blank.pdf");
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage());
            document.save(pdf.toFile());
        }

        PdfRawExtraction raw = extractor.extract(pdf, 100);
        PdfDraftResult result = builder.build(raw, 100, 0.5);
        assertThat(result.scanned()).isTrue();
        assertThat(result.markdown()).isNull();
    }
}
