package com.example.aimindmap.agenttracing;

import com.example.aimindmap.agenttools.LearningResourceTools;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Arrays;
import java.util.List;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

/**
 * 为每次问答创建一套带追踪/限制装饰的工具回调。
 * 原始 @Tool 回调只在启动时生成一次；装饰器与计数器每次问答新建，绝不跨请求。
 */
@Component
public class AgentToolCallbackFactory {

    private final AgentTracingService tracingService;
    private final AgentTracingProperties properties;
    private final ObjectMapper objectMapper;
    private final List<org.springframework.ai.tool.ToolCallback> rawToolCallbacks;

    public AgentToolCallbackFactory(LearningResourceTools tools,
                                    AgentTracingService tracingService,
                                    AgentTracingProperties properties,
                                    ObjectMapper objectMapper) {
        this.tracingService = tracingService;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.rawToolCallbacks = Arrays.asList(ToolCallbacks.from(tools));
    }

    public AgentRunTools createForRun(Long userId, Long runId) {
        AgentToolCallGuard guard = new AgentToolCallGuard(properties.getMaxToolCalls());
        PerRunToolResultCache cache = new PerRunToolResultCache(objectMapper);
        boolean cacheEnabled = properties.getToolCache().isEnabled();
        List<ToolCallback> decorated = rawToolCallbacks.stream()
                .<ToolCallback>map(callback -> {
                    TracingToolCallback tracingCallback = new TracingToolCallback(
                            callback, tracingService, guard, properties);
                    if (cacheEnabled) {
                        return new CachingToolCallback(tracingCallback, tracingService, cache);
                    }
                    return tracingCallback;
                })
                .toList();
        return new AgentRunTools(decorated, guard, cache);
    }
}
