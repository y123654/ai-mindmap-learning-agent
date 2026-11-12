package com.example.aimindmap.progress;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 本机 SSE 注册表（阶段七-1 从 mindmap.SyncJobEventPusher 原样搬迁，行为保持等价）。
 *
 * <p>{@code ConcurrentHashMap<Long, CopyOnWriteArrayList<Holder>>} 保存每个任务在本 JVM
 * 持有的全部连接；每 15 秒发送一次 ping 心跳，SseEmitter 超时 10 分钟（由 Servlet 容器
 * 触发 onTimeout 回调清理）。本类只负责「投递到本机持有的 emitter」，不感知 Redis：
 * 单机实现直接调用它；Redis 实现收到广播回流后也只调用它，天然避免双发。
 */
@Component
public class LocalSseRegistry {

    private static final Logger log = LoggerFactory.getLogger(LocalSseRegistry.class);
    private static final long EMITTER_TIMEOUT_MILLIS = TimeUnit.MINUTES.toMillis(10);
    private static final long HEARTBEAT_SECONDS = 15;

    private final ConcurrentHashMap<Long, CopyOnWriteArrayList<Holder>> registry =
            new ConcurrentHashMap<>();
    private final ScheduledExecutorService heartbeats = Executors.newScheduledThreadPool(1, runnable -> {
        Thread thread = new Thread(runnable, "sync-job-sse-heartbeat");
        thread.setDaemon(true);
        return thread;
    });

    public SseEmitter subscribe(Long jobId) {
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MILLIS);
        ScheduledFuture<?> heartbeat = heartbeats.scheduleAtFixedRate(() -> {
            try {
                emitter.send(SseEmitter.event().name("ping").data(""));
            } catch (IOException e) {
                unsubscribe(jobId, emitter);
            }
        }, HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS);

        Holder holder = new Holder(emitter, heartbeat);
        registry.computeIfAbsent(jobId, key -> new CopyOnWriteArrayList<>()).add(holder);
        emitter.onCompletion(() -> unsubscribe(jobId, emitter));
        emitter.onTimeout(() -> {
            unsubscribe(jobId, emitter);
            emitter.complete();
        });
        emitter.onError(error -> unsubscribe(jobId, emitter));
        return emitter;
    }

    /**
     * 向本机持有的 emitter 投递一条事件。{@code payloadJson} 必须是已序列化好的 JSON
     * （广播路径上 Redis envelope 携带的就是同一份 JSON，保证各实例下发内容一致）。
     */
    public void deliver(Long jobId, String eventName, String payloadJson) {
        List<Holder> holders = registry.get(jobId);
        if (holders == null || holders.isEmpty()) {
            return;
        }
        for (Holder holder : holders) {
            try {
                holder.emitter().send(SseEmitter.event()
                        .name(eventName)
                        .data(payloadJson, MediaType.APPLICATION_JSON));
            } catch (IOException e) {
                log.debug("本机 SSE 投递失败，移除连接 jobId={} event={}", jobId, eventName, e);
                unsubscribe(jobId, holder.emitter());
            }
        }
    }

    /** 关闭并移除本任务在本机的全部连接。 */
    public void complete(Long jobId) {
        List<Holder> holders = registry.remove(jobId);
        if (holders == null) {
            return;
        }
        for (Holder holder : holders) {
            holder.heartbeat().cancel(false);
            try {
                holder.emitter().complete();
            } catch (Exception e) {
                // 连接可能已断开
                log.debug("关闭 SSE 连接时忽略异常 jobId={}", jobId, e);
            }
        }
    }

    /**
     * 本机当前活跃 SSE 连接总数（阶段八-1 新增的<b>只读</b>统计，供
     * {@code aimindmap.sse.connections} Gauge 采集，对应方案 325 行「SSE 连接数」）。
     *
     * <p>不改变任何投递/清理行为：只遍历注册表求和。{@code ConcurrentHashMap#values()} 是弱一致视图，
     * 读到的是采集瞬间的近似值，对连接数面板足够；多实例部署时各实例分别上报，面板 sum 聚合。
     */
    public int getActiveConnectionCount() {
        int total = 0;
        for (List<Holder> holders : registry.values()) {
            total += holders.size();
        }
        return total;
    }

    private void unsubscribe(Long jobId, SseEmitter emitter) {
        CopyOnWriteArrayList<Holder> holders = registry.get(jobId);
        if (holders == null) {
            return;
        }
        holders.removeIf(holder -> {
            if (holder.emitter() == emitter) {
                holder.heartbeat().cancel(false);
                return true;
            }
            return false;
        });
    }

    private record Holder(SseEmitter emitter, ScheduledFuture<?> heartbeat) {
    }
}
