package com.example.aimindmap.vectorindex;

import java.time.Instant;
import java.util.List;

/** 写入 Qdrant 的一条点数据（payload 严格对应方案 §7.3 全部字段）。 */
public record VectorPointData(
        Long userId,
        String resourceType,
        String sourceId,
        int chunkIndex,
        String title,
        String content,
        String status,
        int contentVersion,
        List<String> sourcePath,
        String embeddingModel,
        Instant updatedAt) {
}
