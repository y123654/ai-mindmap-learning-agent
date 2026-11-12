package com.example.aimindmap.mindmap;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;

public final class SyncJobDtos {

    private SyncJobDtos() {
    }

    /** §11.2 创建同步任务请求。 */
    public record CreateSyncJobRequest(
            @NotNull(message = "expectedContentVersion 不能为空")
            Integer expectedContentVersion,
            String mode
    ) {
    }

    /** §11.3 READY/任务查询返回内容。修订第 44 条：主键对外为混淆字符串。 */
    public record SyncJobResponse(
            String jobId,
            String documentId,
            String mindmapId,
            String status,
            Integer baseContentVersion,
            Integer targetContentVersion,
            Integer baseGraphVersion,
            Integer targetGraphVersion,
            Object changeSummary,
            List<Object> operations,
            Object targetGraph,
            String errorCode,
            String errorMessage,
            Instant createdAt,
            Instant readyAt,
            Instant animationStartedAt,
            Instant completedAt,
            Instant failedAt
    ) {
    }

    /** §11.3 complete 成功后返回。修订第 44 条：jobId 对外为混淆字符串。 */
    public record CompleteJobResponse(
            String jobId,
            String status,
            Integer graphVersion,
            Object graph
    ) {
    }
}