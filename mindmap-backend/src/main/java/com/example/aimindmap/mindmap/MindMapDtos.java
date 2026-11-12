package com.example.aimindmap.mindmap;

import java.time.Instant;

public final class MindMapDtos {

    private MindMapDtos() {
    }

    /** 修订第 44 条：id / documentId 对外为混淆字符串。 */
    public record MindMapResponse(
            String id,
            String documentId,
            Object graph,
            Integer graphVersion,
            String syncStatus,
            Instant updatedAt
    ) {
    }
}