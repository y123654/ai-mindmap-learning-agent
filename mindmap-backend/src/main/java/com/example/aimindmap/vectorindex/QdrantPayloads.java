package com.example.aimindmap.vectorindex;

import io.qdrant.client.ValueFactory;
import io.qdrant.client.grpc.JsonWithInt.Value;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 构造方案 §7.3 规定的全部 payload 字段。 */
final class QdrantPayloads {

    private QdrantPayloads() {
    }

    static Map<String, Value> toPayload(VectorPointData data) {
        Map<String, Value> payload = new LinkedHashMap<>();
        payload.put("user_id", ValueFactory.value(data.userId()));
        payload.put("resource_type", ValueFactory.value(data.resourceType()));
        payload.put("source_id", ValueFactory.value(data.sourceId()));
        payload.put("chunk_index", ValueFactory.value((long) data.chunkIndex()));
        payload.put("title", ValueFactory.value(data.title()));
        payload.put("content", ValueFactory.value(data.content()));
        payload.put("status", ValueFactory.value(data.status()));
        payload.put("content_version", ValueFactory.value((long) data.contentVersion()));
        payload.put("source_path", ValueFactory.value(
                data.sourcePath().stream().map(ValueFactory::value).toList()));
        payload.put("embedding_model", ValueFactory.value(data.embeddingModel()));
        payload.put("updated_at", ValueFactory.value(data.updatedAt().toString()));
        return payload;
    }

    /** gRPC 返回的 payload 值转成普通 Java 对象，供检索服务读取。 */
    static Map<String, Object> toJavaMap(Map<String, Value> raw) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Value> entry : raw.entrySet()) {
            result.put(entry.getKey(), toJavaValue(entry.getValue()));
        }
        return result;
    }

    private static Object toJavaValue(Value value) {
        return switch (value.getKindCase()) {
            case STRING_VALUE -> value.getStringValue();
            case INTEGER_VALUE -> value.getIntegerValue();
            case DOUBLE_VALUE -> value.getDoubleValue();
            case BOOL_VALUE -> value.getBoolValue();
            case LIST_VALUE -> value.getListValue().getValuesList().stream()
                    .map(QdrantPayloads::toJavaValue).toList();
            default -> null;
        };
    }
}
