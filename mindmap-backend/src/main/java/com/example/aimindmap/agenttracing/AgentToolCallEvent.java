package com.example.aimindmap.agenttracing;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 一次工具调用在 SSE 上的过程事件（P0-2，方案 §5.1）。
 *
 * <p>字段口径固定：{@code seq} 为本次问答内工具调用序号（start / 结束事件共用同一个值，
 * 前端据此合并两次事件）；{@code phase} 只有 start / succeed / failed；
 * {@code fromCache}、{@code durationMs} 仅结束事件出现，{@code errorCode} 仅失败事件出现。
 * 空值字段不参与序列化，避免旧前端把 null 当成有效值展示。</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AgentToolCallEvent(
        Long seq,
        String tool,
        String label,
        String detail,
        String phase,
        Boolean fromCache,
        Long durationMs,
        String errorCode) {

    public static final String PHASE_START = "start";
    public static final String PHASE_SUCCEED = "succeed";
    public static final String PHASE_FAILED = "failed";

    public static AgentToolCallEvent start(Long seq, String tool, String label, String detail) {
        return new AgentToolCallEvent(seq, tool, label, blankToNull(detail), PHASE_START,
                null, null, null);
    }

    public static AgentToolCallEvent succeed(Long seq, String tool, String label, String detail,
                                             Boolean fromCache, Long durationMs) {
        return new AgentToolCallEvent(seq, tool, label, blankToNull(detail), PHASE_SUCCEED,
                fromCache, durationMs, null);
    }

    public static AgentToolCallEvent failed(Long seq, String tool, String label, String detail,
                                            String errorCode) {
        return new AgentToolCallEvent(seq, tool, label, blankToNull(detail), PHASE_FAILED,
                null, null, errorCode);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
