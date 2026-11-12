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

@Entity
@Table(name = "mindmaps")
public class MindMapEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "document_id", nullable = false, unique = true)
    private Long documentId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "graph", nullable = false, columnDefinition = "jsonb")
    private String graph;

    @Column(name = "graph_version", nullable = false)
    private Integer graphVersion = 1;

    @Column(name = "sync_status", nullable = false, length = 32)
    private String syncStatus = "idle";

    @Column(name = "last_published_job_id")
    private Long lastPublishedJobId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

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

    public String getGraph() {
        return graph;
    }

    public void setGraph(String graph) {
        this.graph = graph;
    }

    public Integer getGraphVersion() {
        return graphVersion;
    }

    public void setGraphVersion(Integer graphVersion) {
        this.graphVersion = graphVersion;
    }

    public String getSyncStatus() {
        return syncStatus;
    }

    public void setSyncStatus(String syncStatus) {
        this.syncStatus = syncStatus;
    }

    public Long getLastPublishedJobId() {
        return lastPublishedJobId;
    }

    public void setLastPublishedJobId(Long lastPublishedJobId) {
        this.lastPublishedJobId = lastPublishedJobId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}