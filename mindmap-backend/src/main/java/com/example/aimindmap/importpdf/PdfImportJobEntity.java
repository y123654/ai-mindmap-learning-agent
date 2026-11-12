package com.example.aimindmap.importpdf;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "pdf_import_jobs")
public class PdfImportJobEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "document_id")
    private Long documentId;

    @Column(name = "original_file_name", nullable = false, length = 255)
    private String originalFileName;

    @Column(name = "stored_file_path", nullable = false, columnDefinition = "text")
    private String storedFilePath;

    @Column(nullable = false, length = 32)
    private String status = PdfImportStatus.PENDING;

    @Column(name = "page_count")
    private Integer pageCount;

    @Column(name = "extracted_text_length")
    private Integer extractedTextLength;

    @Column(name = "markdown_draft", columnDefinition = "text")
    private String markdownDraft;

    @Column(name = "suspected_scanned", nullable = false)
    private boolean suspectedScanned = false;

    @Column(name = "error_code", length = 64)
    private String errorCode;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "completed_at")
    private Instant completedAt;

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Long getDocumentId() {
        return documentId;
    }

    public void setDocumentId(Long documentId) {
        this.documentId = documentId;
    }

    public String getOriginalFileName() {
        return originalFileName;
    }

    public void setOriginalFileName(String originalFileName) {
        this.originalFileName = originalFileName;
    }

    public String getStoredFilePath() {
        return storedFilePath;
    }

    public void setStoredFilePath(String storedFilePath) {
        this.storedFilePath = storedFilePath;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Integer getPageCount() {
        return pageCount;
    }

    public void setPageCount(Integer pageCount) {
        this.pageCount = pageCount;
    }

    public Integer getExtractedTextLength() {
        return extractedTextLength;
    }

    public void setExtractedTextLength(Integer extractedTextLength) {
        this.extractedTextLength = extractedTextLength;
    }

    public String getMarkdownDraft() {
        return markdownDraft;
    }

    public void setMarkdownDraft(String markdownDraft) {
        this.markdownDraft = markdownDraft;
    }

    public boolean isSuspectedScanned() {
        return suspectedScanned;
    }

    public void setSuspectedScanned(boolean suspectedScanned) {
        this.suspectedScanned = suspectedScanned;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(String errorCode) {
        this.errorCode = errorCode;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void complete() {
        this.completedAt = Instant.now();
    }
}
