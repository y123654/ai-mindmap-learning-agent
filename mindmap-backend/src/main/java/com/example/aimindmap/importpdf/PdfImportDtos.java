package com.example.aimindmap.importpdf;

import com.example.aimindmap.auth.ExternalIdCodec;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public final class PdfImportDtos {

    private PdfImportDtos() {
    }

    /** 修订第 44 条：id / documentId 对外为混淆字符串。 */
    public record JobResponse(
            String id,
            String status,
            String originalFileName,
            Integer pageCount,
            Integer extractedTextLength,
            boolean suspectedScanned,
            String markdownDraft,
            String documentId,
            String errorCode,
            String errorMessage,
            Instant createdAt,
            Instant completedAt
    ) {
        public static JobResponse from(PdfImportJobEntity entity, ExternalIdCodec idCodec) {
            return new JobResponse(
                    idCodec.encode(entity.getId()),
                    entity.getStatus(),
                    entity.getOriginalFileName(),
                    entity.getPageCount(),
                    entity.getExtractedTextLength(),
                    entity.isSuspectedScanned(),
                    entity.getMarkdownDraft(),
                    idCodec.encode(entity.getDocumentId()),
                    entity.getErrorCode(),
                    entity.getErrorMessage(),
                    entity.getCreatedAt(),
                    entity.getCompletedAt());
        }
    }

    public record AcceptDraftRequest(
            @Size(max = 255, message = "标题不能超过 255 字")
            String title
    ) {
    }

    /** 修订第 44 条：jobId / documentId 对外为混淆字符串。 */
    public record AcceptDraftResponse(
            String jobId,
            String documentId,
            String title
    ) {
    }
}