package com.example.aimindmap.progress;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 单机内存直推实现（{@code app.cache.redis-enabled=false} 时装配，方案 §3.5 降级开关）。
 * publish 直接投递到 {@link LocalSseRegistry} 本机持有的连接，不经过 Redis。
 */
@Component
@ConditionalOnProperty(name = "app.cache.redis-enabled", havingValue = "false")
public class InMemoryProgressBroadcaster implements ProgressBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(InMemoryProgressBroadcaster.class);

    private final ObjectMapper objectMapper;
    private final LocalSseRegistry registry;

    public InMemoryProgressBroadcaster(ObjectMapper objectMapper, LocalSseRegistry registry) {
        this.objectMapper = objectMapper;
        this.registry = registry;
    }

    @Override
    public SseEmitter subscribe(Long jobId) {
        return registry.subscribe(jobId);
    }

    @Override
    public void publish(Long jobId, String eventName, Object payload) {
        String payloadJson;
        try {
            payloadJson = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            log.warn("SSE 事件序列化失败，丢弃该事件 jobId={} event={}", jobId, eventName, e);
            return;
        }
        registry.deliver(jobId, eventName, payloadJson);
    }

    @Override
    public void publishLocal(Long jobId, String eventName, Object payload) {
        // 单机实现下本机直投与广播路径完全一致
        publish(jobId, eventName, payload);
    }

    @Override
    public void complete(Long jobId) {
        registry.complete(jobId);
    }
}
