package com.example.aimindmap.agenttracing;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 单个 agent run 内的工具结果缓存（综合升级方案 第一部分 阶段一 A/B）。
 * 每次问答随 {@code AgentToolCallbackFactory.createForRun} 新建，run 结束即回收，
 * 绝不跨 run/用户/会话。缓存值为工具截断后的最终返回字符串；
 * Key = 工具名长度前缀 + 工具名 + 规范化参数 JSON（消除键序/空白差异，且杜绝跨工具碰撞）。
 */
public final class PerRunToolResultCache {

    private static final Logger log = LoggerFactory.getLogger(PerRunToolResultCache.class);

    private final ObjectMapper objectMapper;
    private final ObjectMapper orderedWriter;
    private final ConcurrentHashMap<String, String> results = new ConcurrentHashMap<>();
    private final AtomicInteger hits = new AtomicInteger();

    public PerRunToolResultCache(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.orderedWriter = objectMapper.copy()
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    }

    /**
     * 命中返回缓存结果并把命中计数加一；未命中返回 null（不加计数）。
     */
    public String get(String toolName, String rawArguments) {
        String cached = results.get(buildKey(toolName, rawArguments));
        if (cached != null) {
            hits.incrementAndGet();
        }
        return cached;
    }

    /**
     * 仅在真实调用成功返回后写入；null 结果不缓存（视为未命中，下次重新真实调用）。
     */
    public void put(String toolName, String rawArguments, String result) {
        if (result == null) {
            return;
        }
        results.put(buildKey(toolName, rawArguments), result);
    }

    public int hits() {
        return hits.get();
    }

    private String buildKey(String toolName, String rawArguments) {
        return toolName.length() + ":" + toolName + normalize(toolName, rawArguments);
    }

    /**
     * 用项目现有 Jackson 把参数解析为树，再以按键有序的映射重序列化；
     * 解析失败退化为原文精确匹配（参数正文不入日志）。
     */
    private String normalize(String toolName, String rawArguments) {
        if (rawArguments == null) {
            return "";
        }
        try {
            JsonNode tree = objectMapper.readTree(rawArguments);
            Object value = objectMapper.treeToValue(tree, Object.class);
            return orderedWriter.writeValueAsString(value);
        } catch (JsonProcessingException | RuntimeException e) {
            log.debug("工具参数 JSON 规范化失败，退化为原文精确匹配 toolName={} reason={}",
                    toolName, e.getClass().getSimpleName());
            return rawArguments;
        }
    }
}
