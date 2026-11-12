package com.example.aimindmap.ratelimit;

/**
 * 阶段七-3：接口限流作用域（方案 §阶段七 第 3 条 / 附录 A 第 363 行）。
 *
 * <p><b>范围严格锁定（方案 319 行「不做」清单不许扩项）：</b>只有下列三个接口限流，
 * 出题 / 再生成 / 重建等其它 AI 类接口方案未列入，一律不限流；SSE events 与查询类 GET 端点也不限流。
 *
 * <p>每个作用域对应一个阈值配置键（{@code app.ratelimit.*}，见 {@link RateLimitThresholds}）
 * 与一段窗口键（{@code {scope}:{userId}}，见 {@link #windowKey(long)}）。
 */
public enum RateLimitScope {

    /** AI 问答：{@code POST /api/ai-assistant/sessions/{sessionId}/stream}（一次问答一轮 POST）。 */
    AI_CHAT("ai-chat"),

    /** PDF 上传：{@code POST /api/imports/pdf}。 */
    PDF_UPLOAD("pdf-upload"),

    /** 触发同步：{@code POST /api/documents/{documentId}/sync-jobs}。 */
    SYNC_TRIGGER("sync-trigger");

    private final String key;

    RateLimitScope(String key) {
        this.key = key;
    }

    /** 键片段（小写连字符），同时是 Redis 键与内存窗口键的作用域部分。 */
    public String key() {
        return key;
    }

    /**
     * 窗口键（不含 {@code app.cache.key-prefix} 前缀）：{@code {scope}:{userId}}。
     * Redis 实现会在前面拼 {@code {key-prefix}ratelimit:}，内存实现直接用作 Map 键。
     */
    public String windowKey(long userId) {
        return key + ":" + userId;
    }
}