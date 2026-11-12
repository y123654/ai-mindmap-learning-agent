package com.example.aimindmap.vectorindex;

import io.qdrant.client.grpc.Common.Filter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Qdrant 读写抽象，便于单测 mock。 */
public interface QdrantVectorGateway {

    /** 覆盖写入一条点（确定性 point id）。 */
    void upsert(String collection, VectorPoint point);

    /** 按 point id 删除。 */
    void delete(String collection, UUID pointId);

    /** 向量检索，返回原始命中点。 */
    List<RetrievedPoint> search(String collection, Filter filter, float[] queryVector, int limit);

    /** 按 point id 读取 payload（用于记忆 content_version 计数）；点不存在时返回空。 */
    Optional<Map<String, Object>> readPayload(String collection, UUID pointId);

    /** 按 filter 滚动枚举点 id（用于用户级记忆重建的残留点清理）。 */
    List<UUID> listPointIds(String collection, Filter filter);

    record VectorPoint(UUID pointId, float[] vector, VectorPointData data) {
    }

    record RetrievedPoint(UUID pointId, double score, java.util.Map<String, Object> payload) {
    }
}
