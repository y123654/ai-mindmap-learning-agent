package com.example.aimindmap.agenttracing;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 一次 Agent 问答的编排步骤（V13 的 {@code agent_steps} 表，P1-B2 起写入 {@code RETRIEVE_FALLBACK}）。
 *
 * <p>只存摘要：{@code input_summary} 存本次补检的查询（截断到 1000 字符以内），
 * <b>不存完整提示词</b>；{@code output_summary} 存命中条数与证据编号。
 * 与 {@code agent_runs} 一对多，run 删除时级联删除。
 */
@Entity
@Table(name = "agent_steps")
public class AgentStepEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "run_id", nullable = false)
    private Long runId;

    /** run 内递增序号（P1-C1 起从 1 开始严格递增：PLAN → RETRIEVE ×N → SYNTH，补检接着往后排）。 */
    @Column(name = "step_index", nullable = false)
    private Integer stepIndex;

    /** PLAN / RETRIEVE / SUFFICIENCY / SYNTH / VERIFY / REPAIR / RETRIEVE_FALLBACK。 */
    @Column(name = "step_type", nullable = false, length = 24)
    private String stepType;

    @Column(name = "name", length = 128)
    private String name;

    /** RUNNING / SUCCEED / FAILED / SKIPPED。 */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "input_summary", length = 1000)
    private String inputSummary;

    @Column(name = "output_summary", length = 1000)
    private String outputSummary;

    @Column(name = "error_code", length = 64)
    private String errorCode;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(name = "prompt_tokens")
    private Integer promptTokens;

    @Column(name = "completion_tokens")
    private Integer completionTokens;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public Long getId() {
        return id;
    }

    public Long getRunId() {
        return runId;
    }

    public void setRunId(Long runId) {
        this.runId = runId;
    }

    public Integer getStepIndex() {
        return stepIndex;
    }

    public void setStepIndex(Integer stepIndex) {
        this.stepIndex = stepIndex;
    }

    public String getStepType() {
        return stepType;
    }

    public void setStepType(String stepType) {
        this.stepType = stepType;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getInputSummary() {
        return inputSummary;
    }

    public void setInputSummary(String inputSummary) {
        this.inputSummary = inputSummary;
    }

    public String getOutputSummary() {
        return outputSummary;
    }

    public void setOutputSummary(String outputSummary) {
        this.outputSummary = outputSummary;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(String errorCode) {
        this.errorCode = errorCode;
    }

    public Long getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(Long durationMs) {
        this.durationMs = durationMs;
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

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
