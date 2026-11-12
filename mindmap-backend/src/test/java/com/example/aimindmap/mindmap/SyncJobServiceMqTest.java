package com.example.aimindmap.mindmap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.aimindmap.ai.AiGatewayException;
import com.example.aimindmap.auth.ExternalIdCodec;
import com.example.aimindmap.common.error.ApiException;
import com.example.aimindmap.document.DocumentEntity;
import com.example.aimindmap.document.DocumentRepository;
import com.example.aimindmap.metrics.BusinessMetrics;
import com.example.aimindmap.mqsupport.RetriableTaskException;
import com.example.aimindmap.mqsupport.TaskMessage;
import com.example.aimindmap.mqsupport.TaskType;
import com.example.aimindmap.mqsupport.outbox.IdempotentConsumptionEntity;
import com.example.aimindmap.mqsupport.outbox.IdempotentConsumptionRepository;
import com.example.aimindmap.mqsupport.outbox.OutboxEventRepository;
import com.example.aimindmap.mqsupport.outbox.OutboxEventService;
import com.example.aimindmap.mqsupport.outbox.OutboxEventStatus;
import com.example.aimindmap.mindmap.SyncJobDtos.CreateSyncJobRequest;
import com.example.aimindmap.mindmap.graph.MindMapGraph;
import com.example.aimindmap.mindmap.graph.MindMapNode;
import com.example.aimindmap.parser.MarkdownDiff;
import com.example.aimindmap.progress.ProgressBroadcaster;
import com.example.aimindmap.vectorindex.VectorIndexTaskService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 阶段六-1：SyncJobService 的 MQ 改造单测（纯 Mockito，无真实 DB/MQ/LLM）。
 * 历史 processJob 无单测（测试树只有 SyncEngineTest），本类只覆盖本次新增/改动的分支。
 */
class SyncJobServiceMqTest {

    private static final long JOB_ID = 10L;
    private static final long DOCUMENT_ID = 888001L;
    private static final long MINDMAP_ID = 777001L;
    private static final long USER_ID = 888500L;

    private SyncJobRepository syncJobRepository;
    private MindMapRepository mindMapRepository;
    private DocumentRepository documentRepository;
    private BlockSnapshotRepository blockSnapshotRepository;
    private SyncEngine syncEngine;
    private GraphJsonCodec graphJsonCodec;
    private ApplicationEventPublisher eventPublisher;
    private ProgressBroadcaster broadcaster;
    private SyncJobResponseMapper responseMapper;
    private ExternalIdCodec idCodec;
    private VectorIndexTaskService vectorIndexTaskService;
    private OutboxEventService outboxEventService;
    private OutboxEventRepository outboxEventRepository;
    private IdempotentConsumptionRepository idempotentConsumptionRepository;

    private final ObjectMapper objectMapper = new ObjectMapper()
            .findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    /** 阶段八-1：同步 RT 指标断言用真实 SimpleMeterRegistry（不 mock 指标组件）。 */
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final BusinessMetrics businessMetrics = new BusinessMetrics(meterRegistry);

    private SyncJobEntity job;
    private DocumentEntity document;
    private MindMapEntity mindMap;
    private MindMapGraph graph;

    @BeforeEach
    void setUp() {
        syncJobRepository = Mockito.mock(SyncJobRepository.class);
        mindMapRepository = Mockito.mock(MindMapRepository.class);
        documentRepository = Mockito.mock(DocumentRepository.class);
        blockSnapshotRepository = Mockito.mock(BlockSnapshotRepository.class);
        syncEngine = Mockito.mock(SyncEngine.class);
        graphJsonCodec = Mockito.mock(GraphJsonCodec.class);
        eventPublisher = Mockito.mock(ApplicationEventPublisher.class);
        broadcaster = Mockito.mock(ProgressBroadcaster.class);
        responseMapper = Mockito.mock(SyncJobResponseMapper.class);
        idCodec = Mockito.mock(ExternalIdCodec.class);
        vectorIndexTaskService = Mockito.mock(VectorIndexTaskService.class);
        outboxEventService = Mockito.mock(OutboxEventService.class);
        outboxEventRepository = Mockito.mock(OutboxEventRepository.class);
        idempotentConsumptionRepository = Mockito.mock(IdempotentConsumptionRepository.class);
        when(idCodec.encode(anyLong())).thenReturn("ext");

        job = new SyncJobEntity();
        ReflectionTestUtils.setField(job, "id", JOB_ID);
        job.setDocumentId(DOCUMENT_ID);
        job.setMindmapId(MINDMAP_ID);
        job.setStatus(SyncStatus.PENDING.name());
        job.setTargetContentVersion(1);
        job.setBaseGraphVersion(1);

        document = new DocumentEntity();
        document.setTitle("标题");
        document.setContent("# 标题\n正文");
        document.setContentVersion(1);

        mindMap = new MindMapEntity();
        mindMap.setDocumentId(DOCUMENT_ID);
        mindMap.setUserId(USER_ID);
        mindMap.setGraphVersion(1);
        mindMap.setGraph("{\"rootId\":\"root\",\"version\":1}");

        graph = new MindMapGraph("root", 1,
                new MindMapNode("root", "标题", null, "markdown", null, null, List.of(), Instant.now()),
                Instant.now());

        when(syncJobRepository.findById(JOB_ID)).thenReturn(Optional.of(job));
        when(documentRepository.findById(DOCUMENT_ID)).thenReturn(Optional.of(document));
        when(mindMapRepository.findById(MINDMAP_ID)).thenReturn(Optional.of(mindMap));
        when(blockSnapshotRepository.findLatestVersion(DOCUMENT_ID)).thenReturn(null);
        when(graphJsonCodec.read(any())).thenReturn(graph);
        when(graphJsonCodec.write(any())).thenReturn("{}");
    }

    private SyncJobService newService(boolean mqEnabled) {
        DocumentLockRegistry lockRegistry = new DocumentLockRegistry();
        SyncJobService service = new SyncJobService(
                syncJobRepository, mindMapRepository, documentRepository, blockSnapshotRepository,
                syncEngine, graphJsonCodec, lockRegistry, objectMapper, eventPublisher, broadcaster,
                responseMapper, null, idCodec, vectorIndexTaskService, outboxEventService,
                outboxEventRepository, idempotentConsumptionRepository, mqEnabled, businessMetrics);
        // self 自注入：构造器无法自引用，反射回填本实例（单测无 Spring 代理，@Transactional 不影响分支）
        try {
            Field selfField = SyncJobService.class.getDeclaredField("self");
            selfField.setAccessible(true);
            selfField.set(service, service);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return service;
    }

    private SyncEngine.SyncPreview successPreview() {
        MarkdownDiff emptyDiff = new MarkdownDiff(
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        return new SyncEngine.SyncPreview(List.of(), graph, emptyDiff, List.of(), null);
    }

    // ---------- 正常流 / loadForProcessing 两路径状态兼容 ----------

    @Test
    void pendingPathReachesReadyAndWritesIdempotentRow() {
        when(syncEngine.prepare(any(), any(), any(), any(), any())).thenReturn(successPreview());
        SyncJobService service = newService(true);

        SyncJobService.ProcessOutcome outcome = service.processJob(JOB_ID);

        assertThat(outcome).isEqualTo(SyncJobService.ProcessOutcome.READY);
        assertThat(job.getStatus()).isEqualTo(SyncStatus.READY.name());
        ArgumentCaptor<IdempotentConsumptionEntity> captor =
                ArgumentCaptor.forClass(IdempotentConsumptionEntity.class);
        verify(idempotentConsumptionRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getMessageId()).isEqualTo("MINDMAP_SYNC:" + JOB_ID);
        verify(broadcaster).publish(eq(JOB_ID), eq("ready"), any());
        verify(broadcaster).complete(JOB_ID);
        verify(broadcaster, never()).publish(eq(JOB_ID), eq("failed"), any());
    }

    @Test
    void parsingAlreadyClaimedPathAlsoReachesReady() {
        // MQ 消费者已 CAS 置 PARSING：loadForProcessing 必须放行且不再要求 PENDING
        job.setStatus(SyncStatus.PARSING.name());
        when(syncEngine.prepare(any(), any(), any(), any(), any())).thenReturn(successPreview());
        SyncJobService service = newService(true);

        SyncJobService.ProcessOutcome outcome = service.processJob(JOB_ID);

        assertThat(outcome).isEqualTo(SyncJobService.ProcessOutcome.READY);
        assertThat(job.getStatus()).isEqualTo(SyncStatus.READY.name());
    }

    // ---------- 阶段八-1：同步 RT 埋点（325 行第 ③ 项） ----------

    @Test
    void processJobRecordsSyncTimerWithReadyOutcome() {
        when(syncEngine.prepare(any(), any(), any(), any(), any())).thenReturn(successPreview());
        SyncJobService service = newService(true);

        SyncJobService.ProcessOutcome outcome = service.processJob(JOB_ID);

        assertThat(outcome).isEqualTo(SyncJobService.ProcessOutcome.READY);
        assertThat(meterRegistry.timer("aimindmap.sync.job", "outcome", "ready").count()).isEqualTo(1L);
    }

    @Test
    void processJobRecordsExceptionOutcomeWhenRetriableFailureBubblesUp() {
        when(syncEngine.prepare(any(), any(), any(), any(), any()))
                .thenThrow(new AiGatewayException("AI_TIMEOUT", "transient AI_TIMEOUT"));
        SyncJobService service = newService(true);

        assertThatThrownBy(() -> service.processJob(JOB_ID))
                .isInstanceOf(RetriableTaskException.class);

        // try/finally 埋点：异常路径也要记一次耗时，outcome=exception，且异常照常外抛
        assertThat(meterRegistry.timer("aimindmap.sync.job", "outcome", "exception").count()).isEqualTo(1L);
        assertThat(meterRegistry.find("aimindmap.sync.job").tag("outcome", "ready").timer()).isNull();
    }

    // ---------- AI 异常分级 ----------

    @Test
    void retriableAiCodesBubbleUpWithoutTerminalState() {
        for (String code : List.of("AI_TIMEOUT", "AI_HTTP_429", "AI_HTTP_503")) {
            SyncJobEntity freshJob = freshPendingJob();
            when(syncEngine.prepare(any(), any(), any(), any(), any()))
                    .thenThrow(new AiGatewayException(code, "transient " + code));
            SyncJobService service = newService(true);

            assertThatThrownBy(() -> service.processJob(JOB_ID))
                    .isInstanceOf(RetriableTaskException.class)
                    .hasMessageContaining(code)
                    .hasCauseInstanceOf(AiGatewayException.class);

            assertThat(freshJob.getStatus()).isEqualTo(SyncStatus.PARSING.name());
            verify(idempotentConsumptionRepository, never()).saveAndFlush(any());
            verify(broadcaster, never()).publish(eq(JOB_ID), eq("failed"), any());
            Mockito.reset(syncEngine, idempotentConsumptionRepository, broadcaster);
        }
    }

    @Test
    void configurationAiCodesFailConfigurationAndWriteFailedTerminal() {
        for (String code : List.of("AI_NOT_CONFIGURED", "AI_HTTP_401")) {
            freshPendingJob();
            when(syncEngine.prepare(any(), any(), any(), any(), any()))
                    .thenThrow(new AiGatewayException(code, "config " + code));
            SyncJobService service = newService(true);

            SyncJobService.ProcessOutcome outcome = service.processJob(JOB_ID);

            assertThat(outcome).isEqualTo(SyncJobService.ProcessOutcome.FAILED_CONFIGURATION);
            assertThat(job.getStatus()).isEqualTo(SyncStatus.FAILED.name());
            assertThat(job.getErrorCode()).isEqualTo(code);
            verify(idempotentConsumptionRepository).saveAndFlush(any(IdempotentConsumptionEntity.class));
            verify(broadcaster).publish(eq(JOB_ID), eq("failed"), any());
            verify(broadcaster).complete(JOB_ID);
            setUp();
        }
    }

    @Test
    void businessAiCodesFailBusiness() {
        for (String code : List.of("AI_INPUT_TOO_LARGE", "AI_RESPONSE_INVALID", "AI_JSON_PARSE_FAILED")) {
            freshPendingJob();
            when(syncEngine.prepare(any(), any(), any(), any(), any()))
                    .thenThrow(new AiGatewayException(code, "business " + code));
            SyncJobService service = newService(true);

            SyncJobService.ProcessOutcome outcome = service.processJob(JOB_ID);

            assertThat(outcome).isEqualTo(SyncJobService.ProcessOutcome.FAILED_BUSINESS);
            assertThat(job.getStatus()).isEqualTo(SyncStatus.FAILED.name());
            assertThat(job.getErrorCode()).isEqualTo(code);
            setUp();
        }
    }

    @Test
    void plainApiExceptionInPrepareFailsBusiness() {
        when(syncEngine.prepare(any(), any(), any(), any(), any()))
                .thenThrow(ApiException.badRequest("AI_REQUEST_INVALID", "请求非法"));
        SyncJobService service = newService(true);

        SyncJobService.ProcessOutcome outcome = service.processJob(JOB_ID);

        assertThat(outcome).isEqualTo(SyncJobService.ProcessOutcome.FAILED_BUSINESS);
        assertThat(job.getStatus()).isEqualTo(SyncStatus.FAILED.name());
        assertThat(job.getErrorCode()).isEqualTo("AI_REQUEST_INVALID");
    }

    @Test
    void unexpectedExceptionInPrepareKeepsSyncFailedBusinessSemantics() {
        when(syncEngine.prepare(any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("boom"));
        SyncJobService service = newService(true);

        SyncJobService.ProcessOutcome outcome = service.processJob(JOB_ID);

        assertThat(outcome).isEqualTo(SyncJobService.ProcessOutcome.FAILED_BUSINESS);
        assertThat(job.getStatus()).isEqualTo(SyncStatus.FAILED.name());
        assertThat(job.getErrorCode()).isEqualTo("SYNC_FAILED");
    }

    // ---------- loadForProcessing / finalizeReady 的数据库与取消分支 ----------

    @Test
    void dataAccessExceptionWhileLoadingIsRetriableWithoutFailedTerminal() {
        when(documentRepository.findById(DOCUMENT_ID))
                .thenThrow(new DataAccessResourceFailureException("db down"));
        SyncJobService service = newService(true);

        assertThatThrownBy(() -> service.processJob(JOB_ID))
                .isInstanceOf(RetriableTaskException.class);
        assertThat(job.getStatus()).isEqualTo(SyncStatus.PENDING.name());
        verify(idempotentConsumptionRepository, never()).saveAndFlush(any());
    }

    @Test
    void versionConflictOnLoadFailsBusiness() {
        document.setContentVersion(2);
        SyncJobService service = newService(true);

        SyncJobService.ProcessOutcome outcome = service.processJob(JOB_ID);

        assertThat(outcome).isEqualTo(SyncJobService.ProcessOutcome.FAILED_BUSINESS);
        assertThat(job.getStatus()).isEqualTo(SyncStatus.FAILED.name());
        assertThat(job.getErrorCode()).isEqualTo("VERSION_CONFLICT");
    }

    @Test
    void readyJobOnLoadIsEarlyExitWithoutFailure() {
        job.setStatus(SyncStatus.READY.name());
        SyncJobService service = newService(true);

        SyncJobService.ProcessOutcome outcome = service.processJob(JOB_ID);

        assertThat(outcome).isEqualTo(SyncJobService.ProcessOutcome.EARLY_EXIT);
        assertThat(job.getStatus()).isEqualTo(SyncStatus.READY.name());
        verify(syncEngine, never()).prepare(any(), any(), any(), any(), any());
        verify(broadcaster, never()).publish(eq(JOB_ID), eq("failed"), any());
        verify(broadcaster).complete(JOB_ID);
    }

    @Test
    void cancelledDuringAiIsEarlyExitWithCancelledPush() {
        when(syncEngine.prepare(any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            job.setStatus(SyncStatus.CANCELLED.name());
            return successPreview();
        });
        SyncJobService service = newService(true);

        SyncJobService.ProcessOutcome outcome = service.processJob(JOB_ID);

        assertThat(outcome).isEqualTo(SyncJobService.ProcessOutcome.EARLY_EXIT);
        assertThat(job.getStatus()).isEqualTo(SyncStatus.CANCELLED.name());
        verify(broadcaster).publish(eq(JOB_ID), eq("cancelled"), any());
        verify(broadcaster, never()).publish(eq(JOB_ID), eq("failed"), any());
    }

    @Test
    void dataAccessExceptionWhileFinalizingIsRetriableWithoutFailedTerminal() {
        when(syncEngine.prepare(any(), any(), any(), any(), any())).thenReturn(successPreview());
        Mockito.doThrow(new DataAccessResourceFailureException("db down"))
                .when(blockSnapshotRepository)
                .deleteByDocumentIdAndDocumentVersion(DOCUMENT_ID, 1);
        SyncJobService service = newService(true);

        assertThatThrownBy(() -> service.processJob(JOB_ID))
                .isInstanceOf(RetriableTaskException.class);
        // Mockito mock 的 SyncEngine 不触发阶段回调，状态停在 PARSING；关键断言是未落 FAILED
        assertThat(job.getStatus()).isEqualTo(SyncStatus.PARSING.name());
        assertThat(job.getErrorCode()).isNull();
        verify(idempotentConsumptionRepository, never()).saveAndFlush(any());
    }

    // ---------- 发布点两模式 ----------

    @Test
    void mqEnabledCreateJobWritesOutboxAndPublishesNoSpringEvent() {
        when(mindMapRepository.findByDocumentId(DOCUMENT_ID)).thenReturn(Optional.of(mindMap));
        when(syncJobRepository.findByDocumentIdAndStatusInOrderByIdDesc(eq(DOCUMENT_ID), any()))
                .thenReturn(List.of());
        when(syncJobRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            SyncJobEntity saved = invocation.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", JOB_ID);
            return saved;
        });
        SyncJobService service = newService(true);

        SyncJobEntity saved =
                service.createSyncJob(DOCUMENT_ID, new CreateSyncJobRequest(1, "incremental"));

        assertThat(saved.getId()).isEqualTo(JOB_ID);
        ArgumentCaptor<TaskMessage> captor = ArgumentCaptor.forClass(TaskMessage.class);
        verify(outboxEventService).recordPending(captor.capture());
        TaskMessage message = captor.getValue();
        assertThat(message.taskType()).isEqualTo(TaskType.MINDMAP_SYNC);
        assertThat(message.taskId()).isEqualTo(JOB_ID);
        assertThat(message.entityId()).isEqualTo(String.valueOf(DOCUMENT_ID));
        assertThat(message.op()).isEqualTo("SYNC");
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void mqDisabledCreateJobKeepsAfterCommitEventBehaviorAndWritesNoOutbox() {
        when(mindMapRepository.findByDocumentId(DOCUMENT_ID)).thenReturn(Optional.of(mindMap));
        when(syncJobRepository.findByDocumentIdAndStatusInOrderByIdDesc(eq(DOCUMENT_ID), any()))
                .thenReturn(List.of());
        when(syncJobRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            SyncJobEntity saved = invocation.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", JOB_ID);
            return saved;
        });
        SyncJobService service = newService(false);

        service.createSyncJob(DOCUMENT_ID, new CreateSyncJobRequest(1, "incremental"));

        verify(outboxEventService, never()).recordPending(any());
        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue()).isInstanceOf(SyncJobRequested.class);
        assertThat(((SyncJobRequested) eventCaptor.getValue()).jobId()).isEqualTo(JOB_ID);
    }

    // ---------- 条件认领 / 重试复位（SQL 语义另由 IT 覆盖） ----------

    @Test
    void claimForMessageDelegatesToAtomicUpdate() {
        when(syncJobRepository.claimForConsumer(JOB_ID)).thenReturn(1);
        SyncJobService service = newService(true);
        assertThat(service.claimForMessage(JOB_ID)).isTrue();

        when(syncJobRepository.claimForConsumer(99L)).thenReturn(0);
        assertThat(service.claimForMessage(99L)).isFalse();
    }

    @Test
    void jobExistsDelegatesToRepository() {
        when(syncJobRepository.existsById(JOB_ID)).thenReturn(true);
        SyncJobService service = newService(true);
        assertThat(service.jobExists(JOB_ID)).isTrue();
    }

    @Test
    void requeueForMessageRetryDelegatesToAtomicUpdate() {
        when(syncJobRepository.requeueForRetry(JOB_ID)).thenReturn(1);
        SyncJobService service = newService(true);
        assertThat(service.requeueForMessageRetry(JOB_ID)).isTrue();

        when(syncJobRepository.requeueForRetry(99L)).thenReturn(0);
        assertThat(service.requeueForMessageRetry(99L)).isFalse();
    }

    // ---------- 对账 ----------

    @Test
    void reconcileResetsStaleProcessingAndReissuesOutboxForBothLists() {
        SyncJobEntity stalePending = staleJob(21L, SyncStatus.PENDING.name());
        SyncJobEntity staleGenerating = staleJob(22L, SyncStatus.AI_GENERATING.name());
        when(syncJobRepository.findStalePending(any(), anyInt())).thenReturn(List.of(stalePending));
        when(syncJobRepository.findStaleProcessing(any(), anyInt())).thenReturn(List.of(staleGenerating));
        when(outboxEventRepository.existsByTaskTypeAndTaskIdAndStatus(
                eq(TaskType.MINDMAP_SYNC.wireName()), anyLong(), eq(OutboxEventStatus.PENDING)))
                .thenReturn(false);
        SyncJobService service = newService(true);

        int recovered = service.reconcileStaleJobs();

        assertThat(recovered).isEqualTo(2);
        assertThat(staleGenerating.getStatus()).isEqualTo(SyncStatus.PENDING.name());
        ArgumentCaptor<TaskMessage> captor = ArgumentCaptor.forClass(TaskMessage.class);
        verify(outboxEventService, Mockito.times(2)).recordPending(captor.capture());
        assertThat(captor.getAllValues()).extracting(TaskMessage::taskId).containsExactly(21L, 22L);
    }

    @Test
    void reconcileSkipsJobsThatAlreadyHavePendingOutbox() {
        SyncJobEntity stalePending = staleJob(23L, SyncStatus.PENDING.name());
        when(syncJobRepository.findStalePending(any(), anyInt())).thenReturn(List.of(stalePending));
        when(syncJobRepository.findStaleProcessing(any(), anyInt())).thenReturn(List.of());
        when(outboxEventRepository.existsByTaskTypeAndTaskIdAndStatus(
                TaskType.MINDMAP_SYNC.wireName(), 23L, OutboxEventStatus.PENDING)).thenReturn(true);
        SyncJobService service = newService(true);

        assertThat(service.reconcileStaleJobs()).isZero();
        verify(outboxEventService, never()).recordPending(any());
    }

    // ---------- helpers ----------

    private SyncJobEntity freshPendingJob() {
        job.setStatus(SyncStatus.PENDING.name());
        job.setErrorCode(null);
        job.setErrorMessage(null);
        return job;
    }

    private SyncJobEntity staleJob(long id, String status) {
        SyncJobEntity stale = new SyncJobEntity();
        ReflectionTestUtils.setField(stale, "id", id);
        stale.setDocumentId(DOCUMENT_ID);
        stale.setMindmapId(MINDMAP_ID);
        stale.setStatus(status);
        stale.setTargetContentVersion(1);
        stale.setBaseGraphVersion(1);
        return stale;
    }
}
