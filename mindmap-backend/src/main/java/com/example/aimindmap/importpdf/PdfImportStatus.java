package com.example.aimindmap.importpdf;

/** PDF 导入任务状态（v1.1 §10.7 / 修订第 28 条）。 */
public final class PdfImportStatus {

    /** 已落盘、已建任务，等待后台异步解析（阶段五：上传后快速返回的初始态）。 */
    public static final String PENDING = "PENDING";
    /** 已被后台消费者/轮询 worker 认领，正在解析提取（处理中状态）。 */
    public static final String EXTRACTING = "EXTRACTING";
    /** Markdown 草稿已生成，等待用户人工检查。 */
    public static final String DRAFT_READY = "DRAFT_READY";
    /** 疑似扫描件/图片版，无法提取有效文字，不生成草稿。 */
    public static final String SCANNED = "SCANNED";
    /** 解析失败（非 PDF、加密、损坏、超页数等）。 */
    public static final String FAILED = "FAILED";
    /** 用户已接受草稿并据此新建了笔记。 */
    public static final String ACCEPTED = "ACCEPTED";

    private PdfImportStatus() {
    }
}
