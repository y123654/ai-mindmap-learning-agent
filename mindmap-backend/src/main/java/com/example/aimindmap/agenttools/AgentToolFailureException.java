package com.example.aimindmap.agenttools;

/**
 * 工具执行失败。fatal=false 时错误信息会作为工具结果返回给模型（如归属拒绝）；
 * fatal=true 时必须中止整个 Agent 问答（如检索基础设施故障）。
 *
 * <p>P1-D1（方案 §P1-4）：新增 {@link #retryable()}。
 * 只有 {@code retryable=true} 且 {@code fatal=false} 的失败，才会被
 * {@code TracingToolCallback} 自动重试 1 次（200ms 退避，且不额外占用工具调用名额）；
 * 旧构造一律 {@code retryable=false}，现有工具与调用方零改动、行为不变。</p>
 */
public class AgentToolFailureException extends RuntimeException {

    private final String code;
    private final boolean fatal;
    private final boolean retryable;

    public AgentToolFailureException(String code, String message, boolean fatal) {
        this(code, message, fatal, false, null);
    }

    public AgentToolFailureException(String code, String message, boolean fatal, Throwable cause) {
        this(code, message, fatal, false, cause);
    }

    /** P1-D1：带 retryable 标记（无 cause）。 */
    public AgentToolFailureException(String code, String message, boolean fatal, boolean retryable) {
        this(code, message, fatal, retryable, null);
    }

    /** P1-D1：带 retryable 标记与 cause（5 参构造）。 */
    public AgentToolFailureException(String code, String message, boolean fatal, boolean retryable,
                                     Throwable cause) {
        super(message, cause);
        this.code = code;
        this.fatal = fatal;
        this.retryable = retryable;
    }

    public String code() {
        return code;
    }

    public boolean fatal() {
        return fatal;
    }

    /** 是否允许自动重试 1 次；仅当同时 {@code fatal=false} 时才真正重试。 */
    public boolean retryable() {
        return retryable;
    }
}
