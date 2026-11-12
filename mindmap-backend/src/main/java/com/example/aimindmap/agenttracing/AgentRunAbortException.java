package com.example.aimindmap.agenttracing;

/**
 * 致命错误：必须中止整个 Agent 问答（绕过 ToolCallingManager 对 ToolExecutionException
 * 的“转成工具错误文本喂回模型”处理，直接让本次 run 失败）。
 */
public class AgentRunAbortException extends RuntimeException {

    private final String code;

    public AgentRunAbortException(String code, String message) {
        super(message);
        this.code = code;
    }

    public AgentRunAbortException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
