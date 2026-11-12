package com.example.aimindmap.agenttracing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

/**
 * ToolCallback 装饰器：单 run 内对“工具名 + 规范化参数”相同的重复调用复用首次结果
 * （综合升级方案 第一部分 阶段一 B）。装饰顺序固定为
 * {@code CachingToolCallback(TracingToolCallback(rawCallback))}。
 * 命中时不经过 Guard、不占真实调用名额，只落一条 from_cache=true、duration_ms=0 的记录；
 * 未命中委托 TracingToolCallback，仅在真实调用成功返回时写缓存。
 *
 * <p>P0-2：缓存命中分支补发一对 start + succeed（fromCache=true, durationMs=0）事件；
 * 未命中分支不补发，避免与 TracingToolCallback 重复。</p>
 */
public final class CachingToolCallback implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(CachingToolCallback.class);

    private final ToolCallback delegate;
    private final AgentTracingService tracingService;
    private final PerRunToolResultCache cache;

    public CachingToolCallback(ToolCallback delegate,
                               AgentTracingService tracingService,
                               PerRunToolResultCache cache) {
        this.delegate = delegate;
        this.tracingService = tracingService;
        this.cache = cache;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return delegate.getToolMetadata();
    }

    @Override
    public String call(String toolInput) {
        return call(toolInput, null);
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        Long userId = readLong(toolContext, AgentToolContextKeys.USER_ID);
        Long runId = readLong(toolContext, AgentToolContextKeys.RUN_ID);
        String toolName = delegate.getToolDefinition().name();

        String cachedResult = cache.get(toolName, toolInput);
        if (cachedResult != null) {
            tracingService.recordCacheHitToolCall(runId, userId, toolName, toolInput, cachedResult);
            emitCacheHitEvents(toolContext, runId, toolName, toolInput);
            log.info("工具结果缓存命中 runId={} userId={} toolName={} durationMs={}",
                    runId, userId, toolName, 0);
            return cachedResult;
        }

        String result = delegate.call(toolInput, toolContext);
        // P1-D1：预算用完的固定提示不是真实工具结果，不能进缓存——否则下一次同参调用会以
        // “缓存命中”名义多落一行 agent_tool_calls，与“软降级不落库”的规格冲突。
        if (!TracingToolCallback.TOOL_BUDGET_EXHAUSTED_TEXT.equals(result)) {
            cache.put(toolName, toolInput, result);
        }
        return result;
    }

    /** 缓存命中没有真实执行耗时：同一 seq 连发 start 与 succeed(0ms)。 */
    private static void emitCacheHitEvents(ToolContext toolContext, Long runId,
                                           String toolName, String toolInput) {
        AgentToolEventSink sink = readSink(toolContext);
        String label = AgentToolDisplay.label(toolName);
        String detail = AgentToolDisplay.detail(toolName, toolInput);
        long seq = sink.nextSeq();
        emitQuietly(sink, AgentToolCallEvent.start(seq, toolName, label, detail), runId);
        emitQuietly(sink, AgentToolCallEvent.succeed(seq, toolName, label, detail,
                Boolean.TRUE, 0L), runId);
    }

    private static void emitQuietly(AgentToolEventSink sink, AgentToolCallEvent event, Long runId) {
        try {
            sink.emit(event);
        } catch (RuntimeException e) {
            log.warn("工具过程事件推送失败（不影响工具执行） runId={} tool={} phase={} error={}",
                    runId, event.tool(), event.phase(), e.toString());
        }
    }

    private static AgentToolEventSink readSink(ToolContext toolContext) {
        if (toolContext == null || toolContext.getContext() == null) {
            return AgentToolEventSink.noop();
        }
        Object value = toolContext.getContext().get(AgentToolContextKeys.TOOL_EVENT_SINK);
        return value instanceof AgentToolEventSink sink ? sink : AgentToolEventSink.noop();
    }

    private static Long readLong(ToolContext toolContext, String key) {
        if (toolContext == null) {
            throw new AgentRunAbortException("AGENT_CONTEXT_MISSING", "缺少工具上下文");
        }
        Object value = toolContext.getContext().get(key);
        if (value instanceof Long longValue) {
            return longValue;
        }
        throw new AgentRunAbortException("AGENT_CONTEXT_MISSING", "工具上下文缺少必要参数：" + key);
    }
}
