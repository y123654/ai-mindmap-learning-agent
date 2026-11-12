package com.example.aimindmap.agenttracing;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

/** 一次 Agent 问答执行记录（方案 §10.3）。 */
@Entity
@Table(name = "agent_runs")
public class AgentRunEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private AgentRunStatus status = AgentRunStatus.RUNNING;

    @Column(length = 128)
    private String model;

    @Column(name = "user_message", columnDefinition = "text")
    private String userMessage;

    @Column(name = "answer_summary", columnDefinition = "text")
    private String answerSummary;

    @Column(name = "tool_call_count", nullable = false)
    private Integer toolCallCount = 0;

    @Column(name = "cache_hits", nullable = false)
    private int cacheHits = 0;

    @Column(name = "prompt_chars")
    private Integer promptChars;

    /** 阶段二 E：本轮输入 token。先写估算值（startRun）；P0-E1 起真实平台值到达后覆盖，缺失时保留估算。 */
    @Column(name = "prompt_tokens")
    private Integer promptTokens;

    /** P0-E1：平台回传的真实输出 token（V13 列；无真实 usage 时为 null，不做估算填充）。 */
    @Column(name = "completion_tokens")
    private Integer completionTokens;

    /** P0-E1：按 app.agent.cost.* 单价折算的估算成本（元，6 位小数；真实 token 缺失时为 null，不是账单）。 */
    @Column(name = "estimated_cost", precision = 12, scale = 6)
    private BigDecimal estimatedCost;

    /**
     * P1 意图分类：CHITCHAT / DIRECT / RETRIEVE / MULTI_HOP / TASK 的枚举名（V13 列）。
     * P1-B1 起在 {@code startRun} 写入；开关关 / 未接线 / 分类失败时保持 null。
     */
    @Column(name = "intent", length = 16)
    private String intent;

    /** P0-3 证据协议：本次回答里合法引用的不同证据编号数（V13，默认 0）。 */
    @Column(name = "citation_count", nullable = false)
    private Integer citationCount = 0;

    /** P0-3 证据协议：本次回答是否至少有一个合法引用（V13，可空）。 */
    @Column(name = "grounded")
    private Boolean grounded;

    /** P1 Plan-and-Execute 预留：本轮计划 JSON（V13，可空；P0-C1 只映射不写）。 */
    @Column(name = "plan_json", columnDefinition = "text")
    private String planJson;

    /** P1 自检修复预留：已修复轮次（V13，默认 0；P0-C1 只映射不写）。 */
    @Column(name = "repair_round", nullable = false)
    private Integer repairRound = 0;

    @Column(name = "error_code", length = 64)
    private String errorCode;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt = Instant.now();

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

    public Long getSessionId() {
        return sessionId;
    }

    public void setSessionId(Long sessionId) {
        this.sessionId = sessionId;
    }

    public AgentRunStatus getStatus() {
        return status;
    }

    public void setStatus(AgentRunStatus status) {
        this.status = status;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getUserMessage() {
        return userMessage;
    }

    public void setUserMessage(String userMessage) {
        this.userMessage = userMessage;
    }

    public String getAnswerSummary() {
        return answerSummary;
    }

    public void setAnswerSummary(String answerSummary) {
        this.answerSummary = answerSummary;
    }

    public Integer getToolCallCount() {
        return toolCallCount;
    }

    public void setToolCallCount(Integer toolCallCount) {
        this.toolCallCount = toolCallCount;
    }

    public int getCacheHits() {
        return cacheHits;
    }

    public void setCacheHits(int cacheHits) {
        this.cacheHits = cacheHits;
    }

    public Integer getPromptChars() {
        return promptChars;
    }

    public void setPromptChars(Integer promptChars) {
        this.promptChars = promptChars;
    }

    public Integer getPromptTokens() {
        return promptTokens;
    }

    public void setPromptTokens(Integer promptTokens) {
        this.promptTokens = promptTokens;
    }

    public Integer getCompletionTokens() {
        return completionTokens;
    }

    public void setCompletionTokens(Integer completionTokens) {
        this.completionTokens = completionTokens;
    }

    public BigDecimal getEstimatedCost() {
        return estimatedCost;
    }

    public void setEstimatedCost(BigDecimal estimatedCost) {
        this.estimatedCost = estimatedCost;
    }

    public String getIntent() {
        return intent;
    }

    public void setIntent(String intent) {
        this.intent = intent;
    }

    public Integer getCitationCount() {
        return citationCount;
    }

    public void setCitationCount(Integer citationCount) {
        this.citationCount = citationCount;
    }

    public Boolean getGrounded() {
        return grounded;
    }

    public void setGrounded(Boolean grounded) {
        this.grounded = grounded;
    }

    public String getPlanJson() {
        return planJson;
    }

    public void setPlanJson(String planJson) {
        this.planJson = planJson;
    }

    public Integer getRepairRound() {
        return repairRound;
    }

    public void setRepairRound(Integer repairRound) {
        this.repairRound = repairRound;
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

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }
}
