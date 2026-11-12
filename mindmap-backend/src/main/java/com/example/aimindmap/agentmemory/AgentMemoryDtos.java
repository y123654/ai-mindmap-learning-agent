package com.example.aimindmap.agentmemory;

import com.example.aimindmap.auth.ExternalIdCodec;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public final class AgentMemoryDtos {

    private AgentMemoryDtos() {
    }

    /**
     * 手动创建请求：不含 status 字段。
     * 客户端即使在请求体里传 status 也会被忽略，落库固定为 candidate（任务二策略）。
     */
    public record CreateMemoryRequest(
            @NotBlank(message = "记忆内容不能为空")
            @Size(max = 4000, message = "记忆内容不能超过 4000 字")
            String content,
            @NotBlank(message = "记忆类型不能为空")
            String memoryType,
            @NotNull(message = "重要程度不能为空")
            @Min(value = 1, message = "重要程度为 1~5")
            @Max(value = 5, message = "重要程度为 1~5")
            Short importance,
            @NotNull(message = "可信程度不能为空")
            @Min(value = 0, message = "可信程度为 0~100")
            @Max(value = 100, message = "可信程度为 0~100")
            Short confidence) {
    }

    /**
     * 更新请求：只含 content/memoryType/importance/confidence，不含 status。
     * status 无法通过 PUT 修改；请求体出现 status 会被忽略（任务二策略）。
     */
    public record UpdateMemoryRequest(
            @NotBlank(message = "记忆内容不能为空")
            @Size(max = 4000, message = "记忆内容不能超过 4000 字")
            String content,
            @NotBlank(message = "记忆类型不能为空")
            String memoryType,
            @NotNull(message = "重要程度不能为空")
            @Min(value = 1, message = "重要程度为 1~5")
            @Max(value = 5, message = "重要程度为 1~5")
            Short importance,
            @NotNull(message = "可信程度不能为空")
            @Min(value = 0, message = "可信程度为 0~100")
            @Max(value = 100, message = "可信程度为 0~100")
            Short confidence) {
    }

    public record MemoryResponse(
            String id,
            String memoryType,
            String content,
            Short importance,
            Short confidence,
            String status,
            Instant createdAt,
            Instant updatedAt) {

        public static MemoryResponse from(AgentMemoryEntity entity, ExternalIdCodec idCodec) {
            return new MemoryResponse(
                    idCodec.encode(entity.getId()),
                    entity.getMemoryType(),
                    entity.getContent(),
                    entity.getImportance(),
                    entity.getConfidence(),
                    entity.getStatus(),
                    entity.getCreatedAt(),
                    entity.getUpdatedAt());
        }
    }
}
