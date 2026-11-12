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
 * v1.1 §10.4 同步任务表 sync_jobs。
 */
@Entity
@Table(name = "sync_jobs")
public class SyncJobEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "document_id", nullable = false)
    private Long documentId;

    @Column(name = "mindmap_id")
    private Long mindmapId;

    /** PENDING/PARSING/DIFFING/AI_GENERATING/VALIDATING/READY/ANIMATING/COMPLETED/FAILED/CANCELLED */
    @Column(nullable = false, length = 32)
    private String status;

    @Column(name = "base_content_version")
    private Integer baseContentVersion;

    @Column(name = "target_content_version", nullable = false)
    private Integer targetContentVersion;

    @Column(name = "base_graph_version")
    private Integer baseGraphVersion;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "change_summary", columnDefinition = "jsonb")
    private String changeSummary;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "ai_temp_operations", columnDefinition = "jsonb")
    private String aiTempOperations;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "validated_operations", columnDefinition = "jsonb")
    private String validatedOperations;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "target_graph", columnDefinition = "jsonb")
    private String targetGraph;

    @Column(name = "error_code", length = 64)
    private String errorCode;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "ready_at")
    private Instant readyAt;

    @Column(name = "animation_started_at")
    private Instant animationStartedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "failed_at")
    private Instant failedAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    public Long getId() {
        return id;
    }

    public Long getDocumentId() {
        return documentId;
    }

    public void setDocumentId(Long documentId) {
        this.documentId = documentId;
    }

    public Long getMindmapId() {
        return mindmapId;
    }

    public void setMindmapId(Long mindmapId) {
        this.mindmapId = mindmapId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Integer getBaseContentVersion() {
        return baseContentVersion;
    }

    public void setBaseContentVersion(Integer baseContentVersion) {
        this.baseContentVersion = baseContentVersion;
    }

    public Integer getTargetContentVersion() {
        return targetContentVersion;
    }

    public void setTargetContentVersion(Integer targetContentVersion) {
        this.targetContentVersion = targetContentVersion;
    }

    public Integer getBaseGraphVersion() {
        return baseGraphVersion;
    }

    public void setBaseGraphVersion(Integer baseGraphVersion) {
        this.baseGraphVersion = baseGraphVersion;
    }

    public String getChangeSummary() {
        return changeSummary;
    }

    public void setChangeSummary(String changeSummary) {
        this.changeSummary = changeSummary;
    }

    public String getAiTempOperations() {
        return aiTempOperations;
    }

    public void setAiTempOperations(String aiTempOperations) {
        this.aiTempOperations = aiTempOperations;
    }

    public String getValidatedOperations() {
        return validatedOperations;
    }

    public void setValidatedOperations(String validatedOperations) {
        this.validatedOperations = validatedOperations;
    }

    public String getTargetGraph() {
        return targetGraph;
    }

    public void setTargetGraph(String targetGraph) {
        this.targetGraph = targetGraph;
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

    public Instant getReadyAt() {
        return readyAt;
    }

    public void setReadyAt(Instant readyAt) {
        this.readyAt = readyAt;
    }

    public Instant getAnimationStartedAt() {
        return animationStartedAt;
    }

    public void setAnimationStartedAt(Instant animationStartedAt) {
        this.animationStartedAt = animationStartedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }

    public Instant getFailedAt() {
        return failedAt;
    }

    public void setFailedAt(Instant failedAt) {
        this.failedAt = failedAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }
}