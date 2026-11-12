package com.example.aimindmap.mqsupport;

/**
 * MQ 后台任务类型（方案《阶段四·A》）。
 *
 * <p>wire 名固定使用枚举原名（{@link #name()}），不自创缩写、不改名；
 * 队列名、routing key、消息 JSON 中的 taskType 全部复用该 wire 名。
 */
public enum TaskType {
    PDF_IMPORT,
    VECTOR_UPSERT,
    VECTOR_DELETE,
    MINDMAP_SYNC,
    QUESTION_GENERATE,
    SESSION_SUMMARY;

    /** 线上传输名：与枚举原名严格一致。 */
    public String wireName() {
        return name();
    }
}
