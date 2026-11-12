package com.example.aimindmap.progress;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 阶段七-1：本机 SSE 注册表纯单元测试（无 Spring 上下文、无 Redis）。
 * 框架 Handler 接口包可见，用 JDK 动态代理录制 send/complete 调用。
 */
class LocalSseRegistryTest {

    private static final Long JOB_ID = 7001L;

    /** 代理 Handler：记录收到的事件名与业务 JSON；可配置为首次发送即抛 IOException 模拟断连。 */
    private static final class RecordingHandler implements java.lang.reflect.InvocationHandler {
        private final List<String> eventNames = new ArrayList<>();
        private final List<Object> jsonData = new ArrayList<>();
        private int completeCount;
        private boolean failNextSend;

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            switch (method.getName()) {
                case "send" -> {
                    if (failNextSend) {
                        throw new IOException("connection broken");
                    }
                    if (args.length == 1 && args[0] instanceof Set<?> frames) {
                        for (Object frame : frames) {
                            ResponseBodyEmitter.DataWithMediaType item =
                                    (ResponseBodyEmitter.DataWithMediaType) frame;
                            String text = String.valueOf(item.getData());
                            if (text.startsWith("event:")) {
                                int newline = text.indexOf('\n');
                                String rest = newline < 0 ? text : text.substring(0, newline);
                                eventNames.add(rest.substring("event:".length()).trim());
                            } else if (item.getMediaType() == MediaType.APPLICATION_JSON) {
                                jsonData.add(item.getData());
                            }
                        }
                    }
                }
                case "complete" -> completeCount++;
                default -> {
                    // onTimeout/onError/onCompletion 回调注册，忽略
                }
            }
            return null;
        }
    }

    private static SseEmitter attachHandler(SseEmitter emitter, RecordingHandler handler) throws Exception {
        Class<?> handlerType = Class.forName(
                "org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter$Handler");
        Object proxy = Proxy.newProxyInstance(
                LocalSseRegistryTest.class.getClassLoader(), new Class<?>[]{handlerType}, handler);
        Method initialize = ResponseBodyEmitter.class.getDeclaredMethod("initialize", handlerType);
        initialize.setAccessible(true);
        initialize.invoke(emitter, proxy);
        return emitter;
    }

    // ---------- 阶段八-1：只读连接数统计（325 行第 ⑦ 项的数据来源） ----------

    @Test
    void activeConnectionCountIsZeroForEmptyRegistry() {
        assertThat(new LocalSseRegistry().getActiveConnectionCount()).isZero();
    }

    @Test
    void activeConnectionCountSumsAllJobs() {
        LocalSseRegistry registry = new LocalSseRegistry();

        registry.subscribe(7001L);
        registry.subscribe(7001L);
        registry.subscribe(7002L);

        assertThat(registry.getActiveConnectionCount()).isEqualTo(3);
    }

    @Test
    void activeConnectionCountDropsAfterComplete() {
        LocalSseRegistry registry = new LocalSseRegistry();
        registry.subscribe(7001L);
        registry.subscribe(7002L);

        registry.complete(7001L);

        assertThat(registry.getActiveConnectionCount()).isEqualTo(1);
    }

    @Test
    void emitterTimeoutIsTenMinutes() {
        SseEmitter emitter = new LocalSseRegistry().subscribe(JOB_ID);
        assertThat(emitter.getTimeout()).isEqualTo(TimeUnit.MINUTES.toMillis(10));
    }

    @Test
    void deliverSendsVerbatimJsonToAllLocalSubscribers() throws Exception {
        LocalSseRegistry registry = new LocalSseRegistry();
        RecordingHandler first = new RecordingHandler();
        RecordingHandler second = new RecordingHandler();
        attachHandler(registry.subscribe(JOB_ID), first);
        attachHandler(registry.subscribe(JOB_ID), second);

        String payloadJson = "{\"jobId\":\"ext\",\"status\":\"PARSING\"}";
        registry.deliver(JOB_ID, "status", payloadJson);

        assertThat(first.eventNames).containsExactly("status");
        assertThat(second.eventNames).containsExactly("status");
        assertThat(first.jsonData).containsExactly(payloadJson);
        assertThat(second.jsonData).containsExactly(payloadJson);
    }

    @Test
    void deliverToUnknownJobIsNoop() {
        new LocalSseRegistry().deliver(9999L, "status", "{}");
    }

    @Test
    void completeClosesEmittersAndRemovesThem() throws Exception {
        LocalSseRegistry registry = new LocalSseRegistry();
        RecordingHandler handler = new RecordingHandler();
        attachHandler(registry.subscribe(JOB_ID), handler);

        registry.complete(JOB_ID);
        registry.deliver(JOB_ID, "ready", "{}");

        assertThat(handler.completeCount).isEqualTo(1);
        assertThat(handler.eventNames).isEmpty();
        assertThat(handler.jsonData).isEmpty();
    }

    @Test
    void completeWithoutSubscriberIsNoop() {
        new LocalSseRegistry().complete(JOB_ID);
    }

    @Test
    void brokenEmitterIsUnsubscribedButOthersKeepReceiving() throws Exception {
        LocalSseRegistry registry = new LocalSseRegistry();
        RecordingHandler broken = new RecordingHandler();
        broken.failNextSend = true;
        RecordingHandler healthy = new RecordingHandler();
        attachHandler(registry.subscribe(JOB_ID), broken);
        attachHandler(registry.subscribe(JOB_ID), healthy);

        registry.deliver(JOB_ID, "status", "{\"n\":1}");
        registry.deliver(JOB_ID, "status", "{\"n\":2}");

        // 断连的只尝试一次后被摘除；健康的两次都收到
        assertThat(healthy.eventNames).containsExactly("status", "status");
        assertThat(healthy.jsonData).containsExactly("{\"n\":1}", "{\"n\":2}");
        assertThat(broken.eventNames).isEmpty();
    }
}
