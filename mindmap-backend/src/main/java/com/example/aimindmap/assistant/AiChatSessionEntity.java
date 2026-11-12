package com.example.aimindmap.assistant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "ai_chat_sessions")
public class AiChatSessionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, length = 100)
    private String title = "新对话";

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Column(columnDefinition = "text")
    private String summary;

    @Column(name = "summarized_until_message_id")
    private Long summarizedUntilMessageId;

    @Column(name = "summary_updated_at")
    private Instant summaryUpdatedAt;

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void touch() { this.updatedAt = Instant.now(); }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public Long getSummarizedUntilMessageId() { return summarizedUntilMessageId; }
    public void setSummarizedUntilMessageId(Long summarizedUntilMessageId) { this.summarizedUntilMessageId = summarizedUntilMessageId; }
    public Instant getSummaryUpdatedAt() { return summaryUpdatedAt; }
    public void setSummaryUpdatedAt(Instant summaryUpdatedAt) { this.summaryUpdatedAt = summaryUpdatedAt; }
}
