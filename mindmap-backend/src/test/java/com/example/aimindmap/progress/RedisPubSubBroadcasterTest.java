package com.example.aimindmap.progress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 阶段七-1：Redis 广播纯 Mockito 单测（严禁真实 Redis）。
 * 验证：频道名、envelope 内容、publish 不直投本机（靠订阅回流，防双发）、
 * 发布异常降级本机直投、坏消息/错频道被忽略。
 */
class RedisPubSubBroadcasterTest {

    private static final long JOB_ID = 7003L;
    private static final String CHANNEL = "aimindmap:job:7003";

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    private final LocalSseRegistry registry = mock(LocalSseRegistry.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RedisPubSubBroadcaster broadcaster =
            new RedisPubSubBroadcaster(redisTemplate, objectMapper, registry);

    private Message message(String channel, String body) {
        return new Message() {
            @Override
            public byte[] getBody() {
                return body.getBytes(StandardCharsets.UTF_8);
            }

            @Override
            public byte[] getChannel() {
                return channel.getBytes(StandardCharsets.UTF_8);
            }
        };
    }

    private JsonNode sentEnvelope(String expectedChannel) throws Exception {
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(redisTemplate).convertAndSend(eq(expectedChannel), body.capture());
        return objectMapper.readTree(body.getValue());
    }

    @Test
    void publishOnlySendsRedisEnvelopeAndNeverDeliversLocally() throws Exception {
        broadcaster.publish(JOB_ID, "status", new TestPayload("PARSING", 3));

        verify(registry, never()).deliver(eq(JOB_ID), anyString(), anyString());
        JsonNode envelope = sentEnvelope(CHANNEL);
        assertThat(envelope.get("jobId").asLong()).isEqualTo(JOB_ID);
        assertThat(envelope.get("eventName").asText()).isEqualTo("status");
        assertThat(envelope.get("terminal").asBoolean()).isFalse();
        JsonNode payload = objectMapper.readTree(envelope.get("payloadJson").asText());
        assertThat(payload.get("status").asText()).isEqualTo("PARSING");
        assertThat(payload.get("n").asInt()).isEqualTo(3);
    }

    @Test
    void subscribeDelegatesToLocalRegistry() {
        broadcaster.subscribe(JOB_ID);
        verify(registry).subscribe(JOB_ID);
    }

    @Test
    void publishLocalDeliversOnlyLocallyAndNeverTouchesRedis() {
        // 订阅回放只投本机 emitter，绝不写 Redis，避免泄漏给其它实例同任务连接
        broadcaster.publishLocal(JOB_ID, "status", new TestPayload("PENDING", 1));

        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(registry).deliver(eq(JOB_ID), eq("status"), json.capture());
        assertThat(json.getValue()).contains("PENDING");
        verifyNoInteractions(redisTemplate);
    }

    @Test
    void redisMessageFlowsBackToLocalDeliveryOnce() {
        String envelopeJson = """
                {"jobId":7003,"eventName":"ready","payloadJson":"{\\"status\\":\\"READY\\"}","terminal":false}
                """.trim();

        broadcaster.onMessage(message(CHANNEL, envelopeJson), null);

        verify(registry).deliver(JOB_ID, "ready", "{\"status\":\"READY\"}");
    }

    @Test
    void completeSendsTerminalEnvelope() throws Exception {
        broadcaster.complete(JOB_ID);

        JsonNode envelope = sentEnvelope(CHANNEL);
        assertThat(envelope.get("jobId").asLong()).isEqualTo(JOB_ID);
        assertThat(envelope.get("terminal").asBoolean()).isTrue();
        verify(registry, never()).complete(JOB_ID);
    }

    @Test
    void terminalMessageFlowsBackToLocalComplete() {
        String envelopeJson = """
                {"jobId":7003,"eventName":null,"payloadJson":null,"terminal":true}
                """.trim();

        broadcaster.onMessage(message(CHANNEL, envelopeJson), null);

        verify(registry).complete(JOB_ID);
        verify(registry, never()).deliver(eq(JOB_ID), anyString(), anyString());
    }

    @Test
    void publishFailureFallsBackToLocalDelivery() {
        doThrow(new RedisSystemException("redis down", null))
                .when(redisTemplate).convertAndSend(eq(CHANNEL), anyString());

        broadcaster.publish(JOB_ID, "status", new TestPayload("PARSING", 1));

        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(registry).deliver(eq(JOB_ID), eq("status"), json.capture());
        assertThat(json.getValue()).contains("PARSING");
    }

    @Test
    void completeFailureFallsBackToLocalComplete() {
        doThrow(new RedisSystemException("redis down", null))
                .when(redisTemplate).convertAndSend(eq(CHANNEL), anyString());

        broadcaster.complete(JOB_ID);

        verify(registry).complete(JOB_ID);
    }

    @Test
    void malformedMessageIsIgnored() {
        broadcaster.onMessage(message(CHANNEL, "not-a-json"), null);
        verifyNoInteractions(registry);
    }

    @Test
    void foreignChannelMessageIsIgnored() {
        broadcaster.onMessage(message("aimindmap:other:7003",
                "{\"jobId\":7003,\"eventName\":\"status\",\"payloadJson\":\"{}\",\"terminal\":false}"),
                null);
        verifyNoInteractions(registry);
    }

    @Test
    void envelopeWithoutJobIdIsIgnored() {
        broadcaster.onMessage(message(CHANNEL,
                "{\"eventName\":\"status\",\"payloadJson\":\"{}\",\"terminal\":false}"), null);
        assertThatCode(() -> {
        }).doesNotThrowAnyException();
        verifyNoInteractions(registry);
    }

    private record TestPayload(String status, int n) {
    }
}
