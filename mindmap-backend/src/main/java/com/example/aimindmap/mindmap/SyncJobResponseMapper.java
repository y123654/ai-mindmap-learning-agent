package com.example.aimindmap.mindmap;

import com.example.aimindmap.auth.ExternalIdCodec;
import com.example.aimindmap.common.error.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 把 SyncJobEntity 映射为 §11.3 的 SyncJobResponse；
 * READY / ANIMATING / COMPLETED 才下发 operations 与 targetGraph。
 */
@Component
public class SyncJobResponseMapper {

    private static final List<String> CONTENT_VISIBLE_STATUSES =
            List.of(SyncStatus.READY.name(), SyncStatus.ANIMATING.name(), SyncStatus.COMPLETED.name());

    private final ObjectMapper objectMapper;

    private final ExternalIdCodec idCodec;

    public SyncJobResponseMapper(ObjectMapper objectMapper, ExternalIdCodec idCodec) {
        this.objectMapper = objectMapper;
        this.idCodec = idCodec;
    }

    public SyncJobDtos.SyncJobResponse toResponse(SyncJobEntity job) {
        boolean contentVisible = CONTENT_VISIBLE_STATUSES.contains(job.getStatus());
        Object targetGraph = contentVisible ? parseJson(job.getTargetGraph()) : null;
        Integer targetGraphVersion = null;
        if (targetGraph instanceof java.util.Map<?, ?> map
                && map.get("version") instanceof Number number) {
            targetGraphVersion = number.intValue();
        }
        return new SyncJobDtos.SyncJobResponse(
                idCodec.encode(job.getId()),
                idCodec.encode(job.getDocumentId()),
                idCodec.encode(job.getMindmapId()),
                job.getStatus(),
                job.getBaseContentVersion(),
                job.getTargetContentVersion(),
                job.getBaseGraphVersion(),
                targetGraphVersion,
                parseJson(job.getChangeSummary()),
                contentVisible ? parseJsonList(job.getValidatedOperations()) : null,
                targetGraph,
                job.getErrorCode(),
                job.getErrorMessage(),
                job.getCreatedAt(),
                job.getReadyAt(),
                job.getAnimationStartedAt(),
                job.getCompletedAt(),
                job.getFailedAt()
        );
    }

    private Object parseJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (Exception e) {
            throw ApiException.badRequest("JSON_PARSE_ERROR", "任务数据解析失败");
        }
    }

    @SuppressWarnings("unchecked")
    private List<Object> parseJsonList(String json) {
        Object parsed = parseJson(json);
        return parsed instanceof List<?> list ? (List<Object>) list : List.of();
    }
}