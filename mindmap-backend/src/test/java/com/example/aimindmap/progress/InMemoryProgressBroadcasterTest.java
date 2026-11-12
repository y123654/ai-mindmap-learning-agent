package com.example.aimindmap.progress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 阶段七-1：单机内存广播纯 Mockito 单测（无 Redis）：
 * publish 只负责序列化，投递完全委托 {@link LocalSseRegistry}。
 */
class InMemoryProgressBroadcasterTest {

    private final LocalSseRegistry registry = mock(LocalSseRegistry.class);
    private final InMemoryProgressBroadcaster broadcaster =
            new InMemoryProgressBroadcaster(new ObjectMapper(), registry);

    /** 直接自引用对象，Jackson 序列化必然抛 JsonProcessingException。 */
    static class SelfRef {
        SelfRef self;
    }

    @Test
    void subscribeDelegatesToLocalRegistry() {
        SseEmitter emitter = mock(SseEmitter.class);
        org.mockito.Mockito.when(registry.subscribe(7002L)).thenReturn(emitter);

        assertThat(broadcaster.subscribe(7002L)).isSameAs(emitter);
        verify(registry).subscribe(7002L);
    }

    @Test
    void publishSerializesPayloadAndDeliversLocally() {
        broadcaster.publish(7002L, "status", Map.of("status", "PARSING", "n", 2));

        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(registry).deliver(org.mockito.ArgumentMatchers.eq(7002L),
                org.mockito.ArgumentMatchers.eq("status"), json.capture());
        assertThat(json.getValue()).contains("\"status\":\"PARSING\"").contains("\"n\":2");
    }

    @Test
    void completeDelegatesToLocalRegistry() {
        broadcaster.complete(7002L);
        verify(registry).complete(7002L);
    }

    @Test
    void publishLocalSerializesAndDeliversLocally() {
        broadcaster.publishLocal(7002L, "status", Map.of("status", "PENDING"));

        verify(registry).deliver(org.mockito.ArgumentMatchers.eq(7002L),
                org.mockito.ArgumentMatchers.eq("status"),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void serializationFailureIsSwallowedAndNothingDelivered() {
        SelfRef boom = new SelfRef();
        boom.self = boom;

        assertThatCode(() -> broadcaster.publish(7002L, "status", boom)).doesNotThrowAnyException();
        verify(registry, never()).deliver(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
    }
}
