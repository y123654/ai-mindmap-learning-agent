package com.example.aimindmap.question;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** v1.1 §10.6 题目生成任务表。 */
@Entity
@Table(name = "question_generation_jobs")
public class QuestionGenerationJobEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "mindmap_id", nullable = false)
    private Long mindmapId;

    @Column(name = "status", nullable = false, length = 32)
    private String status = "PENDING";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "request", nullable = false, columnDefinition = "jsonb")
    private String request;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "result_question_ids", columnDefinition = "jsonb")
    private String resultQuestionIds;

    @Column(name = "error_code", length = 64)
    private String errorCode;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "completed_at")
    private Instant completedAt;

    public Long getId() { return id; }
    public Long getMindmapId() { return mindmapId; }
    public void setMindmapId(Long mindmapId) { this.mindmapId = mindmapId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getRequest() { return request; }
    public void setRequest(String request) { this.request = request; }
    public String getResultQuestionIds() { return resultQuestionIds; }
    public void setResultQuestionIds(String resultQuestionIds) { this.resultQuestionIds = resultQuestionIds; }
    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String errorCode) { this.errorCode = errorCode; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }
}
