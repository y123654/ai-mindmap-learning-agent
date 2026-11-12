package com.example.aimindmap.agenttracing;

import com.example.aimindmap.auth.ExternalIdCodec;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * P0-E1：Agent run 观测查询接口的 DTO（全部<b>新增</b>，不改任何既有接口/JSON 字段）。
 *
 * <p>对外 ID 一律走 {@link ExternalIdCodec} 混淆（修订第 44 条）：{@code runId} / {@code sessionId}
 * 都是字符串，前端把 completed 事件里拿到的 {@code runId} 原样拼进 URL 即可。</p>
 *
 * <p>{@code evidence.resourceId} 保持"证据快照原值"（文档为数字字符串、导图节点为 nodeId 字符串），
 * 与方案 §6 的 citation 事件示例（{@code "resourceId":"12"}）一致，不做二次编码。</p>
 */
public final class AgentRunDtos {

    private AgentRunDtos() {
    }

    /** 单 run 详情：run + steps（P1-B2 起从 agent_steps 读取，无步骤时为空数组）+ evidence + citations + tool_calls。 */
    public record RunDetailResponse(
            RunResponse run,
            List<RunStepResponse> steps,
            List<EvidenceResponse> evidence,
            List<CitationResponse> citations,
            List<ToolCallResponse> toolCalls) {
    }

    /** run 主记录（只读投影，不含提示词正文与密钥）。 */
    public record RunResponse(
            String runId,
            String sessionId,
            String status,
            String model,
            String userMessage,
            String answerSummary,
            Integer toolCallCount,
            Integer cacheHits,
            Integer promptChars,
            Integer promptTokens,
            Integer completionTokens,
            BigDecimal estimatedCost,
            Integer citationCount,
            Boolean grounded,
            Instant startedAt,
            Instant completedAt,
            Long durationMs,
            String errorCode,
            String errorMessage) {

        public static RunResponse from(AgentRunEntity entity, ExternalIdCodec idCodec) {
            Long durationMs = null;
            if (entity.getStartedAt() != null && entity.getCompletedAt() != null) {
                durationMs = Math.max(0L,
                        Duration.between(entity.getStartedAt(), entity.getCompletedAt()).toMillis());
            }
            return new RunResponse(
                    idCodec.encode(entity.getId()),
                    idCodec.encode(entity.getSessionId()),
                    entity.getStatus() == null ? null : entity.getStatus().name(),
                    entity.getModel(),
                    entity.getUserMessage(),
                    entity.getAnswerSummary(),
                    entity.getToolCallCount(),
                    entity.getCacheHits(),
                    entity.getPromptChars(),
                    entity.getPromptTokens(),
                    entity.getCompletionTokens(),
                    entity.getEstimatedCost(),
                    entity.getCitationCount(),
                    entity.getGrounded(),
                    entity.getStartedAt(),
                    entity.getCompletedAt(),
                    durationMs,
                    entity.getErrorCode(),
                    entity.getErrorMessage());
        }
    }

    /**
     * agent_steps 的一行（PLAN / RETRIEVE / SUFFICIENCY / SYNTH / VERIFY / REPAIR / RETRIEVE_FALLBACK）。
     * P1-B2 起由 {@code GET /api/agent/runs/{runId}} 从 agent_steps 读取（无步骤时为空数组）。
     */
    public record RunStepResponse(
            int stepIndex,
            String stepType,
            String name,
            String status,
            String inputSummary,
            String outputSummary,
            String errorCode,
            Long durationMs,
            Integer promptTokens,
            Integer completionTokens,
            Instant createdAt) {

        public static RunStepResponse from(AgentStepEntity entity) {
            return new RunStepResponse(
                    entity.getStepIndex() == null ? 0 : entity.getStepIndex(),
                    entity.getStepType(),
                    entity.getName(),
                    entity.getStatus(),
                    entity.getInputSummary(),
                    entity.getOutputSummary(),
                    entity.getErrorCode(),
                    entity.getDurationMs(),
                    entity.getPromptTokens(),
                    entity.getCompletionTokens(),
                    entity.getCreatedAt());
        }
    }

    /** 证据快照一行（P0-C1 落库）。 */
    public record EvidenceResponse(
            String evidenceKey,
            String resourceType,
            String resourceId,
            Integer chunkIndex,
            Float score,
            String title,
            String snippet,
            Boolean suspicious,
            Instant createdAt) {

        public static EvidenceResponse from(AgentEvidenceEntity entity) {
            return new EvidenceResponse(
                    entity.getEvidenceKey(),
                    entity.getResourceType(),
                    entity.getResourceId(),
                    entity.getChunkIndex(),
                    entity.getScore(),
                    entity.getTitle(),
                    entity.getSnippet(),
                    entity.isSuspicious(),
                    entity.getCreatedAt());
        }
    }

    /** 引用明细一行（P0-C1 落库）：编号 + 是否在证据台账里存在。 */
    public record CitationResponse(String evidenceKey, Boolean valid, Instant createdAt) {

        public static CitationResponse from(AgentCitationEntity entity) {
            return new CitationResponse(entity.getEvidenceKey(), entity.isValid(),
                    entity.getCreatedAt());
        }
    }

    /** 工具调用明细一行（阶段 3 落库，P0-E1 首次对外暴露）。 */
    public record ToolCallResponse(
            String toolName,
            String arguments,
            String resultSummary,
            String status,
            String errorMessage,
            Long durationMs,
            Boolean fromCache,
            Instant createdAt) {

        public static ToolCallResponse from(AgentToolCallEntity entity) {
            return new ToolCallResponse(
                    entity.getToolName(),
                    entity.getArguments(),
                    entity.getResultSummary(),
                    entity.getStatus() == null ? null : entity.getStatus().name(),
                    entity.getErrorMessage(),
                    entity.getDurationMs(),
                    entity.isFromCache(),
                    entity.getCreatedAt());
        }
    }

    /** 会话内 run 列表的一行（不含 evidence/citations/tool_calls 明细）。 */
    public record RunSummaryResponse(
            String runId,
            String sessionId,
            String status,
            String model,
            String userMessage,
            Integer toolCallCount,
            Integer promptTokens,
            Integer completionTokens,
            BigDecimal estimatedCost,
            Integer citationCount,
            Boolean grounded,
            Instant startedAt,
            Instant completedAt,
            Long durationMs,
            String errorCode) {

        public static RunSummaryResponse from(AgentRunEntity entity, ExternalIdCodec idCodec) {
            Long durationMs = null;
            if (entity.getStartedAt() != null && entity.getCompletedAt() != null) {
                durationMs = Math.max(0L,
                        Duration.between(entity.getStartedAt(), entity.getCompletedAt()).toMillis());
            }
            return new RunSummaryResponse(
                    idCodec.encode(entity.getId()),
                    idCodec.encode(entity.getSessionId()),
                    entity.getStatus() == null ? null : entity.getStatus().name(),
                    entity.getModel(),
                    entity.getUserMessage(),
                    entity.getToolCallCount(),
                    entity.getPromptTokens(),
                    entity.getCompletionTokens(),
                    entity.getEstimatedCost(),
                    entity.getCitationCount(),
                    entity.getGrounded(),
                    entity.getStartedAt(),
                    entity.getCompletedAt(),
                    durationMs,
                    entity.getErrorCode());
        }
    }
}
