package com.example.aimindmap.mindmap;

import com.example.aimindmap.auth.AuthUser;
import com.example.aimindmap.auth.ExternalIdCodec;
import com.example.aimindmap.auth.OwnershipGuard;
import com.example.aimindmap.mindmap.SyncJobDtos.CompleteJobResponse;
import com.example.aimindmap.mindmap.SyncJobDtos.CreateSyncJobRequest;
import com.example.aimindmap.mindmap.SyncJobDtos.SyncJobResponse;
import com.example.aimindmap.progress.ProgressBroadcaster;
import com.example.aimindmap.ratelimit.RateLimitScope;
import com.example.aimindmap.ratelimit.RateLimited;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * v1.1 §11.2 同步任务接口。阶段 3 新增 SSE events 端点。
 * 修订第 44 条：路径 id 均为外部混淆 ID。
 */
@RestController
public class SyncJobController {

    private final SyncJobService syncJobService;
    private final SyncJobResponseMapper responseMapper;
    private final ProgressBroadcaster broadcaster;
    private final OwnershipGuard ownershipGuard;
    private final ExternalIdCodec idCodec;

    public SyncJobController(SyncJobService syncJobService,
                             SyncJobResponseMapper responseMapper,
                             ProgressBroadcaster broadcaster,
                             OwnershipGuard ownershipGuard,
                             ExternalIdCodec idCodec) {
        this.syncJobService = syncJobService;
        this.responseMapper = responseMapper;
        this.broadcaster = broadcaster;
        this.ownershipGuard = ownershipGuard;
        this.idCodec = idCodec;
    }

    /** 阶段七-3：触发同步限流 {@code app.ratelimit.sync-per-min}（默认 10/min）。 */
    @RateLimited(RateLimitScope.SYNC_TRIGGER)
    @PostMapping("/api/documents/{documentId}/sync-jobs")
    @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    public SyncJobResponse createSyncJob(@AuthenticationPrincipal AuthUser user,
                                         @PathVariable String documentId,
                                         @Valid @RequestBody CreateSyncJobRequest request) {
        Long internalDocumentId = idCodec.decode(documentId);
        ownershipGuard.requireDocument(internalDocumentId, user.userId());
        return responseMapper.toResponse(syncJobService.createSyncJob(internalDocumentId, request));
    }

    @GetMapping("/api/sync-jobs/{jobId}")
    public SyncJobResponse getSyncJob(@AuthenticationPrincipal AuthUser user,
                                      @PathVariable String jobId) {
        Long internalJobId = idCodec.decode(jobId);
        ownershipGuard.requireSyncJob(internalJobId, user.userId());
        return responseMapper.toResponse(syncJobService.getJob(internalJobId));
    }

    /**
     * §11.2 SSE：连接后先回放当前状态，再接收后续进度；
     * READY/COMPLETED/FAILED/CANCELLED 推送终态后关闭连接。
     * EventSource 无法自定义请求头，JWE 令牌通过 ?access_token= 传递，由 JwtAuthenticationFilter 解析。
     */
    @GetMapping(value = "/api/sync-jobs/{jobId}/events",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@AuthenticationPrincipal AuthUser user,
                             @PathVariable String jobId) {
        Long internalJobId = idCodec.decode(jobId);
        ownershipGuard.requireSyncJob(internalJobId, user.userId());
        SyncJobEntity job = syncJobService.getJob(internalJobId);
        SseEmitter emitter = broadcaster.subscribe(internalJobId);

        String status = job.getStatus();
        switch (status) {
            case "READY", "ANIMATING", "COMPLETED" -> {
                String eventName = SyncStatus.COMPLETED.name().equals(status) ? "completed" : "ready";
                broadcaster.publishLocal(internalJobId, eventName, responseMapper.toResponse(job));
                if (SyncStatus.COMPLETED.name().equals(status)) {
                    broadcaster.complete(internalJobId);
                }
            }
            case "FAILED" -> {
                broadcaster.publishLocal(internalJobId, "failed", responseMapper.toResponse(job));
                broadcaster.complete(internalJobId);
            }
            case "CANCELLED" -> {
                broadcaster.publishLocal(internalJobId, "cancelled",
                        Map.of("jobId", idCodec.encode(internalJobId), "status", status));
                broadcaster.complete(internalJobId);
            }
            default -> broadcaster.publishLocal(internalJobId, "status",
                    Map.of("jobId", idCodec.encode(internalJobId),
                            "status", status,
                            "documentId", idCodec.encode(job.getDocumentId())));
        }
        return emitter;
    }

    @PostMapping("/api/sync-jobs/{jobId}/animation-started")
    public SyncJobResponse animationStarted(@AuthenticationPrincipal AuthUser user,
                                            @PathVariable String jobId) {
        Long internalJobId = idCodec.decode(jobId);
        ownershipGuard.requireSyncJob(internalJobId, user.userId());
        return responseMapper.toResponse(syncJobService.animationStarted(internalJobId));
    }

    @PostMapping("/api/sync-jobs/{jobId}/complete")
    public CompleteJobResponse complete(@AuthenticationPrincipal AuthUser user,
                                        @PathVariable String jobId) {
        Long internalJobId = idCodec.decode(jobId);
        ownershipGuard.requireSyncJob(internalJobId, user.userId());
        return syncJobService.complete(internalJobId);
    }

    @PostMapping("/api/sync-jobs/{jobId}/skip-animation")
    public CompleteJobResponse skipAnimation(@AuthenticationPrincipal AuthUser user,
                                             @PathVariable String jobId) {
        Long internalJobId = idCodec.decode(jobId);
        ownershipGuard.requireSyncJob(internalJobId, user.userId());
        return syncJobService.skipAnimation(internalJobId);
    }

    @PostMapping("/api/sync-jobs/{jobId}/cancel")
    public SyncJobResponse cancel(@AuthenticationPrincipal AuthUser user,
                                  @PathVariable String jobId) {
        Long internalJobId = idCodec.decode(jobId);
        ownershipGuard.requireSyncJob(internalJobId, user.userId());
        return responseMapper.toResponse(syncJobService.cancel(internalJobId));
    }
}
