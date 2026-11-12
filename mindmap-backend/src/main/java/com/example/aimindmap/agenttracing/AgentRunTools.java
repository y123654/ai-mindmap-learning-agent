package com.example.aimindmap.agenttracing;

import java.util.List;
import org.springframework.ai.tool.ToolCallback;

/** 单次问答挂载到 ChatClient 的工具集合：回调列表与该次请求专属计数器。 */
public record AgentRunTools(
        List<ToolCallback> toolCallbacks,
        AgentToolCallGuard guard,
        PerRunToolResultCache cache) {
}
