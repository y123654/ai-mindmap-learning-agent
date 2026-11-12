package com.example.aimindmap.progress;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Redis Pub/Sub 跨实例广播实现（方案 §阶段七-1，{@code app.cache.redis-enabled} 缺省 true 时装配）。
 *
 * <p>频道严格为 {@value #CHANNEL_PREFIX}{@code {jobId}}（db2，由 spring.data.redis.database 指定）。
 * <b>防双发：</b>{@link #publish} 只向 Redis 发送，本实例也在模式订阅者之列，所有实例
 * （含发起者自己）收到广播后统一回流 {@link LocalSseRegistry} 只投本机持有的 emitter，
 * 因此每个 emitter 恰好收到一次；publish 路径绝不直投本机。
 *
 * <p><b>降级：</b>Redis 发布异常（连接断开/超时等 {@link DataAccessException}）只 warn，
 * 退化为本机直投，保证发起实例上的前端连接仍然可用；其它实例此时收不到，依赖前端轮询/对账兜底。
 *
 * <p><b>投递保证：</b>Redis Pub/Sub 不持久化、不重放，发布瞬间无在线订阅者的消息直接丢失，
 * 监听连接断线期间的消息也不会补投；前端轮询 {@code GET /api/sync-jobs/{jobId}} 与
 * {@link com.example.aimindmap.mindmap.SyncJobReconciler} 是最终一致性兜底，SSE 仅为体验增强。
 */
@Component
@ConditionalOnProperty(name = "app.cache.redis-enabled", havingValue = "true", matchIfMissing = true)
public class RedisPubSubBroadcaster implements ProgressBroadcaster, MessageListener {

    /** Pub/Sub 频道前缀，频道全名为 aimindmap:job:{jobId}；监听端按该前缀做模式订阅。 */
    static final String CHANNEL_PREFIX = "aimindmap:job:";

    private static final Logger log = LoggerFactory.getLogger(RedisPubSubBroadcaster.class);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final LocalSseRegistry registry;

    public RedisPubSubBroadcaster(StringRedisTemplate redisTemplate,
                                  ObjectMapper objectMapper,
                                  LocalSseRegistry registry) {
        this.redisTemplate = redisTemplate;
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
            // 与阶段 3 行为一致：业务对象都无法序列化时丢弃该事件（本机直投同样会失败）
            log.warn("SSE 事件序列化失败，丢弃该事件 jobId={} event={}", jobId, eventName, e);
            return;
        }
        String channel = CHANNEL_PREFIX + jobId;
        String envelopeJson;
        try {
            envelopeJson = objectMapper.writeValueAsString(
                    ProgressEnvelope.event(jobId, eventName, payloadJson));
        } catch (JsonProcessingException e) {
            log.warn("SSE 广播信封序列化失败，降级本机直投 jobId={} event={}", jobId, eventName, e);
            registry.deliver(jobId, eventName, payloadJson);
            return;
        }
        try {
            // 只发 Redis：本实例同样靠订阅回流，避免同机 emitter 收到两次
            redisTemplate.convertAndSend(channel, envelopeJson);
        } catch (DataAccessException e) {
            log.warn("Redis Pub/Sub 发布失败，降级为本机直投 jobId={} event={} channel={}",
                    jobId, eventName, channel, e);
            registry.deliver(jobId, eventName, payloadJson);
        }
    }

    /**
     * 订阅回放：只投本机持有的 emitter，绝不写 Redis（否则会把某条连接自己的回放
     * 泄漏给其它实例上同任务的连接，造成重复 status 事件）。
     */
    @Override
    public void publishLocal(Long jobId, String eventName, Object payload) {
        try {
            registry.deliver(jobId, eventName, objectMapper.writeValueAsString(payload));
        } catch (JsonProcessingException e) {
            log.warn("SSE 回放事件序列化失败，丢弃该事件 jobId={} event={}", jobId, eventName, e);
        }
    }

    @Override
    public void complete(Long jobId) {
        String channel = CHANNEL_PREFIX + jobId;
        String envelopeJson;
        try {
            envelopeJson = objectMapper.writeValueAsString(ProgressEnvelope.terminal(jobId));
        } catch (JsonProcessingException e) {
            log.warn("SSE 终态信封序列化失败，降级本机关闭 jobId={}", jobId, e);
            registry.complete(jobId);
            return;
        }
        try {
            redisTemplate.convertAndSend(channel, envelopeJson);
        } catch (DataAccessException e) {
            log.warn("Redis Pub/Sub 终态发布失败，降级本机关闭 jobId={} channel={}",
                    jobId, channel, e);
            registry.complete(jobId);
        }
    }

    /**
     * 订阅回流入口（所有实例共用一个模式订阅容器）。反序列化失败/频道不符只 warn，
     * 不抛异常给监听容器，避免容器因坏消息反复重投告警。
     */
    @Override
    public void onMessage(Message message, byte[] pattern) {
        String body = new String(message.getBody(), StandardCharsets.UTF_8);
        String channel = message.getChannel() == null
                ? null : new String(message.getChannel(), StandardCharsets.UTF_8);
        if (channel == null || !channel.startsWith(CHANNEL_PREFIX)) {
            log.warn("收到非预期频道的 Pub/Sub 消息，忽略 channel={}", channel);
            return;
        }
        ProgressEnvelope envelope;
        try {
            envelope = objectMapper.readValue(body, ProgressEnvelope.class);
        } catch (Exception e) {
            log.warn("SSE 广播信封反序列化失败，忽略该消息 channel={} bodyLength={}",
                    channel, body.length(), e);
            return;
        }
        if (envelope.jobId() == null) {
            log.warn("SSE 广播信封缺少 jobId，忽略该消息 channel={}", channel);
            return;
        }
        if (envelope.terminal()) {
            registry.complete(envelope.jobId());
            return;
        }
        registry.deliver(envelope.jobId(), envelope.eventName(), envelope.payloadJson());
    }
}
