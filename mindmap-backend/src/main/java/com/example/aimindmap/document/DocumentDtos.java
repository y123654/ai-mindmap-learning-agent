package com.example.aimindmap.document;

import com.example.aimindmap.auth.ExternalIdCodec;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;

public final class DocumentDtos {

    private DocumentDtos() {
    }

    public record CreateDocumentRequest(
            String title
    ) {
    }

    /** 修订第 23 条：重命名笔记，标题允许为空串并回退为“未命名文档”。 */
    public record RenameDocumentRequest(
            @jakarta.validation.constraints.Size(max = 255, message = "标题不能超过 255 字")
            String title
    ) {
    }

    public record SaveContentRequest(
            @NotNull String content,
            @NotNull Integer expectedVersion
    ) {
    }

    /** 修订第 44 条：对外 id 为混淆字符串。 */
    public record DocumentSummaryResponse(
            String id,
            String title,
            String contentPreview,
            Integer contentVersion,
            String sourceType,
            String status,
            Instant createdAt,
            Instant updatedAt
    ) {
        public static DocumentSummaryResponse from(DocumentEntity entity, ExternalIdCodec idCodec) {
            String normalized = entity.getContent() == null ? "" : entity.getContent()
                    .replaceAll("\\s+", " ")
                    .trim();
            String preview = normalized.length() > 120
                    ? normalized.substring(0, 120) + "…"
                    : normalized;
            return new DocumentSummaryResponse(
                    idCodec.encode(entity.getId()),
                    entity.getTitle(),
                    preview,
                    entity.getContentVersion(),
                    entity.getSourceType(),
                    entity.getStatus(),
                    entity.getCreatedAt(),
                    entity.getUpdatedAt()
            );
        }
    }

    /** 修订第 44 条：对外 id 为混淆字符串。 */
    public record DocumentResponse(
            String id,
            String title,
            String content,
            Integer contentVersion,
            String sourceType,
            String status,
            Instant createdAt,
            Instant updatedAt
    ) {
        public static DocumentResponse from(DocumentEntity entity, ExternalIdCodec idCodec) {
            return new DocumentResponse(
                    idCodec.encode(entity.getId()),
                    entity.getTitle(),
                    entity.getContent(),
                    entity.getContentVersion(),
                    entity.getSourceType(),
                    entity.getStatus(),
                    entity.getCreatedAt(),
                    entity.getUpdatedAt()
            );
        }
    }

    /** 修订第 44 条：documentId 为混淆字符串。 */
    public record SaveContentResponse(
            String documentId,
            Integer contentVersion,
            Instant savedAt
    ) {
    }
}