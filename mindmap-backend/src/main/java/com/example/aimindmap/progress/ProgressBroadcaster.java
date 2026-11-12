package com.example.aimindmap.progress;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 同步任务进度广播（方案 §阶段七-1）：把任务进度事件投递给订阅该任务的 SSE 连接。
 *
 * <p>两个实现：{@link InMemoryProgressBroadcaster}（{@code app.cache.redis-enabled=false}
 * 时的单机内存直推降级）与 {@link RedisPubSubBroadcaster}（缺省启用，Redis Pub/Sub 跨实例广播）。
 *
 * <p><b>投递语义提醒：</b>Redis Pub/Sub 是「在线即达、离线即丢」的瞬态消息，不持久化、不重放，
 * Redis 宕机或订阅连接断开期间的进度会丢失，因此前端仍保留轮询/对账兜底（{@code GET
 * /api/sync-jobs/{jobId}}、{@link com.example.aimindmap.mindmap.SyncJobReconciler}），
 * SSE 只做体验增强，不是状态同步的唯一通道。
 *
 * <p>事件名沿用阶段 3 契约：status / ready / animating / completed / failed / cancelled。
 * 终态事件（ready/completed/failed/cancelled）由调用方先 {@link #publish} 再 {@link #complete}。
 */
public interface ProgressBroadcaster {

    /** 订阅指定任务的进度 SSE；连接建立后的状态回放由调用方（Controller）负责。 */
    SseEmitter subscribe(Long jobId);

    /** 广播一条进度事件，payload 由实现负责 JSON 序列化。 */
    void publish(Long jobId, String eventName, Object payload);

    /**
     * 仅向本实例持有的连接直投（订阅建立后的当前状态回放专用）。
     * 回放是「某条连接自己的补偿事件」，不允许跨实例广播泄漏给同任务的其它连接；
     * 行为与阶段 3 SyncJobEventPusher 时代的本机回放保持一致。
     */
    void publishLocal(Long jobId, String eventName, Object payload);

    /** 广播终态：通知所有实例关闭该任务的本机 SSE 连接。 */
    void complete(Long jobId);
}
