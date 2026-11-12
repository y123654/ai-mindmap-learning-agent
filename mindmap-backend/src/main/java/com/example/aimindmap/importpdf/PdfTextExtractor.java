package com.example.aimindmap.importpdf;

import com.example.aimindmap.common.error.ApiException;
import java.io.IOException;
import java.nio.file.Path;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.springframework.stereotype.Component;

/** 使用 PDFBox 加载 PDF 并进行字号感知的文本提取（v1.1 §15.2）。 */
@Component
public class PdfTextExtractor {

    /**
     * 加载并提取文本。页数超限、加密、损坏都抛 {@link ApiException}，由服务层落 FAILED 任务。
     */
    public PdfRawExtraction extract(Path file, int maxPages) {
        try (PDDocument document = Loader.loadPDF(file.toFile())) {
            int pageCount = document.getNumberOfPages();
            if (pageCount <= 0) {
                throw ApiException.badRequest("PDF_EMPTY", "PDF 没有可读取的页面");
            }
            if (pageCount > maxPages) {
                throw ApiException.badRequest("PDF_TOO_MANY_PAGES",
                        "PDF 页数为 " + pageCount + " 页，超过当前版本 " + maxPages + " 页上限，请拆分后再导入");
            }
            LineCollectingStripper stripper = new LineCollectingStripper();
            // getText 会驱动 processTextPosition 回调收集文本行，返回的字符串本身不使用
            stripper.getText(document);
            return stripper.build(pageCount);
        } catch (InvalidPasswordException e) {
            throw ApiException.badRequest("PDF_ENCRYPTED", "该 PDF 已加密或需要密码，当前版本无法读取");
        } catch (ApiException e) {
            throw e;
        } catch (IOException e) {
            throw ApiException.badRequest("PDF_PARSE_FAILED", "PDF 解析失败，文件可能已损坏或不是有效的 PDF");
        }
    }
}
