package com.example.aimindmap.mindmap;

import java.util.List;

/**
 * v1.1 §10.4 / §13.4 同步任务状态。
 */
public enum SyncStatus {
    PENDING,
    PARSING,
    DIFFING,
    AI_GENERATING,
    VALIDATING,
    READY,
    ANIMATING,
    COMPLETED,
    FAILED,
    CANCELLED;

    /** §13.4：同一文档只允许一个活动同步任务。 */
    public static final List<String> ACTIVE = List.of(
            PENDING.name(),
            PARSING.name(),
            DIFFING.name(),
            AI_GENERATING.name(),
            VALIDATING.name(),
            READY.name(),
            ANIMATING.name()
    );
}