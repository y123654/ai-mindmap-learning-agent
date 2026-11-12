package com.example.aimindmap.progress;

/**
 * Redis Pub/Sub 广播信封（方案 §阶段七-1）。
 *
 * <p>频道为 {@code aimindmap:job:{jobId}}，消息体即本 record 的 JSON：
 * <ul>
 *   <li>{@code jobId}：同步任务 ID，冗余在消息体中，订阅方（模式订阅）以它为准回流本机注册表；</li>
 *   <li>{@code eventName}：SSE 事件名（status/ready/animating/completed/failed/cancelled）；</li>
 *   <li>{@code payloadJson}：下发给前端的业务 JSON（与单机路径序列化结果完全一致）；</li>
 *   <li>{@code terminal}：true 表示终态关连接信号（对应 {@link ProgressBroadcaster#complete}），
 *       此时 eventName/payloadJson 为空。</li>
 * </ul>
 * Redis Pub/Sub 不保证投递（发布时无订阅者/网络抖动即丢），前端轮询与对账负责兜底。
 */
public record ProgressEnvelope(Long jobId, String eventName, String payloadJson, boolean terminal) {

    static ProgressEnvelope event(Long jobId, String eventName, String payloadJson) {
        return new ProgressEnvelope(jobId, eventName, payloadJson, false);
    }

    static ProgressEnvelope terminal(Long jobId) {
        return new ProgressEnvelope(jobId, null, null, true);
    }
}
