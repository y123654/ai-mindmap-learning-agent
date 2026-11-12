package com.example.aimindmap.vectorindex;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** 确定性 Qdrant 点 ID：同一逻辑点多次写入/重建必然覆盖，杜绝重复点。 */
public final class VectorPointIds {

    private VectorPointIds() {
    }

    public static UUID pointId(VectorResourceType resourceType, String resourceId, int chunkIndex) {
        String key = resourceType.wireName() + ":" + resourceId + "#" + chunkIndex;
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
    }
}
