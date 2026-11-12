package com.example.aimindmap.mindmap;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * v1.1 §10.3 document_block_snapshots：每次发布后的 Markdown 块快照，用于下次增量比较。
 */
@Entity
@Table(name = "document_block_snapshots")
public class BlockSnapshotEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "document_id", nullable = false)
    private Long documentId;

    @Column(name = "document_version", nullable = false)
    private Integer documentVersion;

    @Column(name = "block_id", nullable = false, length = 128)
    private String blockId;

    @Column(name = "block_type", nullable = false, length = 32)
    private String blockType;

    @Column(name = "anchor", nullable = false, columnDefinition = "text")
    private String anchor;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "heading_path", nullable = false, columnDefinition = "jsonb")
    private String headingPath;

    @Column(name = "normalized_text", nullable = false, columnDefinition = "text")
    private String normalizedText;

    @Column(name = "content_hash", nullable = false, length = 128)
    private String contentHash;

    @Column(name = "block_order", nullable = false)
    private Integer blockOrder;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public Long getId() {
        return id;
    }

    public Long getDocumentId() {
        return documentId;
    }

    public void setDocumentId(Long documentId) {
        this.documentId = documentId;
    }

    public Integer getDocumentVersion() {
        return documentVersion;
    }

    public void setDocumentVersion(Integer documentVersion) {
        this.documentVersion = documentVersion;
    }

    public String getBlockId() {
        return blockId;
    }

    public void setBlockId(String blockId) {
        this.blockId = blockId;
    }

    public String getBlockType() {
        return blockType;
    }

    public void setBlockType(String blockType) {
        this.blockType = blockType;
    }

    public String getAnchor() {
        return anchor;
    }

    public void setAnchor(String anchor) {
        this.anchor = anchor;
    }

    public String getHeadingPath() {
        return headingPath;
    }

    public void setHeadingPath(String headingPath) {
        this.headingPath = headingPath;
    }

    public String getNormalizedText() {
        return normalizedText;
    }

    public void setNormalizedText(String normalizedText) {
        this.normalizedText = normalizedText;
    }

    public String getContentHash() {
        return contentHash;
    }

    public void setContentHash(String contentHash) {
        this.contentHash = contentHash;
    }

    public Integer getBlockOrder() {
        return blockOrder;
    }

    public void setBlockOrder(Integer blockOrder) {
        this.blockOrder = blockOrder;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}