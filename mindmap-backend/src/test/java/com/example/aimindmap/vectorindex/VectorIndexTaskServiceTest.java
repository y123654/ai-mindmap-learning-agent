package com.example.aimindmap.vectorindex;

import com.example.aimindmap.agentmemory.AgentMemoryRepository;
import com.example.aimindmap.agentmemory.MemoryVectorService;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.aimindmap.document.DocumentEntity;
import com.example.aimindmap.document.DocumentRepository;
import com.example.aimindmap.mindmap.GraphJsonCodec;
import com.example.aimindmap.mindmap.MindMapEntity;
import com.example.aimindmap.mindmap.MindMapRepository;
import com.example.aimindmap.mindmap.graph.MindMapGraph;
import com.example.aimindmap.mindmap.graph.MindMapNode;
import com.example.aimindmap.mqsupport.TaskMessage;
import com.example.aimindmap.mqsupport.TaskType;
import com.example.aimindmap.mqsupport.outbox.IdempotentConsumptionEntity;
import com.example.aimindmap.mqsupport.outbox.IdempotentConsumptionRepository;
import com.example.aimindmap.mqsupport.outbox.OutboxEventRepository;
import com.example.aimindmap.mqsupport.outbox.OutboxEventService;
import com.example.aimindmap.mqsupport.outbox.OutboxEventStatus;
import com.example.aimindmap.question.QuestionRepository;
import com.example.aimindmap.question.QuestionEntity;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;
import org.mockito.ArgumentCaptor;

class VectorIndexTaskServiceTest {

    private final VectorIndexTaskRepository taskRepository =
            org.mockito.Mockito.mock(VectorIndexTaskRepository.class);
    private final VectorIndexEntryRepository entryRepository =
            org.mockito.Mockito.mock(VectorIndexEntryRepository.class);
    private final DocumentRepository documentRepository =
            org.mockito.Mockito.mock(DocumentRepository.class);
    private final MindMapRepository mindMapRepository =
            org.mockito.Mockito.mock(MindMapRepository.class);
    private final QuestionRepository questionRepository =
            org.mockito.Mockito.mock(QuestionRepository.class);
    private final GraphJsonCodec graphJsonCodec = org.mockito.Mockito.mock(GraphJsonCodec.class);
    private final QdrantProperties properties = new QdrantProperties();
    private final MemoryVectorService memoryVectorService =
            org.mockito.Mockito.mock(MemoryVectorService.class);
    private final AgentMemoryRepository agentMemoryRepository =
            org.mockito.Mockito.mock(AgentMemoryRepository.class);
    private final OutboxEventService outboxEventService =
            org.mockito.Mockito.mock(OutboxEventService.class);
    private final OutboxEventRepository outboxEventRepository =
            org.mockito.Mockito.mock(OutboxEventRepository.class);
    private final IdempotentConsumptionRepository idempotentConsumptionRepository =
            org.mockito.Mockito.mock(IdempotentConsumptionRepository.class);

    private final VectorIndexTaskService service = new VectorIndexTaskService(
            taskRepository, entryRepository, documentRepository, mindMapRepository,
            questionRepository, graphJsonCodec, properties,
            memoryVectorService, agentMemoryRepository,
            outboxEventService, outboxEventRepository, idempotentConsumptionRepository);

    @Test
    void enqueueDocumentUpsertsCurrentChunksAndDeletesStaleEntries() {
        DocumentEntity document = new DocumentEntity();
        document.setUserId(1L);
        document.setStatus("active");
        String unit = "进程是资源分配的基本单位，线程是调度单位。";
        document.setContent(unit.repeat(80));
        when(documentRepository.findById(3L)).thenReturn(Optional.of(document));
        VectorIndexEntryEntity staleEntry = new VectorIndexEntryEntity();
        staleEntry.setChunkIndex(99);
        when(entryRepository.findByResourceTypeAndResourceId(
                VectorResourceType.DOCUMENT_CHUNK.wireName(), "3"))
                .thenReturn(List.of(staleEntry));
        when(taskRepository.existsByResourceTypeAndResourceIdAndChunkIndexAndOperationAndStatus(
                any(), any(), anyInt(), any(), any())).thenReturn(false);

        int enqueued = service.enqueueDocument(1L, 3L);

        ArgumentCaptor<VectorIndexTaskEntity> captor =
                ArgumentCaptor.forClass(VectorIndexTaskEntity.class);
        verify(taskRepository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        List<VectorIndexTaskEntity> tasks = captor.getAllValues();
        long upserts = tasks.stream().filter(t -> t.getOperation().equals("UPSERT")).count();
        long deletes = tasks.stream().filter(t -> t.getOperation().equals("DELETE")).count();
        assertThat(upserts).isGreaterThan(2);
        assertThat(deletes).isOne();
        assertThat(enqueued).isEqualTo(tasks.size());
        assertThat(tasks).allSatisfy(task -> {
            assertThat(task.getUserId()).isEqualTo(1L);
            assertThat(task.getStatus()).isEqualTo("PENDING");
        });
    }

    @Test
    void pendingTaskIsNotEnqueuedAgain() {
        DocumentEntity document = new DocumentEntity();
        document.setUserId(1L);
        document.setStatus("active");
        document.setContent("简单内容");
        when(documentRepository.findById(3L)).thenReturn(Optional.of(document));
        when(entryRepository.findByResourceTypeAndResourceId(any(), any())).thenReturn(List.of());
        when(taskRepository.existsByResourceTypeAndResourceIdAndChunkIndexAndOperationAndStatus(
                any(), any(), anyInt(), any(), any())).thenReturn(true);

        int enqueued = service.enqueueDocument(1L, 3L);

        assertThat(enqueued).isZero();
        verify(taskRepository, never()).save(any(VectorIndexTaskEntity.class));
    }

    @Test
    void enqueueDocumentDeletionIssuesDeletePerChunkAndEntry() {
        DocumentEntity document = new DocumentEntity();
        document.setUserId(1L);
        document.setStatus("deleted");
        String unit = "进程是资源分配的基本单位，线程是调度单位。";
        document.setContent(unit.repeat(20));
        when(documentRepository.findById(3L)).thenReturn(Optional.of(document));
        VectorIndexEntryEntity entry = new VectorIndexEntryEntity();
        entry.setChunkIndex(77);
        when(entryRepository.findByResourceTypeAndResourceId(any(), any()))
                .thenReturn(List.of(entry));
        when(taskRepository.existsByResourceTypeAndResourceIdAndChunkIndexAndOperationAndStatus(
                any(), any(), anyInt(), any(), any())).thenReturn(false);

        service.enqueueDocumentDeletion(1L, 3L);

        ArgumentCaptor<VectorIndexTaskEntity> captor =
                ArgumentCaptor.forClass(VectorIndexTaskEntity.class);
        verify(taskRepository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        assertThat(captor.getAllValues())
                .allSatisfy(task -> assertThat(task.getOperation()).isEqualTo("DELETE"));
    }

    @Test
    void claimPendingMovesToProcessingAndIncrementsAttempts() {
        VectorIndexTaskEntity entity = new VectorIndexTaskEntity();
        entity.setResourceType(VectorResourceType.DOCUMENT_CHUNK.wireName());
        entity.setOperation(VectorTaskOperation.UPSERT.name());
        entity.setStatus("PENDING");
        entity.setAttempts(0);
        when(taskRepository.claimPending(anyInt())).thenReturn(List.of(entity));

        List<VectorIndexTaskService.TaskLoad> claimed = service.claimBatch();

        assertThat(claimed).hasSize(1);
        assertThat(entity.getStatus()).isEqualTo("PROCESSING");
        assertThat(entity.getAttempts()).isOne();
        verify(taskRepository).claimPending(anyInt());
        verify(taskRepository, never()).claimRetriableFailed(anyInt(), anyInt());
    }

    @Test
    void claimFallsBackToRetriableFailed() {
        VectorIndexTaskEntity entity = new VectorIndexTaskEntity();
        entity.setResourceType(VectorResourceType.DOCUMENT_CHUNK.wireName());
        entity.setOperation(VectorTaskOperation.UPSERT.name());
        entity.setStatus("FAILED");
        entity.setAttempts(2);
        when(taskRepository.claimPending(anyInt())).thenReturn(List.of());
        when(taskRepository.claimRetriableFailed(anyInt(), anyInt())).thenReturn(List.of(entity));

        List<VectorIndexTaskService.TaskLoad> claimed = service.claimBatch();

        assertThat(claimed).hasSize(1);
        assertThat(entity.getStatus()).isEqualTo("PROCESSING");
        assertThat(entity.getAttempts()).isEqualTo(3);
    }

    @Test
    void markFailedUpdatesTaskAndEntryWithError() {
        VectorIndexTaskEntity task = new VectorIndexTaskEntity();
        when(taskRepository.findById(9L)).thenReturn(Optional.of(task));
        VectorIndexEntryEntity entry = new VectorIndexEntryEntity();
        when(entryRepository.findByResourceTypeAndResourceIdAndChunkIndex(
                any(), any(), anyInt())).thenReturn(Optional.of(entry));
        VectorIndexTaskService.TaskLoad load = new VectorIndexTaskService.TaskLoad(
                9L, 1L, VectorResourceType.QUESTION, "5", 0, VectorTaskOperation.UPSERT);

        service.markFailed(load, "Qdrant 不可用");

        assertThat(task.getStatus()).isEqualTo("FAILED");
        assertThat(task.getLastError()).isEqualTo("Qdrant 不可用");
        assertThat(entry.getStatus()).isEqualTo("ERROR");
        assertThat(entry.getLastError()).isEqualTo("Qdrant 不可用");
    }

    @Test
    void markUpsertSucceededCreatesActiveEntry() {
        VectorIndexTaskEntity task = new VectorIndexTaskEntity();
        when(taskRepository.findById(9L)).thenReturn(Optional.of(task));
        when(entryRepository.findByResourceTypeAndResourceIdAndChunkIndex(
                any(), any(), anyInt())).thenReturn(Optional.empty());
        VectorIndexTaskService.TaskLoad load = new VectorIndexTaskService.TaskLoad(
                9L, 1L, VectorResourceType.DOCUMENT_CHUNK, "3", 1, VectorTaskOperation.UPSERT);

        service.markUpsertSucceeded(load, java.util.UUID.randomUUID(), "hash-1");

        ArgumentCaptor<VectorIndexEntryEntity> entryCaptor =
                ArgumentCaptor.forClass(VectorIndexEntryEntity.class);
        verify(entryRepository).save(entryCaptor.capture());
        assertThat(entryCaptor.getValue().getStatus()).isEqualTo("ACTIVE");
        assertThat(entryCaptor.getValue().getContentHash()).isEqualTo("hash-1");
        assertThat(task.getStatus()).isEqualTo("SUCCEEDED");
    }

    // ---------- 4-3：addTask 同事务写 outbox ----------

    @Test
    void addTaskWritesOutboxWithUpsertMessageFields() {
        QuestionEntity question = new QuestionEntity();
        question.setUserId(1L);
        when(questionRepository.findById(88L)).thenReturn(Optional.of(question));
        when(taskRepository.existsByResourceTypeAndResourceIdAndChunkIndexAndOperationAndStatus(
                any(), any(), anyInt(), any(), any())).thenReturn(false);
        when(taskRepository.save(any(VectorIndexTaskEntity.class))).thenAnswer(invocation -> {
            VectorIndexTaskEntity saved = invocation.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", 77L);
            return saved;
        });

        service.enqueueQuestion(1L, 88L);

        ArgumentCaptor<TaskMessage> captor = ArgumentCaptor.forClass(TaskMessage.class);
        verify(outboxEventService).recordPending(captor.capture());
        TaskMessage message = captor.getValue();
        assertThat(message.taskType()).isEqualTo(TaskType.VECTOR_UPSERT);
        assertThat(message.taskId()).isEqualTo(77L);
        assertThat(message.entityId()).isEqualTo("88");
        assertThat(message.op()).isEqualTo("UPSERT");
        assertThat(message.occurredAt()).isNotNull();
    }

    @Test
    void addTaskWritesDeleteOutboxForNodeDelete() {
        MindMapEntity mindMap = new MindMapEntity();
        mindMap.setUserId(1L);
        when(mindMapRepository.findById(4L)).thenReturn(Optional.of(mindMap));
        when(taskRepository.existsByResourceTypeAndResourceIdAndChunkIndexAndOperationAndStatus(
                any(), any(), anyInt(), any(), any())).thenReturn(false);
        when(taskRepository.save(any(VectorIndexTaskEntity.class))).thenAnswer(invocation -> {
            VectorIndexTaskEntity saved = invocation.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", 55L);
            return saved;
        });

        service.enqueueNodeDelete(1L, 4L, "n8");

        ArgumentCaptor<TaskMessage> captor = ArgumentCaptor.forClass(TaskMessage.class);
        verify(outboxEventService).recordPending(captor.capture());
        assertThat(captor.getValue().taskType()).isEqualTo(TaskType.VECTOR_DELETE);
        assertThat(captor.getValue().taskId()).isEqualTo(55L);
        assertThat(captor.getValue().op()).isEqualTo("DELETE");
        assertThat(captor.getValue().entityId()).isEqualTo("4:n8");
    }

    @Test
    void memoryUpsertAlsoMapsToVectorUpsertOutbox() {
        com.example.aimindmap.agentmemory.AgentMemoryEntity memory =
                new com.example.aimindmap.agentmemory.AgentMemoryEntity();
        memory.setUserId(1L);
        ReflectionTestUtils.setField(memory, "id", 9L);
        when(agentMemoryRepository.findByUserIdAndStatus(1L, "active")).thenReturn(List.of(memory));
        when(taskRepository.existsByResourceTypeAndResourceIdAndChunkIndexAndOperationAndStatus(
                any(), any(), anyInt(), any(), any())).thenReturn(false);
        when(taskRepository.save(any(VectorIndexTaskEntity.class))).thenAnswer(invocation -> {
            VectorIndexTaskEntity saved = invocation.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", 100L);
            return saved;
        });

        service.rebuildByType(1L, VectorResourceType.MEMORY);

        ArgumentCaptor<TaskMessage> captor = ArgumentCaptor.forClass(TaskMessage.class);
        verify(outboxEventService).recordPending(captor.capture());
        assertThat(captor.getValue().taskType()).isEqualTo(TaskType.VECTOR_UPSERT);
        assertThat(captor.getValue().entityId()).isEqualTo("9");
    }

    @Test
    void duplicatePendingTaskWritesNoOutbox() {
        DocumentEntity document = new DocumentEntity();
        document.setUserId(1L);
        document.setStatus("active");
        document.setContent("简单内容");
        when(documentRepository.findById(3L)).thenReturn(Optional.of(document));
        when(entryRepository.findByResourceTypeAndResourceId(any(), any())).thenReturn(List.of());
        when(taskRepository.existsByResourceTypeAndResourceIdAndChunkIndexAndOperationAndStatus(
                any(), any(), anyInt(), any(), any())).thenReturn(true);

        service.enqueueDocument(1L, 3L);

        verify(outboxEventService, never()).recordPending(any());
    }

    @Test
    void uniqueConflictWritesNoOutbox() {
        QuestionEntity question = new QuestionEntity();
        question.setUserId(1L);
        when(questionRepository.findById(88L)).thenReturn(Optional.of(question));
        when(taskRepository.existsByResourceTypeAndResourceIdAndChunkIndexAndOperationAndStatus(
                any(), any(), anyInt(), any(), any())).thenReturn(false);
        when(taskRepository.save(any(VectorIndexTaskEntity.class)))
                .thenThrow(new DataIntegrityViolationException("uq_vector_tasks_pending"));

        assertThat(service.enqueueQuestion(1L, 88L)).isZero();
        verify(outboxEventService, never()).recordPending(any());
    }

    // ---------- 4-3：条件认领 / 成功落库 / 重试复位 ----------

    @Test
    void claimForMessageReturnsTrueOnlyWhenConditionalUpdateHitsRow() {
        when(taskRepository.claimForConsumer(7L)).thenReturn(1);
        when(taskRepository.claimForConsumer(8L)).thenReturn(0);

        assertThat(service.claimForMessage(7L)).isTrue();
        assertThat(service.claimForMessage(8L)).isFalse();
    }

    @Test
    void loadTaskMapsEntityToTaskLoad() {
        VectorIndexTaskEntity entity = new VectorIndexTaskEntity();
        ReflectionTestUtils.setField(entity, "id", 9L);
        entity.setUserId(1L);
        entity.setResourceType(VectorResourceType.QUESTION.wireName());
        entity.setResourceId("88");
        entity.setChunkIndex(0);
        entity.setOperation(VectorTaskOperation.DELETE.name());
        when(taskRepository.findById(9L)).thenReturn(Optional.of(entity));

        VectorIndexTaskService.TaskLoad load = service.loadTask(9L);

        assertThat(load.taskId()).isEqualTo(9L);
        assertThat(load.resourceType()).isEqualTo(VectorResourceType.QUESTION);
        assertThat(load.operation()).isEqualTo(VectorTaskOperation.DELETE);
    }

    @Test
    void completeFromMessageMarksSucceededAndInsertsIdempotentRow() {
        VectorIndexTaskEntity task = new VectorIndexTaskEntity();
        when(taskRepository.findById(9L)).thenReturn(Optional.of(task));
        when(entryRepository.findByResourceTypeAndResourceIdAndChunkIndex(
                any(), any(), anyInt())).thenReturn(Optional.empty());
        VectorIndexTaskService.TaskLoad load = new VectorIndexTaskService.TaskLoad(
                9L, 1L, VectorResourceType.DOCUMENT_CHUNK, "3", 1, VectorTaskOperation.UPSERT);

        service.completeFromMessage(load,
                new VectorIndexTaskProcessor.ProcessResult.UpsertSucceeded(
                        java.util.UUID.randomUUID(), "hash-9"),
                "VECTOR_UPSERT:9");

        assertThat(task.getStatus()).isEqualTo("SUCCEEDED");
        ArgumentCaptor<IdempotentConsumptionEntity> captor =
                ArgumentCaptor.forClass(IdempotentConsumptionEntity.class);
        verify(idempotentConsumptionRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getMessageId()).isEqualTo("VECTOR_UPSERT:9");
    }

    @Test
    void completeFromMessagePropagatesIdempotentUniqueConflict() {
        VectorIndexTaskEntity task = new VectorIndexTaskEntity();
        when(taskRepository.findById(9L)).thenReturn(Optional.of(task));
        when(entryRepository.findByResourceTypeAndResourceIdAndChunkIndex(
                any(), any(), anyInt())).thenReturn(Optional.empty());
        when(idempotentConsumptionRepository.saveAndFlush(any()))
                .thenThrow(new DataIntegrityViolationException("idempotent pkey conflict"));
        VectorIndexTaskService.TaskLoad load = new VectorIndexTaskService.TaskLoad(
                9L, 1L, VectorResourceType.DOCUMENT_CHUNK, "3", 1, VectorTaskOperation.UPSERT);

        assertThatThrownBy(() -> service.completeFromMessage(load,
                new VectorIndexTaskProcessor.ProcessResult.ProcessDeleteSucceeded(),
                "VECTOR_DELETE:9"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void requeueForMessageRetryDelegatesConditionalReset() {
        when(taskRepository.requeueForRetry(eq(9L), anyString())).thenReturn(1);

        assertThat(service.requeueForMessageRetry(9L, "Qdrant 写入失败")).isTrue();

        verify(taskRepository).requeueForRetry(eq(9L), eq("Qdrant 写入失败"));
    }

    // ---------- 4-3：对账扫描 ----------

    @Test
    void reconcileReissuesStalePendingOutboxOnlyWhenNoPendingOutboxExists() {
        VectorIndexTaskEntity stale = staleTask(1L, VectorTaskStatus.PENDING.name(),
                VectorResourceType.QUESTION.wireName(), "88", VectorTaskOperation.UPSERT);
        when(taskRepository.findStalePending(any(Instant.class), anyInt()))
                .thenReturn(List.of(stale));
        when(taskRepository.findStaleProcessing(any(Instant.class), anyInt()))
                .thenReturn(List.of());
        when(outboxEventRepository.existsByTaskTypeAndTaskIdAndStatus(
                TaskType.VECTOR_UPSERT.wireName(), 1L, OutboxEventStatus.PENDING)).thenReturn(false);

        int recovered = service.reconcileStaleTasks();

        assertThat(recovered).isOne();
        ArgumentCaptor<TaskMessage> captor = ArgumentCaptor.forClass(TaskMessage.class);
        verify(outboxEventService).recordPending(captor.capture());
        assertThat(captor.getValue().taskId()).isEqualTo(1L);
        assertThat(captor.getValue().taskType()).isEqualTo(TaskType.VECTOR_UPSERT);
    }

    @Test
    void reconcileSkipsStalePendingWhenPendingOutboxAlreadyExists() {
        VectorIndexTaskEntity stale = staleTask(1L, VectorTaskStatus.PENDING.name(),
                VectorResourceType.QUESTION.wireName(), "88", VectorTaskOperation.UPSERT);
        when(taskRepository.findStalePending(any(Instant.class), anyInt()))
                .thenReturn(List.of(stale));
        when(taskRepository.findStaleProcessing(any(Instant.class), anyInt()))
                .thenReturn(List.of());
        when(outboxEventRepository.existsByTaskTypeAndTaskIdAndStatus(
                anyString(), anyLong(), any())).thenReturn(true);

        assertThat(service.reconcileStaleTasks()).isZero();
        verify(outboxEventService, never()).recordPending(any());
    }

    @Test
    void reconcileResetsStaleProcessingAndReissuesOutbox() {
        VectorIndexTaskEntity stale = staleTask(2L, VectorTaskStatus.PROCESSING.name(),
                VectorResourceType.MINDMAP_NODE.wireName(), "4:n8", VectorTaskOperation.DELETE);
        when(taskRepository.findStalePending(any(Instant.class), anyInt()))
                .thenReturn(List.of());
        when(taskRepository.findStaleProcessing(any(Instant.class), anyInt()))
                .thenReturn(List.of(stale));
        when(taskRepository.existsByResourceTypeAndResourceIdAndChunkIndexAndOperationAndStatus(
                any(), any(), anyInt(), any(), any())).thenReturn(false);

        int recovered = service.reconcileStaleTasks();

        assertThat(recovered).isOne();
        assertThat(stale.getStatus()).isEqualTo(VectorTaskStatus.PENDING.name());
        verify(taskRepository).save(stale);
        ArgumentCaptor<TaskMessage> captor = ArgumentCaptor.forClass(TaskMessage.class);
        verify(outboxEventService).recordPending(captor.capture());
        assertThat(captor.getValue().taskType()).isEqualTo(TaskType.VECTOR_DELETE);
    }

    @Test
    void staleProcessingSupersededByNewerPendingIsMarkedFailedWithoutOutbox() {
        VectorIndexTaskEntity stale = staleTask(2L, VectorTaskStatus.PROCESSING.name(),
                VectorResourceType.DOCUMENT_CHUNK.wireName(), "3", VectorTaskOperation.UPSERT);
        when(taskRepository.findStalePending(any(Instant.class), anyInt()))
                .thenReturn(List.of());
        when(taskRepository.findStaleProcessing(any(Instant.class), anyInt()))
                .thenReturn(List.of(stale));
        when(taskRepository.existsByResourceTypeAndResourceIdAndChunkIndexAndOperationAndStatus(
                any(), any(), anyInt(), any(), any())).thenReturn(true);

        int recovered = service.reconcileStaleTasks();

        assertThat(recovered).isZero();
        assertThat(stale.getStatus()).isEqualTo(VectorTaskStatus.FAILED.name());
        verify(outboxEventService, never()).recordPending(any());
    }

    @Test
    void reconcileStaleThresholdIsFiveMinutesAndBoundedByConstant() {
        assertThat(VectorIndexTaskService.RECONCILE_STALE_AGE).isEqualTo(Duration.ofMinutes(5));
        assertThat(VectorIndexTaskService.RECONCILE_BATCH_LIMIT).isLessThanOrEqualTo(100);
        when(taskRepository.findStalePending(any(Instant.class), anyInt()))
                .thenReturn(List.of());
        when(taskRepository.findStaleProcessing(any(Instant.class), anyInt()))
                .thenReturn(List.of());

        service.reconcileStaleTasks();

        ArgumentCaptor<Instant> cutoffCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(taskRepository).findStalePending(cutoffCaptor.capture(),
                eq(VectorIndexTaskService.RECONCILE_BATCH_LIMIT));
        assertThat(Duration.between(cutoffCaptor.getValue(), Instant.now())).isGreaterThanOrEqualTo(
                Duration.ofMinutes(5).minusSeconds(5));
    }

    /**
     * 缺陷修复（2026-11-04，P1/P2）：软删除文档要按导图批量删节点向量；
     * 没有导图（从未同步出图的文档）返回 0 且不抛异常，保证删文档不被阻塞。
     */
    @Test
    void enqueueMindmapDeletionByDocumentReturnsZeroWithoutMindmap() {
        when(mindMapRepository.findByDocumentId(3L)).thenReturn(Optional.empty());

        int enqueued = service.enqueueMindmapDeletionByDocument(1L, 3L);

        assertThat(enqueued).isZero();
        verify(taskRepository, never()).save(any(VectorIndexTaskEntity.class));
        verify(outboxEventService, never()).recordPending(any());
        verify(graphJsonCodec, never()).read(any());
    }

    /** 有导图时：每个节点一条 DELETE，resourceId 沿用 "mindmapId:nodeId"。 */
    @Test
    void enqueueMindmapDeletionByDocumentEnqueuesDeletePerNode() {
        MindMapEntity mindMap = new MindMapEntity();
        mindMap.setUserId(1L);
        mindMap.setDocumentId(3L);
        mindMap.setGraph("{\"rootId\":\"n1\",\"version\":1}");
        ReflectionTestUtils.setField(mindMap, "id", 4L);
        when(mindMapRepository.findByDocumentId(3L)).thenReturn(Optional.of(mindMap));

        MindMapNode root = new MindMapNode();
        root.setId("n1");
        MindMapNode child = new MindMapNode();
        child.setId("n2");
        root.setChildren(new java.util.ArrayList<>(List.of(child)));
        when(graphJsonCodec.read(anyString()))
                .thenReturn(new MindMapGraph("n1", 1, root, Instant.now()));
        when(taskRepository.existsByResourceTypeAndResourceIdAndChunkIndexAndOperationAndStatus(
                any(), any(), anyInt(), any(), any())).thenReturn(false);
        when(taskRepository.save(any(VectorIndexTaskEntity.class))).thenAnswer(invocation -> {
            VectorIndexTaskEntity saved = invocation.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", 55L);
            return saved;
        });

        int enqueued = service.enqueueMindmapDeletionByDocument(1L, 3L);

        ArgumentCaptor<VectorIndexTaskEntity> captor =
                ArgumentCaptor.forClass(VectorIndexTaskEntity.class);
        verify(taskRepository, org.mockito.Mockito.times(2)).save(captor.capture());
        assertThat(enqueued).isEqualTo(2);
        assertThat(captor.getAllValues()).allSatisfy(task -> {
            assertThat(task.getOperation()).isEqualTo("DELETE");
            assertThat(task.getResourceType()).isEqualTo(VectorResourceType.MINDMAP_NODE.wireName());
            assertThat(task.getUserId()).isEqualTo(1L);
            assertThat(task.getChunkIndex()).isZero();
        });
        assertThat(captor.getAllValues())
                .extracting(VectorIndexTaskEntity::getResourceId)
                .containsExactlyInAnyOrder("4:n1", "4:n2");
    }

    @Test
    void enqueueAndConsumerEntryPointsAreTransactional() throws Exception {
        assertTransactional("enqueueDocument", Long.class, Long.class);
        assertTransactional("enqueueDocumentDeletion", Long.class, Long.class);
        assertTransactional("enqueueMindmapDeletionByDocument", Long.class, Long.class);
        assertTransactional("enqueueMindmap", Long.class, Long.class);
        assertTransactional("enqueueNodeUpsert", Long.class, Long.class, String.class);
        assertTransactional("enqueueNodeDelete", Long.class, Long.class, String.class);
        assertTransactional("enqueueQuestion", Long.class, Long.class);
        assertTransactional("claimForMessage", Long.class);
        assertTransactional("completeFromMessage", VectorIndexTaskService.TaskLoad.class,
                VectorIndexTaskProcessor.ProcessResult.class, String.class);
        assertTransactional("reconcileStaleTasks");
    }

    private static void assertTransactional(String methodName, Class<?>... parameterTypes)
            throws Exception {
        assertThat(VectorIndexTaskService.class.getMethod(methodName, parameterTypes)
                .isAnnotationPresent(Transactional.class))
                .as("VectorIndexTaskService#%s must be @Transactional", methodName)
                .isTrue();
    }

    private static VectorIndexTaskEntity staleTask(Long id, String status, String resourceType,
                                                   String resourceId, VectorTaskOperation op) {
        VectorIndexTaskEntity entity = new VectorIndexTaskEntity();
        ReflectionTestUtils.setField(entity, "id", id);
        entity.setUserId(1L);
        entity.setResourceType(resourceType);
        entity.setResourceId(resourceId);
        entity.setChunkIndex(0);
        entity.setOperation(op.name());
        entity.setStatus(status);
        ReflectionTestUtils.setField(entity, "updatedAt", Instant.now().minus(Duration.ofMinutes(10)));
        return entity;
    }
}
