package com.example.aimindmap.mindmap;

import com.example.aimindmap.ai.AiGatewayException;
import com.example.aimindmap.auth.ExternalIdCodec;
import com.example.aimindmap.common.error.ApiException;
import com.example.aimindmap.document.DocumentEntity;
import com.example.aimindmap.document.DocumentRepository;
import com.example.aimindmap.metrics.BusinessMetrics;
import com.example.aimindmap.mqsupport.AiRetryPolicy;
import com.example.aimindmap.mqsupport.RetriableTaskException;
import com.example.aimindmap.mqsupport.TaskMessage;
import com.example.aimindmap.mqsupport.TaskType;
import com.example.aimindmap.mqsupport.outbox.IdempotentConsumptionEntity;
import com.example.aimindmap.mqsupport.outbox.IdempotentConsumptionRepository;
import com.example.aimindmap.mqsupport.outbox.OutboxEventRepository;
import com.example.aimindmap.mqsupport.outbox.OutboxEventService;
import com.example.aimindmap.mqsupport.outbox.OutboxEventStatus;
import com.example.aimindmap.progress.ProgressBroadcaster;
import com.example.aimindmap.mindmap.SyncJobDtos.CompleteJobResponse;
import com.example.aimindmap.mindmap.SyncJobDtos.CreateSyncJobRequest;
import com.example.aimindmap.mindmap.SyncJobDtos.SyncJobResponse;
import com.example.aimindmap.mindmap.graph.MindMapGraph;
import com.example.aimindmap.parser.BlockType;
import com.example.aimindmap.parser.MarkdownBlock;
import com.example.aimindmap.parser.MarkdownDiff;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.transaction.Transactional;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * v1.1 §5.2 / §11.2 / §13.3 / §13.4：同步任务状态机与发布事务（阶段六-1 增加 MQ 模式）。
 *
 * <p>MQ 模式（app.mq.enabled=true，缺省，阶段六-1）：创建任务（短事务，状态 PENDING）
 * 与 outbox 事件同事务落库 → OutboxRelay 投递 → {@link SyncJobMessageConsumer} 条件认领后
 * 调用 {@link #processJob(Long)}；失败只标记任务 FAILED，当前已发布思维导图不变（§5.3）。
 * 降级模式（app.mq.enabled=false，§3.5）：完全保持阶段 3 行为——事务提交后发
 * {@link SyncJobRequested} 事件，{@link SyncJobWorker} @Async 推进。
 * 每个状态切换都是独立短事务，AI 调用不在数据库事务中（§13.3）。
 *
 * <p>阶段八-1：{@link #processJob(Long)} 记录同步主流程耗时 {@code aimindmap.sync.job}
 * （方案 325 行「同步 RT」）。它是 MQ 消费者与 mq=false 的 @Async 兜底唯一共用入口，
 * 一处埋点覆盖两条链路；业务编排在私有的 {@link #processJobInternal(Long)}（原方法体一行未动）。
 */
@Service
public class SyncJobService {

    private static final Logger log = LoggerFactory.getLogger(SyncJobService.class);

    /**
     * 对账超时门槛：PENDING/四个中间态超过 5 分钟才视为漏发或消费者崩溃。
     * 正常 outbox 投递与单次同步均为秒级，不会误伤。
     */
    static final Duration RECONCILE_STALE_AGE = Duration.ofMinutes(5);
    /** 单轮对账最多补投的行数，防止风暴。 */
    static final int RECONCILE_BATCH_LIMIT = 20;

    private final SyncJobRepository syncJobRepository;
    private final MindMapRepository mindMapRepository;
    private final DocumentRepository documentRepository;
    private final BlockSnapshotRepository blockSnapshotRepository;
    private final SyncEngine syncEngine;
    private final GraphJsonCodec graphJsonCodec;
    private final DocumentLockRegistry lockRegistry;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;
    private final ProgressBroadcaster broadcaster;
    private final SyncJobResponseMapper responseMapper;
    private final SyncJobService self;
    private final ExternalIdCodec idCodec;
    private final com.example.aimindmap.vectorindex.VectorIndexTaskService vectorIndexTaskService;
    private final OutboxEventService outboxEventService;
    private final OutboxEventRepository outboxEventRepository;
    private final IdempotentConsumptionRepository idempotentConsumptionRepository;
    private final boolean mqEnabled;
    private final BusinessMetrics businessMetrics;

    public SyncJobService(SyncJobRepository syncJobRepository,
                          MindMapRepository mindMapRepository,
                          DocumentRepository documentRepository,
                          BlockSnapshotRepository blockSnapshotRepository,
                          SyncEngine syncEngine,
                          GraphJsonCodec graphJsonCodec,
                          DocumentLockRegistry lockRegistry,
                          ObjectMapper objectMapper,
                          ApplicationEventPublisher eventPublisher,
                          ProgressBroadcaster broadcaster,
                          SyncJobResponseMapper responseMapper,
                          @Lazy SyncJobService self,
                          ExternalIdCodec idCodec,
                          com.example.aimindmap.vectorindex.VectorIndexTaskService vectorIndexTaskService,
                          OutboxEventService outboxEventService,
                          OutboxEventRepository outboxEventRepository,
                          IdempotentConsumptionRepository idempotentConsumptionRepository,
                          @Value("${app.mq.enabled:true}") boolean mqEnabled,
                          BusinessMetrics businessMetrics) {
        this.syncJobRepository = syncJobRepository;
        this.mindMapRepository = mindMapRepository;
        this.documentRepository = documentRepository;
        this.blockSnapshotRepository = blockSnapshotRepository;
        this.syncEngine = syncEngine;
        this.graphJsonCodec = graphJsonCodec;
        this.lockRegistry = lockRegistry;
        this.objectMapper = objectMapper;
        this.eventPublisher = eventPublisher;
        this.broadcaster = broadcaster;
        this.responseMapper = responseMapper;
        this.self = self;
        this.idCodec = idCodec;
        this.vectorIndexTaskService = vectorIndexTaskService;
        this.outboxEventService = outboxEventService;
        this.outboxEventRepository = outboxEventRepository;
        this.idempotentConsumptionRepository = idempotentConsumptionRepository;
        this.mqEnabled = mqEnabled;
        this.businessMetrics = businessMetrics;
    }

    // ---------- 创建任务（HTTP 请求内短事务） ----------

    @Transactional
    public SyncJobEntity createSyncJob(Long documentId, CreateSyncJobRequest request) {
        synchronized (lockRegistry.lockFor(documentId)) {
            DocumentEntity document = documentRepository.findById(documentId)
                    .orElseThrow(() -> ApiException.notFound("文档不存在：" + documentId));
            MindMapEntity mindMap = mindMapRepository.findByDocumentId(documentId)
                    .orElseThrow(() -> ApiException.notFound("思维导图不存在，文档 ID：" + documentId));

            if (request.expectedContentVersion() == null
                    || !document.getContentVersion().equals(request.expectedContentVersion())) {
                throw ApiException.conflict("VERSION_CONFLICT",
                        "文档版本不一致，请刷新后重试。服务器版本：" + document.getContentVersion());
            }
            String mode = request.mode() == null || request.mode().isBlank()
                    ? "incremental"
                    : request.mode();
            if (!"incremental".equals(mode)) {
                throw ApiException.badRequest("UNSUPPORTED_MODE", "第一版只支持 incremental 同步模式");
            }

            Optional<SyncJobEntity> active = findActiveJobEntity(documentId);
            if (active.isPresent()) {
                SyncJobEntity job = active.get();
                throw ApiException.conflict("ACTIVE_SYNC_JOB",
                        "该文档已有进行中的同步任务（" + job.getStatus() + "），请先等待、继续或取消它",
                        Map.of("activeJobId", idCodec.encode(job.getId()), "status", job.getStatus()));
            }
            if (mindMap.getGraphVersion() == null) {
                throw ApiException.conflict("GRAPH_STATE_INVALID", "思维导图状态异常，请刷新后重试");
            }

            SyncJobEntity job = new SyncJobEntity();
            job.setDocumentId(documentId);
            job.setMindmapId(mindMap.getId());
            job.setStatus(SyncStatus.PENDING.name());
            job.setBaseContentVersion(document.getContentVersion());
            job.setTargetContentVersion(document.getContentVersion());
            job.setBaseGraphVersion(mindMap.getGraphVersion());
            SyncJobEntity saved = syncJobRepository.saveAndFlush(job);

            if (mqEnabled) {
                // 阶段六-1（缺省）：任务行与 outbox 同一事务原子落库（事务性发件箱），
                // 不再注册 afterCommit 事件、不再 publishEvent；由 OutboxRelay 投递、
                // SyncJobMessageConsumer 条件认领后执行。
                outboxEventService.recordPending(new TaskMessage(
                        TaskType.MINDMAP_SYNC, saved.getId(),
                        String.valueOf(documentId), "SYNC", Instant.now()));
            } else if (TransactionSynchronizationManager.isSynchronizationActive()) {
                // §3.5 降级路径（mq=false）：行为与阶段 3 完全一致——
                // 事务提交后再让后台线程执行，避免后台读不到未提交数据
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        eventPublisher.publishEvent(new SyncJobRequested(saved.getId()));
                    }
                });
            } else {
                // 无活动事务（理论上不应发生）：保持阶段 3 直接发事件的兜底行为
                eventPublisher.publishEvent(new SyncJobRequested(saved.getId()));
            }
            return saved;
        }
    }

    // ---------- 阶段六-1：MQ 条件认领 / 重试复位 / 对账 ----------

    /**
     * 消费者条件认领（幂等三件套之一）：仅 PENDING 行能被 DB 原子条件 UPDATE 置为 PARSING；
     * 0 行说明任务已终态或已被认领（重复投递/重试竞态/用户已取消），调用方直接 ack。
     */
    @Transactional
    public boolean claimForMessage(Long jobId) {
        return syncJobRepository.claimForConsumer(jobId) > 0;
    }

    /** 任务行是否存在：条件认领 0 行时区分"重复投递"与"毒消息（任务行不存在）"。 */
    @Transactional
    public boolean jobExists(Long jobId) {
        return syncJobRepository.existsById(jobId);
    }

    /**
     * 可重试失败转延时重试前复位任务行：PARSING/DIFFING/AI_GENERATING/VALIDATING→PENDING，
     * 重试消息到期回来后才能被 {@link #claimForMessage} 再次认领。
     */
    @Transactional
    public boolean requeueForMessageRetry(Long jobId) {
        int updated = syncJobRepository.requeueForRetry(jobId);
        if (updated == 0) {
            log.warn("同步任务重试复位未命中中间态行（可能存在并发或已终态） jobId={}", jobId);
        }
        return updated > 0;
    }

    /**
     * 对账（MQ 开启时 {@link SyncJobReconciler} 每 60s 调用一次）：补投 PENDING 超时漏发；
     * 四个中间态超时（消费者崩溃/重试耗尽进 DLQ 后基础设施恢复）复位 PENDING 并补写 outbox；
     * 已有 PENDING outbox 不重复补写。返回实际补写 outbox 的条数。
     * 年龄锚点只有 created_at（无 updated_at，沿用阶段五-A 已接受取舍）。
     */
    @Transactional
    public int reconcileStaleJobs() {
        Instant cutoff = Instant.now().minus(RECONCILE_STALE_AGE);
        List<SyncJobEntity> stalePending =
                syncJobRepository.findStalePending(cutoff, RECONCILE_BATCH_LIMIT);
        List<SyncJobEntity> staleProcessing =
                syncJobRepository.findStaleProcessing(cutoff, RECONCILE_BATCH_LIMIT);

        for (SyncJobEntity job : staleProcessing) {
            job.setStatus(SyncStatus.PENDING.name());
            syncJobRepository.save(job);
            log.warn("同步任务卡在中间态超过 {}，复位为 PENDING 等待补投 jobId={} documentId={}",
                    RECONCILE_STALE_AGE, job.getId(), job.getDocumentId());
        }

        int recovered = 0;
        for (SyncJobEntity job : stalePending) {
            recovered += reissueOutboxIfAbsent(job);
        }
        for (SyncJobEntity job : staleProcessing) {
            recovered += reissueOutboxIfAbsent(job);
        }
        if (recovered > 0) {
            log.info("同步任务对账补投 stalePending={} staleProcessing={} recovered={}",
                    stalePending.size(), staleProcessing.size(), recovered);
        }
        return recovered;
    }

    private int reissueOutboxIfAbsent(SyncJobEntity job) {
        if (outboxEventRepository.existsByTaskTypeAndTaskIdAndStatus(
                TaskType.MINDMAP_SYNC.wireName(), job.getId(), OutboxEventStatus.PENDING)) {
            return 0;
        }
        outboxEventService.recordPending(new TaskMessage(
                TaskType.MINDMAP_SYNC, job.getId(),
                String.valueOf(job.getDocumentId()), "SYNC", Instant.now()));
        return 1;
    }

    // ---------- 后台异步编排（非事务方法，事务在 self 代理的短事务方法上） ----------

    /**
     * 后台编排结果（阶段六-1）：MQ 消费者据此决定 ack / 重试 / DLQ。
     * READY 成功；EARLY_EXIT 任务已取消或已终态（非错误）；FAILED_BUSINESS 业务失败已落 FAILED；
     * FAILED_CONFIGURATION 配置类失败已落 FAILED（消息进 DLQ 仅作运维信号）。
     * 基础设施瞬时故障不落任何终态，直接抛 {@link RetriableTaskException} 走 MQ 三档重试。
     */
    public enum ProcessOutcome {
        READY,
        FAILED_BUSINESS,
        FAILED_CONFIGURATION,
        EARLY_EXIT
    }

    /**
     * 后台异步编排（非事务方法，事务在 self 代理的短事务方法上，AI 调用不在数据库事务内）。
     *
     * <p>MQ 模式由 {@link SyncJobMessageConsumer} 调用，调用前任务已被 DB 原子条件认领到 PARSING；
     * 降级模式（mq=false）由 {@link SyncJobWorker} @Async 调用，任务仍为 PENDING，
     * {@link #loadForProcessing} 同时兼容两种状态。SSE 推送序列与阶段 3 完全一致，
     * 由本方法经 {@link ProgressBroadcaster} 发布（阶段七-1 起：Redis Pub/Sub 跨实例广播，
     * 关闭 Redis 时内存直推），事件名与时机均未改变。
     *
     * <p>阶段八-1：用 try/finally 保证任何返回路径都记录一次耗时，outcome 取
     * {@link ProcessOutcome} 枚举名小写（ready / failed_business / failed_configuration /
     * early_exit），抛出未捕获异常（如 {@link RetriableTaskException}）时记 exception。
     * <b>不新增 catch、不改变返回值与异常传播</b>，纯记录。
     */
    public ProcessOutcome processJob(Long jobId) {
        long startedNanos = businessMetrics.startTimer();
        ProcessOutcome outcome = null;
        try {
            outcome = processJobInternal(jobId);
            return outcome;
        } finally {
            businessMetrics.recordSyncJobProcess(
                    outcome == null ? BusinessMetrics.SYNC_OUTCOME_EXCEPTION
                            : outcome.name().toLowerCase(Locale.ROOT),
                    startedNanos);
        }
    }

    /** 同步主流程实现（原 {@code processJob} 方法体，阶段八-1 仅改为私有并改名，逻辑零改动）。 */
    private ProcessOutcome processJobInternal(Long jobId) {
        SyncJobEntity initial = syncJobRepository.findById(jobId).orElse(null);
        if (initial == null) {
            // MQ 模式下该情况已被消费者 jobExists 拦截（进 DLQ）；只剩 mq=false 的异常竞态会到这里。
            log.warn("同步任务行不存在，提前退出 jobId={}", jobId);
            return ProcessOutcome.EARLY_EXIT;
        }
        long documentId = initial.getDocumentId();

        JobLoad load;
        try {
            load = self.loadForProcessing(jobId);
            broadcaster.publish(jobId, "status",
                    Map.of("jobId", idCodec.encode(jobId), "status", SyncStatus.PARSING.name(), "documentId", idCodec.encode(documentId)));
        } catch (ApiException e) {
            // VERSION_CONFLICT / INVALID_JOB_STATUS / 取消等业务语义：维持提前退出/业务失败路径
            log.warn("同步任务提前退出 jobId={} documentId={} code={} message={}",
                    jobId, documentId, e.getCode(), e.getMessage());
            return handleEarlyExit(jobId, e);
        } catch (DataAccessException e) {
            // 基础设施瞬时故障：不落 FAILED，抛可重试异常交 MQ 三档重试（对账亦会兜底）
            log.error("同步启动遇数据库异常，不落终态，转可重试 jobId={} documentId={}",
                    jobId, documentId, e);
            throw new RetriableTaskException("sync load DataAccessException: " + safeMessage(e), e);
        } catch (Exception e) {
            log.error("同步启动失败 jobId={} documentId={}", jobId, documentId, e);
            failJob(jobId, documentId, "SYNC_FAILED", "同步启动失败：" + safeMessage(e));
            return ProcessOutcome.FAILED_BUSINESS;
        }

        SyncEngine.SyncPreview preview;
        try {
            MindMapGraph currentGraph = graphJsonCodec.read(load.graphJson());
            preview = syncEngine.prepare(load.title(), load.content(), load.oldBlocks(), currentGraph,
                    stage -> {
                        self.updateStage(jobId, stage);
                        broadcaster.publish(jobId, "status",
                                Map.of("jobId", idCodec.encode(jobId), "status", stage, "documentId", idCodec.encode(documentId)));
                    });
        } catch (AiGatewayException e) {
            if (AiRetryPolicy.isRetriableCode(e.getCode())) {
                // AI_TIMEOUT / AI_HTTP_429 / AI_HTTP_5xx：不落任何终态，交 MQ 三档重试
                log.warn("AI 调用遇到可重试错误，任务不落终态，转延时重试 jobId={} documentId={} userId={} code={}",
                        jobId, documentId, load.userId(), e.getCode());
                throw new RetriableTaskException("retriable AI failure: " + e.getCode(), e);
            }
            if (AiRetryPolicy.isConfigurationCode(e.getCode())) {
                // AI_NOT_CONFIGURED / 4xx（429 除外）：配置类问题，重试无意义，落 FAILED；
                // 消息进 DLQ 仅作运维信号（任务已是 FAILED 终态）
                log.warn("AI 配置类错误，任务落 FAILED，消息进 DLQ 作运维信号 jobId={} documentId={} userId={} code={} message={}",
                        jobId, documentId, load.userId(), e.getCode(), e.getMessage());
                failJob(jobId, documentId, e.getCode(), e.getMessage());
                return ProcessOutcome.FAILED_CONFIGURATION;
            }
            // 其余 AI 码（AI_INPUT_TOO_LARGE、AI_RESPONSE_*、JSON 解析、内容类）：业务失败
            log.warn("AI 调用导致同步业务失败 jobId={} documentId={} userId={} code={} message={}",
                    jobId, documentId, load.userId(), e.getCode(), e.getMessage(), e);
            failJob(jobId, documentId, e.getCode(), e.getMessage());
            return ProcessOutcome.FAILED_BUSINESS;
        } catch (ApiException e) {
            log.warn("业务校验导致同步失败 jobId={} documentId={} userId={} code={} message={}",
                    jobId, documentId, load.userId(), e.getCode(), e.getMessage());
            failJob(jobId, documentId, e.getCode(), e.getMessage());
            return ProcessOutcome.FAILED_BUSINESS;
        } catch (DataAccessException e) {
            // AI 阶段数据库瞬时异常：不落终态，交 MQ 三档重试
            log.error("同步 AI 阶段遇数据库异常，不落终态，转可重试 jobId={} documentId={} userId={}",
                    jobId, documentId, load.userId(), e);
            throw new RetriableTaskException("sync prepare DataAccessException: " + safeMessage(e), e);
        } catch (Exception e) {
            log.error("同步处理发生未预期异常 jobId={} documentId={}", jobId, documentId, e);
            failJob(jobId, documentId, "SYNC_FAILED", "同步失败：" + safeMessage(e));
            return ProcessOutcome.FAILED_BUSINESS;
        }

        try {
            self.finalizeReady(jobId, preview.operations(), preview.targetGraph(),
                    buildChangeSummary(preview.diff(), preview.aiMeta()), preview.newBlocks());
        } catch (ApiException e) {
            // 任务被取消等：不推送失败，仅关闭 SSE
            log.warn("同步收尾提前退出 jobId={} documentId={} userId={} code={} message={}",
                    jobId, documentId, load.userId(), e.getCode(), e.getMessage());
            return handleEarlyExit(jobId, e);
        } catch (DataAccessException e) {
            log.error("同步收尾遇数据库异常，不落终态，转可重试 jobId={} documentId={} userId={}",
                    jobId, documentId, load.userId(), e);
            throw new RetriableTaskException("sync finalize DataAccessException: " + safeMessage(e), e);
        } catch (Exception e) {
            log.error("保存同步结果失败 jobId={} documentId={}", jobId, documentId, e);
            failJob(jobId, documentId, "SYNC_FAILED", "保存同步结果失败：" + safeMessage(e));
            return ProcessOutcome.FAILED_BUSINESS;
        }

        SyncJobEntity ready = syncJobRepository.findById(jobId).orElse(null);
        if (ready != null) {
            SyncJobResponse payload = responseMapper.toResponse(ready);
            broadcaster.publish(jobId, "ready", payload);
            broadcaster.complete(jobId);
        }
        return ProcessOutcome.READY;
    }

    private void failJob(Long jobId, long documentId, String code, String message) {
        try {
            self.markFailure(jobId, code, message);
        } finally {
            SyncJobEntity failed = syncJobRepository.findById(jobId).orElse(null);
            if (failed != null) {
                broadcaster.publish(jobId, "failed", responseMapper.toResponse(failed));
                broadcaster.complete(jobId);
            }
        }
    }

    /**
     * 创建后立刻被取消/状态已终态：不是错误，静默结束并返回 EARLY_EXIT；
     * 其余情况（如版本冲突）落业务失败，返回 FAILED_BUSINESS。
     */
    private ProcessOutcome handleEarlyExit(Long jobId, ApiException error) {
        SyncJobEntity current = syncJobRepository.findById(jobId).orElse(null);
        if (current != null && SyncStatus.CANCELLED.name().equals(current.getStatus())) {
            broadcaster.publish(jobId, "cancelled", responseMapper.toResponse(current));
            broadcaster.complete(jobId);
            return ProcessOutcome.EARLY_EXIT;
        }
        if (current != null && SyncJobStatuses.TERMINAL.contains(current.getStatus())) {
            broadcaster.complete(jobId);
            return ProcessOutcome.EARLY_EXIT;
        }
        failJob(jobId, current == null ? jobId : current.getDocumentId(),
                error.getCode(), error.getMessage());
        return ProcessOutcome.FAILED_BUSINESS;
    }

    // ---------- 短事务方法（必须经 self 代理调用） ----------

    @Transactional
    public JobLoad loadForProcessing(Long jobId) {
        SyncJobEntity job = getJob(jobId);
        synchronized (lockRegistry.lockFor(job.getDocumentId())) {
            String currentStatus = job.getStatus();
            // MQ 消费者已用条件 UPDATE 把 PENDING 原子置为 PARSING；mq=false @Async 路径仍是 PENDING。
            // 两种状态都允许继续；终态/其他状态一律拒绝。
            boolean claimedByConsumer = SyncStatus.PARSING.name().equals(currentStatus);
            if (!SyncStatus.PENDING.name().equals(currentStatus) && !claimedByConsumer) {
                throw ApiException.conflict("INVALID_JOB_STATUS",
                        "任务不是待处理状态：" + currentStatus);
            }
            DocumentEntity document = documentRepository.findById(job.getDocumentId())
                    .orElseThrow(() -> ApiException.notFound("文档不存在：" + job.getDocumentId()));
            MindMapEntity mindMap = mindMapRepository.findById(job.getMindmapId())
                    .orElseThrow(() -> ApiException.notFound("思维导图不存在"));

            if (!document.getContentVersion().equals(job.getTargetContentVersion())) {
                throw ApiException.conflict("VERSION_CONFLICT",
                        "文档版本已变化，请重新发起同步（服务器版本：" + document.getContentVersion() + "）");
            }
            if (!mindMap.getGraphVersion().equals(job.getBaseGraphVersion())) {
                throw ApiException.conflict("VERSION_CONFLICT",
                        "思维导图版本已变化（当前 " + mindMap.getGraphVersion()
                                + "，任务基于 " + job.getBaseGraphVersion() + "），请刷新后重试");
            }

            if (!claimedByConsumer) {
                // mq=false @Async 路径由本方法负责 PENDING→PARSING；MQ 路径保持消费者认领结果
                job.setStatus(SyncStatus.PARSING.name());
                syncJobRepository.save(job);
            }

            return new JobLoad(
                    job.getId(),
                    job.getDocumentId(),
                    job.getMindmapId(),
                    mindMap.getUserId(),
                    document.getTitle(),
                    document.getContent(),
                    job.getTargetContentVersion(),
                    job.getBaseGraphVersion(),
                    mindMap.getGraph(),
                    loadLatestBlocks(job.getDocumentId()));
        }
    }

    @Transactional
    public void updateStage(Long jobId, String stage) {
        SyncJobEntity job = getJob(jobId);
        synchronized (lockRegistry.lockFor(job.getDocumentId())) {
            if (SyncJobStatuses.TERMINAL.contains(job.getStatus())) {
                throw ApiException.conflict("INVALID_JOB_STATUS", "任务已结束：" + job.getStatus());
            }
            job.setStatus(stage);
            syncJobRepository.save(job);
        }
    }

    @Transactional
    public void finalizeReady(Long jobId,
                              List<ValidatedOperation> operations,
                              MindMapGraph targetGraph,
                              String changeSummary,
                              List<MarkdownBlock> newBlocks) {
        SyncJobEntity job = getJob(jobId);
        synchronized (lockRegistry.lockFor(job.getDocumentId())) {
            if (SyncStatus.CANCELLED.name().equals(job.getStatus())) {
                blockSnapshotRepository.deleteByDocumentIdAndDocumentVersion(
                        job.getDocumentId(), job.getTargetContentVersion());
                throw ApiException.conflict("JOB_CANCELLED", "同步任务已取消");
            }
            if (SyncStatus.FAILED.name().equals(job.getStatus())
                    || SyncStatus.COMPLETED.name().equals(job.getStatus())) {
                throw ApiException.conflict("INVALID_JOB_STATUS", "任务已结束：" + job.getStatus());
            }

            job.setValidatedOperations(writeJson(operations));
            job.setTargetGraph(graphJsonCodec.write(targetGraph));
            job.setChangeSummary(changeSummary);

            // READY 时把目标版本块快照落库（§10.3）；取消时由 cancel 清理
            replaceSnapshots(job.getDocumentId(), job.getTargetContentVersion(), newBlocks);

            job.setStatus(SyncStatus.READY.name());
            job.setReadyAt(Instant.now());
            syncJobRepository.save(job);
            // 幂等三件套之三：READY 终态与幂等记录同一事务；唯一冲突外抛由消费者按重复投递 ack。
            // finalizeReady 是唯一提交点：走到这里之前一行现有 graph 都没碰过（§5.3 不发半成品）。
            idempotentConsumptionRepository.saveAndFlush(
                    new IdempotentConsumptionEntity(
                            TaskType.MINDMAP_SYNC.wireName() + ":" + jobId));
        }
    }

    @Transactional
    public void markFailure(Long jobId, String code, String message) {
        SyncJobEntity job = getJob(jobId);
        synchronized (lockRegistry.lockFor(job.getDocumentId())) {
            if (SyncJobStatuses.TERMINAL.contains(job.getStatus())) {
                return;
            }
            markFailed(job, code, message);
            // 防御性清理：失败任务不留下目标版本快照
            blockSnapshotRepository.deleteByDocumentIdAndDocumentVersion(
                    job.getDocumentId(), job.getTargetContentVersion());
            // FAILED 终态与幂等记录同一事务写入；唯一冲突外抛由消费者按重复投递 ack。
            // 注意：CANCELLED 终态在用户取消事务中提交，无法与业务事务同写幂等行，
            // 其重复投递由消费者"条件认领 0 行直接 ack"防线覆盖（见 SyncJobMessageConsumer）。
            idempotentConsumptionRepository.saveAndFlush(
                    new IdempotentConsumptionEntity(
                            TaskType.MINDMAP_SYNC.wireName() + ":" + jobId));
        }
    }

    // ---------- 查询 / 动画 / 取消 ----------

    public SyncJobEntity getJob(Long jobId) {
        return syncJobRepository.findById(jobId)
                .orElseThrow(() -> ApiException.notFound("同步任务不存在：" + jobId));
    }

    public Optional<SyncJobEntity> findActiveJob(Long documentId) {
        return findActiveJobEntity(documentId);
    }

    @Transactional
    public SyncJobEntity animationStarted(Long jobId) {
        SyncJobEntity job = getJob(jobId);
        synchronized (lockRegistry.lockFor(job.getDocumentId())) {
            if (!SyncStatus.READY.name().equals(job.getStatus())) {
                throw ApiException.conflict("INVALID_JOB_STATUS",
                        "只有 READY 状态的任务可以开始播放动画，当前状态：" + job.getStatus());
            }
            job.setStatus(SyncStatus.ANIMATING.name());
            job.setAnimationStartedAt(Instant.now());
            SyncJobEntity saved = syncJobRepository.save(job);
            broadcaster.publish(jobId, "animating",
                    Map.of("jobId", idCodec.encode(jobId), "status", SyncStatus.ANIMATING.name()));
            return saved;
        }
    }

    @Transactional
    public CompleteJobResponse complete(Long jobId) {
        SyncJobEntity job = getJob(jobId);
        synchronized (lockRegistry.lockFor(job.getDocumentId())) {
            if (!SyncStatus.ANIMATING.name().equals(job.getStatus())) {
                throw ApiException.conflict("INVALID_JOB_STATUS",
                        "只有动画播放中的任务可以完成发布，当前状态：" + job.getStatus());
            }
            CompleteJobResponse response = publish(job);
            broadcaster.publish(jobId, "completed",
                    Map.of("jobId", idCodec.encode(jobId), "status", SyncStatus.COMPLETED.name(),
                            "graphVersion", response.graphVersion()));
            broadcaster.complete(jobId);
            return response;
        }
    }

    @Transactional
    public CompleteJobResponse skipAnimation(Long jobId) {
        SyncJobEntity job = getJob(jobId);
        synchronized (lockRegistry.lockFor(job.getDocumentId())) {
            String status = job.getStatus();
            boolean skippable = SyncStatus.READY.name().equals(status)
                    || SyncStatus.ANIMATING.name().equals(status);
            if (!skippable) {
                throw ApiException.conflict("INVALID_JOB_STATUS",
                        "当前状态不能跳过动画：" + status);
            }
            CompleteJobResponse response = publish(job);
            broadcaster.publish(jobId, "completed",
                    Map.of("jobId", idCodec.encode(jobId), "status", SyncStatus.COMPLETED.name(),
                            "graphVersion", response.graphVersion()));
            broadcaster.complete(jobId);
            return response;
        }
    }

    @Transactional
    public SyncJobEntity cancel(Long jobId) {
        SyncJobEntity job = getJob(jobId);
        synchronized (lockRegistry.lockFor(job.getDocumentId())) {
            String status = job.getStatus();
            boolean cancellable = List.of(
                    SyncStatus.PENDING.name(),
                    SyncStatus.PARSING.name(),
                    SyncStatus.DIFFING.name(),
                    SyncStatus.AI_GENERATING.name(),
                    SyncStatus.VALIDATING.name(),
                    SyncStatus.READY.name()
            ).contains(status);
            if (!cancellable) {
                throw ApiException.conflict("INVALID_JOB_STATUS",
                        "动画已经开始播放，不能取消，请等待播放完成或跳过动画。当前状态：" + status);
            }
            blockSnapshotRepository.deleteByDocumentIdAndDocumentVersion(
                    job.getDocumentId(), job.getTargetContentVersion());
            job.setStatus(SyncStatus.CANCELLED.name());
            SyncJobEntity saved = syncJobRepository.save(job);
            broadcaster.publish(jobId, "cancelled",
                    Map.of("jobId", idCodec.encode(jobId), "status", SyncStatus.CANCELLED.name()));
            broadcaster.complete(jobId);
            return saved;
        }
    }

    // ---------- 发布 ----------

    private CompleteJobResponse publish(SyncJobEntity job) {
        MindMapEntity mindMap = mindMapRepository.findById(job.getMindmapId())
                .orElseThrow(() -> ApiException.notFound("思维导图不存在"));
        if (!mindMap.getGraphVersion().equals(job.getBaseGraphVersion())) {
            throw ApiException.conflict("VERSION_CONFLICT",
                    "思维导图已被修改（当前图版本 " + mindMap.getGraphVersion()
                            + "，任务基于版本 " + job.getBaseGraphVersion() + "），请刷新后重试");
        }

        MindMapGraph targetGraph = graphJsonCodec.read(job.getTargetGraph());
        mindMap.setGraph(job.getTargetGraph());
        mindMap.setGraphVersion(targetGraph.getVersion());
        mindMap.setLastPublishedJobId(job.getId());
        mindMap.setUpdatedAt(Instant.now());
        mindMapRepository.save(mindMap);

        vectorIndexTaskService.enqueueMindmap(mindMap.getUserId(), mindMap.getId());

        job.setStatus(SyncStatus.COMPLETED.name());
        job.setCompletedAt(Instant.now());
        syncJobRepository.save(job);

        return new CompleteJobResponse(idCodec.encode(job.getId()), SyncStatus.COMPLETED.name(),
                targetGraph.getVersion(), targetGraph);
    }

    private SyncJobEntity markFailed(SyncJobEntity job, String code, String message) {
        job.setStatus(SyncStatus.FAILED.name());
        job.setErrorCode(code);
        job.setErrorMessage(message);
        job.setFailedAt(Instant.now());
        return syncJobRepository.save(job);
    }

    private Optional<SyncJobEntity> findActiveJobEntity(Long documentId) {
        return syncJobRepository
                .findByDocumentIdAndStatusInOrderByIdDesc(documentId, SyncStatus.ACTIVE)
                .stream()
                .findFirst();
    }

    private List<MarkdownBlock> loadLatestBlocks(Long documentId) {
        Integer latestVersion = blockSnapshotRepository.findLatestVersion(documentId);
        if (latestVersion == null) {
            return List.of();
        }
        List<BlockSnapshotEntity> snapshots =
                blockSnapshotRepository.findByDocumentIdAndDocumentVersionOrderByBlockOrderAsc(
                        documentId, latestVersion);
        List<MarkdownBlock> blocks = new ArrayList<>();
        for (BlockSnapshotEntity snapshot : snapshots) {
            List<String> headingPath = readStringList(snapshot.getHeadingPath());
            blocks.add(new MarkdownBlock(
                    snapshot.getBlockId(),
                    BlockType.valueOf(snapshot.getBlockType()),
                    snapshot.getNormalizedText(),
                    snapshot.getNormalizedText(),
                    headingPath,
                    null,
                    snapshot.getBlockOrder(),
                    snapshot.getAnchor(),
                    snapshot.getContentHash()));
        }
        return blocks;
    }

    private void replaceSnapshots(Long documentId, Integer documentVersion,
                                  List<MarkdownBlock> newBlocks) {
        blockSnapshotRepository.deleteByDocumentIdAndDocumentVersion(documentId, documentVersion);
        blockSnapshotRepository.flush();
        List<BlockSnapshotEntity> entities = new ArrayList<>();
        for (MarkdownBlock block : newBlocks) {
            BlockSnapshotEntity entity = new BlockSnapshotEntity();
            entity.setDocumentId(documentId);
            entity.setDocumentVersion(documentVersion);
            entity.setBlockId(block.blockId());
            entity.setBlockType(block.type().name());
            entity.setAnchor(block.anchor());
            entity.setHeadingPath(writeJson(block.headingPath()));
            entity.setNormalizedText(block.normalizedText());
            entity.setContentHash(block.contentHash());
            entity.setBlockOrder(block.order());
            entities.add(entity);
        }
        blockSnapshotRepository.saveAll(entities);
    }

    private String buildChangeSummary(MarkdownDiff diff, SyncEngine.AiCallMeta aiMeta) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("added", diff.added().stream().map(MarkdownBlock::anchor).toList());
        summary.put("changed", diff.changed().stream()
                .map(pair -> Map.of("from", pair.oldBlock().anchor(), "to", pair.newBlock().anchor()))
                .toList());
        summary.put("removed", diff.removed().stream().map(MarkdownBlock::anchor).toList());
        // 【v1.1 修订·方案 A】搬家块文字未变，计入 unchanged；不新增 JSON 键，保持 §11 契约
        summary.put("unchanged", diff.unchanged().size() + diff.relocated().size());
        summary.put("uncertain", diff.uncertain().stream()
                .map(pair -> Map.of("from", pair.oldBlock().anchor(), "to", pair.newBlock().anchor()))
                .toList());
        if (aiMeta != null) {
            // §16.4 成本记录：模型、耗时、Token；放进已有 JSONB 字段，不改表结构
            Map<String, Object> ai = new LinkedHashMap<>();
            ai.put("provider", aiMeta.provider());
            ai.put("model", aiMeta.model());
            ai.put("elapsedMillis", aiMeta.elapsedMillis());
            ai.put("promptTokens", aiMeta.usage().promptTokens());
            ai.put("completionTokens", aiMeta.usage().completionTokens());
            ai.put("totalTokens", aiMeta.usage().totalTokens());
            summary.put("ai", ai);
        }
        return writeJson(summary);
    }

    private static String safeMessage(Exception e) {
        String message = e.getMessage();
        return message == null ? e.getClass().getSimpleName() : message;
    }

    private List<String> readStringList(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {
            });
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("JSON 序列化失败", e);
        }
    }

    /** 后台执行需要的只读快照（事务结束后使用拆解后的值）。 */
    public record JobLoad(
            Long jobId,
            Long documentId,
            Long mindmapId,
            Long userId,
            String title,
            String content,
            Integer targetContentVersion,
            Integer baseGraphVersion,
            String graphJson,
            List<MarkdownBlock> oldBlocks
    ) {
    }

    /** 状态集合小工具。 */
    private static final class SyncJobStatuses {
        private static final List<String> TERMINAL =
                List.of(SyncStatus.READY.name(), SyncStatus.COMPLETED.name(),
                        SyncStatus.FAILED.name(), SyncStatus.CANCELLED.name(),
                        SyncStatus.ANIMATING.name());
    }
}
