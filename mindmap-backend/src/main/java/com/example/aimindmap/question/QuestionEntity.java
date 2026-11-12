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

/** v1.1 §10.5 题目表。 */
@Entity
@Table(name = "questions")
public class QuestionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "mindmap_id", nullable = false)
    private Long mindmapId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "node_ids", nullable = false, columnDefinition = "jsonb")
    private String nodeIds = "[]";

    @Column(name = "subject", length = 64)
    private String subject;

    @Column(name = "grade", length = 64)
    private String grade;

    @Column(name = "question_type", nullable = false, length = 32)
    private String questionType;

    @Column(name = "difficulty", nullable = false, length = 32)
    private String difficulty;

    @Column(name = "stem", nullable = false, columnDefinition = "text")
    private String stem;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "options", columnDefinition = "jsonb")
    private String options;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "answer", nullable = false, columnDefinition = "jsonb")
    private String answer;

    @Column(name = "explanation", columnDefinition = "text")
    private String explanation;

    @Column(name = "ai_model", length = 128)
    private String aiModel;

    @Column(name = "status", nullable = false, length = 32)
    private String status = "active";

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public Long getMindmapId() { return mindmapId; }
    public void setMindmapId(Long mindmapId) { this.mindmapId = mindmapId; }
    public String getNodeIds() { return nodeIds; }
    public void setNodeIds(String nodeIds) { this.nodeIds = nodeIds; }
    public String getSubject() { return subject; }
    public void setSubject(String subject) { this.subject = subject; }
    public String getGrade() { return grade; }
    public void setGrade(String grade) { this.grade = grade; }
    public String getQuestionType() { return questionType; }
    public void setQuestionType(String questionType) { this.questionType = questionType; }
    public String getDifficulty() { return difficulty; }
    public void setDifficulty(String difficulty) { this.difficulty = difficulty; }
    public String getStem() { return stem; }
    public void setStem(String stem) { this.stem = stem; }
    public String getOptions() { return options; }
    public void setOptions(String options) { this.options = options; }
    public String getAnswer() { return answer; }
    public void setAnswer(String answer) { this.answer = answer; }
    public String getExplanation() { return explanation; }
    public void setExplanation(String explanation) { this.explanation = explanation; }
    public String getAiModel() { return aiModel; }
    public void setAiModel(String aiModel) { this.aiModel = aiModel; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
