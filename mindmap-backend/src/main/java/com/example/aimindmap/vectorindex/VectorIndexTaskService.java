package com.example.aimindmap.vectorindex;

import com.example.aimindmap.agentmemory.AgentMemoryEntity;
import com.example.aimindmap.agentmemory.AgentMemoryRepository;
import com.example.aimindmap.agentmemory.MemoryVectorService;
import com.example.aimindmap.document.DocumentEntity;
import com.example.aimindmap.document.DocumentRepository;
import com.example.aimindmap.mindmap.GraphJsonCodec;
import com.example.aimindmap.mindmap.MindMapEntity;
import com.example.aimindmap.mindmap.MindMapRepository;
import com.example.aimindmap.mindmap.graph.GraphIndex;
import com.example.aimindmap.mindmap.graph.MindMapGraph;
import com.example.aimindmap.mindmap.graph.MindMapNode;
import com.example.aimindmap.mqsupport.TaskMessage;
import com.example.aimindmap.mqsupport.TaskType;
import com.example.aimindmap.mqsupport.outbox.IdempotentConsumptionEntity;
import com.example.aimindmap.mqsupport.outbox.IdempotentConsumptionRepository;
import com.example.aimindmap.mqsupport.outbox.OutboxEventRepository;
import com.example.aimindmap.mqsupport.outbox.OutboxEventService;
import com.example.aimindmap.mqsupport.outbox.OutboxEventStatus;
import com.example.aimindmap.question.QuestionEntity;
import com.example.aimindmap.question.QuestionRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 向量任务投递、认领与结果落库（方案 §9）。
 * 投递默认加入调用方业务事务（事务性发件箱模式，不丢任务）；
 * userId 一律来自资源归属/登录态，不接受外部传入。
 */
@Service
public class VectorIndexTaskService {

    private static final Logger log = LoggerFactory.getLogger(VectorIndexTaskService.class);
    private static final int ERROR_MAX_LENGTH = 1000;

    /**
     * 4-3 对账扫描每类每轮最多补投的行数（PENDING/PROCESSING 各自限量，防风暴）。
     */
    static final int RECONCILE_BATCH_LIMIT = 100;

    /**
     * 4-3 对账超时门槛：任务行 PENDING/PROCESSING 超过该时长才视为漏发或消费者崩溃。
     * 取 5 分钟：正常 outbox 投递是秒级（relay 1s/次、最多 5 次），正常处理是秒级，
     * 最长重试档 2 分钟期间任务行也已复位为 PENDING（updated_at 会刷新），不会误伤。
     */
    static final Duration RECONCILE_STALE_AGE = Duration.ofMinutes(5);

    private final VectorIndexTaskRepository taskRepository;
    private final VectorIndexEntryRepository entryRepository;
    private final DocumentRepository documentRepository;
    private final MindMapRepository mindMapRepository;
    private final QuestionRepository questionRepository;
    private final GraphJsonCodec graphJsonCodec;
    private final QdrantProperties properties;
    private final MemoryVectorService memoryVectorService;
    private final AgentMemoryRepository agentMemoryRepository;
    private final OutboxEventService outboxEventService;
    private final OutboxEventRepository outboxEventRepository;
    private final IdempotentConsumptionRepository idempotentConsumptionRepository;
    private final MarkdownChunker chunker = new MarkdownChunker();

    public VectorIndexTaskService(VectorIndexTaskRepository taskRepository,
                                  VectorIndexEntryRepository entryRepository,
                                  DocumentRepository documentRepository,
                                  MindMapRepository mindMapRepository,
                                  QuestionRepository questionRepository,
                                  GraphJsonCodec graphJsonCodec,
                                  QdrantProperties properties,
                                  MemoryVectorService memoryVectorService,
                                  AgentMemoryRepository agentMemoryRepository,
                                  OutboxEventService outboxEventService,
                                  OutboxEventRepository outboxEventRepository,
                                  IdempotentConsumptionRepository idempotentConsumptionRepository) {
        this.taskRepository = taskRepository;
        this.entryRepository = entryRepository;
        this.documentRepository = documentRepository;
        this.mindMapRepository = mindMapRepository;
        this.questionRepository = questionRepository;
        this.graphJsonCodec = graphJsonCodec;
        this.properties = properties;
        this.memoryVectorService = memoryVectorService;
        this.agentMemoryRepository = agentMemoryRepository;
        this.outboxEventService = outboxEventService;
        this.outboxEventRepository = outboxEventRepository;
        this.idempotentConsumptionRepository = idempotentConsumptionRepository;
    }

    /** worker 认领一批任务：先 PENDING，再未达上限的 FAILED。 */
    @Transactional
    public List<TaskLoad> claimBatch() {
        List<VectorIndexTaskEntity> claimed = taskRepository.claimPending(properties.getWorkerBatchSize());
        if (claimed.isEmpty()) {
            claimed = taskRepository.claimRetriableFailed(
                    properties.getWorkerBatchSize(), properties.getMaxAttempts());
        }
        List<TaskLoad> result = new ArrayList<>();
        for (VectorIndexTaskEntity entity : claimed) {
            entity.setStatus(VectorTaskStatus.PROCESSING.name());
            entity.setAttempts(entity.getAttempts() + 1);
            entity.touch();
            taskRepository.save(entity);
            result.add(new TaskLoad(
                    entity.getId(),
                    entity.getUserId(),
                    VectorResourceType.fromWire(entity.getResourceType()),
                    entity.getResourceId(),
                    entity.getChunkIndex(),
                    VectorTaskOperation.valueOf(entity.getOperation())));
        }
        return result;
    }

    // ---------- 笔记挂钩 ----------

    /** 笔记新建/内容更新/重命名后同步片段：当前片段 UPSERT，消失片段 DELETE。 */
    @Transactional
    public int enqueueDocument(Long userId, Long documentId) {
        DocumentEntity document = documentRepository.findById(documentId)
                .orElseThrow(() -> new IllegalArgumentException("笔记不存在：" + documentId));
        requireOwned(document.getUserId(), userId);
        String resourceId = String.valueOf(documentId);
        Set<Integer> currentIndices = new HashSet<>();

        int enqueued = 0;
        if ("active".equals(document.getStatus())) {
            List<MarkdownChunker.Chunk> chunks = chunker.chunk(document.getContent());
            for (MarkdownChunker.Chunk chunk : chunks) {
                currentIndices.add(chunk.chunkIndex());
                if (addTask(userId, VectorResourceType.DOCUMENT_CHUNK, resourceId,
                        chunk.chunkIndex(), VectorTaskOperation.UPSERT)) {
                    enqueued++;
                }
            }
        }

        for (VectorIndexEntryEntity entry : entryRepository.findByResourceTypeAndResourceId(
                VectorResourceType.DOCUMENT_CHUNK.wireName(), resourceId)) {
            if (!currentIndices.contains(entry.getChunkIndex())
                    && addTask(userId, VectorResourceType.DOCUMENT_CHUNK, resourceId,
                    entry.getChunkIndex(), VectorTaskOperation.DELETE)) {
                enqueued++;
            }
        }
        return enqueued;
    }

    /** 笔记软删除：全部既有片段/映射投递 DELETE。 */
    @Transactional
    public int enqueueDocumentDeletion(Long userId, Long documentId) {
        DocumentEntity document = documentRepository.findById(documentId)
                .orElseThrow(() -> new IllegalArgumentException("笔记不存在：" + documentId));
        requireOwned(document.getUserId(), userId);
        String resourceId = String.valueOf(documentId);

        Set<Integer> indices = new HashSet<>();
        for (MarkdownChunker.Chunk chunk : chunker.chunk(document.getContent())) {
            indices.add(chunk.chunkIndex());
        }
        for (VectorIndexEntryEntity entry : entryRepository.findByResourceTypeAndResourceId(
                VectorResourceType.DOCUMENT_CHUNK.wireName(), resourceId)) {
            indices.add(entry.getChunkIndex());
        }

        int enqueued = 0;
        for (Integer index : indices) {
            if (addTask(userId, VectorResourceType.DOCUMENT_CHUNK, resourceId,
                    index, VectorTaskOperation.DELETE)) {
                enqueued++;
            }
        }
        return enqueued;
    }

    // ---------- 导图挂钩 ----------

    /** 导图同步发布后整图同步：当前节点 UPSERT，消失节点 DELETE。 */
    @Transactional
    public int enqueueMindmap(Long userId, Long mindmapId) {
        MindMapEntity mindMap = requireMindmap(mindmapId, userId);
        MindMapGraph graph = graphJsonCodec.read(mindMap.getGraph());

        Set<String> currentNodeIds = new HashSet<>();
        if (!graph.isEmpty()) {
            for (MindMapNode node : GraphIndex.of(graph).allNodes()) {
                currentNodeIds.add(node.getId());
            }
        }

        int enqueued = 0;
        for (String nodeId : currentNodeIds) {
            if (addTask(userId, VectorResourceType.MINDMAP_NODE,
                    nodeResourceId(mindmapId, nodeId), 0, VectorTaskOperation.UPSERT)) {
                enqueued++;
            }
        }

        String prefix = mindmapId + ":%";
        for (VectorIndexEntryEntity entry : entryRepository.findMindmapNodeEntries(prefix)) {
            String nodeId = entry.getResourceId().substring(entry.getResourceId().indexOf(':') + 1);
            if (!currentNodeIds.contains(nodeId)
                    && addTask(userId, VectorResourceType.MINDMAP_NODE,
                    entry.getResourceId(), 0, VectorTaskOperation.DELETE)) {
                enqueued++;
            }
        }
        return enqueued;
    }

    /**
     * 软删除文档后调用：把该文档所属导图的全部节点向量标记为 DELETE；没有导图时返回 0。
     *
     * <p>文档软删除后导图行仍保留（便于将来恢复/重建），但节点向量不能留成孤儿——
     * 否则检索侧 filter（status=active）会继续命中"用户已经删掉的笔记"。
     * 没有导图（未同步出图的文档）不抛异常，直接返回 0，保证删文档流程不被阻塞。</p>
     */
    @Transactional
    public int enqueueMindmapDeletionByDocument(Long userId, Long documentId) {
        MindMapEntity mindMap = mindMapRepository.findByDocumentId(documentId).orElse(null);
        if (mindMap == null) {
            return 0;
        }
        requireOwned(mindMap.getUserId(), userId);

        MindMapGraph graph = graphJsonCodec.read(mindMap.getGraph());
        if (graph == null || graph.isEmpty()) {
            log.info("软删除文档，导图为空无需清理节点向量 userId={} documentId={} mindmapId={}",
                    userId, documentId, mindMap.getId());
            return 0;
        }

        int enqueued = 0;
        for (MindMapNode node : GraphIndex.of(graph).allNodes()) {
            if (addTask(userId, VectorResourceType.MINDMAP_NODE,
                    nodeResourceId(mindMap.getId(), node.getId()), 0, VectorTaskOperation.DELETE)) {
                enqueued++;
            }
        }
        log.info("软删除文档，已入队导图节点向量删除 userId={} documentId={} mindmapId={} nodeCount={}",
                userId, documentId, mindMap.getId(), enqueued);
        return enqueued;
    }

    /** 手动新增/修改节点：单节点 UPSERT。 */
    @Transactional
    public int enqueueNodeUpsert(Long userId, Long mindmapId, String nodeId) {
        requireMindmap(mindmapId, userId);
        return addTask(userId, VectorResourceType.MINDMAP_NODE,
                nodeResourceId(mindmapId, nodeId), 0, VectorTaskOperation.UPSERT) ? 1 : 0;
    }

    /** 手动删除节点/接受删除建议：单节点 DELETE。 */
    @Transactional
    public int enqueueNodeDelete(Long userId, Long mindmapId, String nodeId) {
        requireMindmap(mindmapId, userId);
        return addTask(userId, VectorResourceType.MINDMAP_NODE,
                nodeResourceId(mindmapId, nodeId), 0, VectorTaskOperation.DELETE) ? 1 : 0;
    }

    // ---------- 题目挂钩 ----------

    /** 出题成功/单题重生成后：单题 UPSERT。 */
    @Transactional
    public int enqueueQuestion(Long userId, Long questionId) {
        QuestionEntity question = questionRepository.findById(questionId)
                .orElseThrow(() -> new IllegalArgumentException("题目不存在：" + questionId));
        requireOwned(question.getUserId(), userId);
        return addTask(userId, VectorResourceType.QUESTION,
                String.valueOf(questionId), 0, VectorTaskOperation.UPSERT) ? 1 : 0;
    }

    // ---------- 用户级重建 ----------

    @Transactional
    public int rebuildAll(Long userId) {
        int enqueued = 0;
        for (DocumentEntity document :
                documentRepository.findByStatusAndUserIdOrderByUpdatedAtDesc("active", userId)) {
            enqueued += enqueueDocument(userId, document.getId());
        }
        for (MindMapEntity mindMap : mindMapRepository.findByUserId(userId)) {
            enqueued += enqueueMindmap(userId, mindMap.getId());
        }
        for (QuestionEntity question : questionRepository.findByUserIdOrderByUpdatedAtDescIdDesc(userId)) {
            enqueued += enqueueQuestion(userId, question.getId());
        }
        enqueued += rebuildMemory(userId);
        log.info("用户级向量重建任务已投递 userId={} enqueued={}", userId, enqueued);
        return enqueued;
    }

    @Transactional
    public int rebuildByType(Long userId, VectorResourceType resourceType) {
        int enqueued = 0;
        switch (resourceType) {
            case DOCUMENT_CHUNK -> {
                enqueued = 0;
                for (DocumentEntity document :
                        documentRepository.findByStatusAndUserIdOrderByUpdatedAtDesc("active", userId)) {
                    enqueued += enqueueDocument(userId, document.getId());
                }
            }
            case MINDMAP_NODE -> {
                enqueued = 0;
                for (MindMapEntity mindMap : mindMapRepository.findByUserId(userId)) {
                    enqueued += enqueueMindmap(userId, mindMap.getId());
                }
            }
            case QUESTION -> {
                enqueued = 0;
                for (QuestionEntity question :
                        questionRepository.findByUserIdOrderByUpdatedAtDescIdDesc(userId)) {
                    enqueued += enqueueQuestion(userId, question.getId());
                }
            }
            case MEMORY -> enqueued = rebuildMemory(userId);
        }
        log.info("用户级向量重建任务已投递 userId={} resourceType={} enqueued={}",
                userId, resourceType.wireName(), enqueued);
        return enqueued;
    }

    /**
     * 记忆重建：为每条 active 记忆投递 UPSERT（同点覆盖），
     * 事务提交后同步枚举并清理"行已不存在/非 active"的残留点（任务四，R-09 只重建本人）。
     */
    private int rebuildMemory(Long userId) {
        List<Long> activeIds = new ArrayList<>();
        int enqueued = 0;
        for (AgentMemoryEntity memory : memoryRepository(userId)) {
            activeIds.add(memory.getId());
            if (addTask(userId, VectorResourceType.MEMORY, String.valueOf(memory.getId()),
                    0, VectorTaskOperation.UPSERT)) {
                enqueued++;
            }
        }
        List<Long> captured = List.copyOf(activeIds);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    memoryVectorService.cleanupOrphanPoints(userId, captured);
                }
            });
        } else {
            memoryVectorService.cleanupOrphanPoints(userId, captured);
        }
        log.info("用户级记忆重建任务已投递 userId={} activeMemories={} enqueued={}",
                userId, activeIds.size(), enqueued);
        return enqueued;
    }

    private List<AgentMemoryEntity> memoryRepository(Long userId) {
        return agentMemoryRepository.findByUserIdAndStatus(userId, "active");
    }

    // ---------- 结果落库 ----------

    @Transactional
    public void markUpsertSucceeded(TaskLoad task, UUID pointId, String contentHash) {
        VectorIndexEntryEntity entry = entryRepository
                .findByResourceTypeAndResourceIdAndChunkIndex(
                        task.resourceType().wireName(), task.resourceId(), task.chunkIndex())
                .orElseGet(VectorIndexEntryEntity::new);
        entry.setUserId(task.userId());
        entry.setResourceType(task.resourceType().wireName());
        entry.setResourceId(task.resourceId());
        entry.setChunkIndex(task.chunkIndex());
        entry.setQdrantPointId(pointId.toString());
        entry.setContentHash(contentHash);
        entry.setStatus(VectorEntryStatus.ACTIVE.name());
        entry.setLastError(null);
        entry.setLastSyncedAt(java.time.Instant.now());
        entryRepository.save(entry);

        VectorIndexTaskEntity taskEntity = getTask(task.taskId());
        taskEntity.setStatus(VectorTaskStatus.SUCCEEDED.name());
        taskEntity.setLastError(null);
        taskEntity.touch();
        taskRepository.save(taskEntity);
    }

    @Transactional
    public void markDeleteSucceeded(TaskLoad task) {
        entryRepository.findByResourceTypeAndResourceIdAndChunkIndex(
                        task.resourceType().wireName(), task.resourceId(), task.chunkIndex())
                .ifPresent(entry -> {
                    entry.setStatus(VectorEntryStatus.PENDING_DELETE.name());
                    entry.setLastError(null);
                    entry.touch();
                    entryRepository.save(entry);
                });

        VectorIndexTaskEntity taskEntity = getTask(task.taskId());
        taskEntity.setStatus(VectorTaskStatus.SUCCEEDED.name());
        taskEntity.setLastError(null);
        taskEntity.touch();
        taskRepository.save(taskEntity);
    }

    /** 内容未变化：跳过外部调用，直接成功。 */
    @Transactional
    public void markSkipped(Long taskId) {
        VectorIndexTaskEntity taskEntity = getTask(taskId);
        taskEntity.setStatus(VectorTaskStatus.SUCCEEDED.name());
        taskEntity.touch();
        taskRepository.save(taskEntity);
    }

    @Transactional
    public void markFailed(TaskLoad task, String error) {
        String truncated = truncate(error);
        VectorIndexTaskEntity taskEntity = getTask(task.taskId());
        taskEntity.setStatus(VectorTaskStatus.FAILED.name());
        taskEntity.setLastError(truncated);
        taskEntity.touch();
        taskRepository.save(taskEntity);

        entryRepository.findByResourceTypeAndResourceIdAndChunkIndex(
                        task.resourceType().wireName(), task.resourceId(), task.chunkIndex())
                .ifPresent(entry -> {
                    entry.setStatus(VectorEntryStatus.ERROR.name());
                    entry.setLastError(truncated);
                    entry.touch();
                    entryRepository.save(entry);
                });
    }

    // ---------- 内部工具 ----------

    private boolean addTask(Long userId,
                            VectorResourceType resourceType,
                            String resourceId,
                            int chunkIndex,
                            VectorTaskOperation operation) {
        boolean pendingExists = taskRepository
                .existsByResourceTypeAndResourceIdAndChunkIndexAndOperationAndStatus(
                        resourceType.wireName(), resourceId, chunkIndex,
                        operation.name(), VectorTaskStatus.PENDING.name());
        if (pendingExists) {
            log.debug("跳过重复向量任务 resourceType={} resourceId={} chunkIndex={} operation={}",
                    resourceType.wireName(), resourceId, chunkIndex, operation);
            return false;
        }
        VectorIndexTaskEntity entity = new VectorIndexTaskEntity();
        entity.setUserId(userId);
        entity.setResourceType(resourceType.wireName());
        entity.setResourceId(resourceId);
        entity.setChunkIndex(chunkIndex);
        entity.setOperation(operation.name());
        entity.setStatus(VectorTaskStatus.PENDING.name());
        try {
            taskRepository.save(entity);
            // 4-3：任务行与 outbox 同一业务事务原子落库（方案《阶段四·D》）；
            // recordPending 传播级别 REQUIRED，序列化失败抛 IllegalStateException 让事务回滚。
            outboxEventService.recordPending(toTaskMessage(entity));
            return true;
        } catch (DataIntegrityViolationException e) {
            log.warn("向量任务并发去重冲突，已跳过 resourceType={} resourceId={} chunkIndex={} operation={}",
                    resourceType.wireName(), resourceId, chunkIndex, operation);
            return false;
        }
    }

    // ---------- 4-3 MQ 消费链路 ----------

    /**
     * 消费者条件认领（幂等三件套之一）：仅 PENDING 行能被认领，0 行说明任务已被
     * 其他投递处理（H1-3/H1-5）或状态已推进，调用方直接 ack。
     */
    @Transactional
    public boolean claimForMessage(Long taskId) {
        return taskRepository.claimForConsumer(taskId) > 0;
    }

    /** 认领后读取任务快照；行不存在返回 null（正常不应发生，调用方按异常入 DLQ）。 */
    @Transactional(readOnly = true)
    public TaskLoad loadTask(Long taskId) {
        return taskRepository.findById(taskId)
                .map(VectorIndexTaskService::toTaskLoad)
                .orElse(null);
    }

    /**
     * 消费成功落库：按 ProcessResult 调既有 mark* 方法，并在同一事务插入幂等记录
     * （messageId=taskType:taskId，幂等三件套之三）。幂等表唯一冲突会以
     * DataIntegrityViolationException 外抛，由消费者捕获后直接 ack（说明已有投递完成过）；
     * 同一事务内的 mark* 随之回滚也正确——赢家的成功状态才是准的。
     */
    @Transactional
    public void completeFromMessage(TaskLoad task,
                                    VectorIndexTaskProcessor.ProcessResult result,
                                    String messageId) {
        switch (result) {
            case VectorIndexTaskProcessor.ProcessResult.UpsertSucceeded succeeded ->
                    markUpsertSucceeded(task, succeeded.pointId(), succeeded.contentHash());
            case VectorIndexTaskProcessor.ProcessResult.ProcessDeleteSucceeded ignored ->
                    markDeleteSucceeded(task);
            case VectorIndexTaskProcessor.ProcessResult.Skipped ignored ->
                    markSkipped(task.taskId());
        }
        idempotentConsumptionRepository.saveAndFlush(new IdempotentConsumptionEntity(messageId));
    }

    /**
     * 可重试失败转延时重试前复位任务行：PROCESSING→PENDING 并记录最近错误，
     * 重试消息到期回来后才能再次被 {@link #claimForMessage} 认领。
     */
    @Transactional
    public boolean requeueForMessageRetry(Long taskId, String error) {
        int updated = taskRepository.requeueForRetry(taskId, truncate(error));
        if (updated == 0) {
            log.warn("重试复位未命中 PROCESSING 任务行（可能存在并发） taskId={}", taskId);
        }
        return updated > 0;
    }

    /**
     * 4-3 对账（MQ 开启时 VectorIndexWorker 每 60s 调用一次，方案《阶段四·C》末条）：
     * 补投"PENDING 超时漏发"与"PROCESSING 超时卡死（消费者崩溃）"两类任务。
     * 限量 {@link #RECONCILE_BATCH_LIMIT} + 超时门槛 {@link #RECONCILE_STALE_AGE} 防风暴；
     * 若该任务已有 PENDING outbox 行则不重复补写。返回本次实际补写 outbox 的条数。
     */
    @Transactional
    public int reconcileStaleTasks() {
        Instant cutoff = Instant.now().minus(RECONCILE_STALE_AGE);
        List<VectorIndexTaskEntity> stalePending =
                taskRepository.findStalePending(cutoff, RECONCILE_BATCH_LIMIT);
        List<VectorIndexTaskEntity> staleProcessing =
                taskRepository.findStaleProcessing(cutoff, RECONCILE_BATCH_LIMIT);

        int recovered = 0;
        for (VectorIndexTaskEntity task : stalePending) {
            recovered += reissueOutboxIfAbsent(task);
        }
        for (VectorIndexTaskEntity task : staleProcessing) {
            boolean superseded = taskRepository
                    .existsByResourceTypeAndResourceIdAndChunkIndexAndOperationAndStatus(
                            task.getResourceType(), task.getResourceId(), task.getChunkIndex(),
                            task.getOperation(), VectorTaskStatus.PENDING.name());
            if (superseded) {
                task.setStatus(VectorTaskStatus.FAILED.name());
                task.setLastError(truncate("消费者中断恢复时发现已有更新的 PENDING 任务，本任务作废"));
                task.touch();
                taskRepository.save(task);
                log.warn("卡死 PROCESSING 任务已被更新 PENDING 任务取代，置 FAILED taskId={} resourceType={} resourceId={}",
                        task.getId(), task.getResourceType(), task.getResourceId());
                continue;
            }
            task.setStatus(VectorTaskStatus.PENDING.name());
            task.touch();
            taskRepository.save(task);
            recovered += reissueOutboxIfAbsent(task);
        }
        if (recovered > 0) {
            log.info("向量任务对账补投 stalePending={} staleProcessing={} recovered={}",
                    stalePending.size(), staleProcessing.size(), recovered);
        }
        return recovered;
    }

    private int reissueOutboxIfAbsent(VectorIndexTaskEntity task) {
        TaskType taskType = toTaskType(task);
        if (outboxEventRepository.existsByTaskTypeAndTaskIdAndStatus(
                taskType.wireName(), task.getId(), OutboxEventStatus.PENDING)) {
            return 0;
        }
        outboxEventService.recordPending(toTaskMessage(task));
        return 1;
    }

    private static TaskType toTaskType(VectorIndexTaskEntity entity) {
        return VectorTaskOperation.UPSERT.name().equals(entity.getOperation())
                ? TaskType.VECTOR_UPSERT
                : TaskType.VECTOR_DELETE;
    }

    private static TaskMessage toTaskMessage(VectorIndexTaskEntity entity) {
        return new TaskMessage(
                toTaskType(entity),
                entity.getId(),
                entity.getResourceId(),
                entity.getOperation(),
                Instant.now());
    }

    private static TaskLoad toTaskLoad(VectorIndexTaskEntity entity) {
        return new TaskLoad(
                entity.getId(),
                entity.getUserId(),
                VectorResourceType.fromWire(entity.getResourceType()),
                entity.getResourceId(),
                entity.getChunkIndex(),
                VectorTaskOperation.valueOf(entity.getOperation()));
    }

    private MindMapEntity requireMindmap(Long mindmapId, Long userId) {
        MindMapEntity mindMap = mindMapRepository.findById(mindmapId)
                .orElseThrow(() -> new IllegalArgumentException("思维导图不存在：" + mindmapId));
        requireOwned(mindMap.getUserId(), userId);
        return mindMap;
    }

    private static void requireOwned(Long resourceUserId, Long userId) {
        if (resourceUserId == null || !resourceUserId.equals(userId)) {
            throw new IllegalArgumentException("资源不属于当前用户，拒绝投递向量任务");
        }
    }

    private VectorIndexTaskEntity getTask(Long taskId) {
        return taskRepository.findById(taskId)
                .orElseThrow(() -> new IllegalStateException("向量任务不存在：" + taskId));
    }

    static String nodeResourceId(Long mindmapId, String nodeId) {
        return mindmapId + ":" + nodeId;
    }

    private static String truncate(String error) {
        if (error == null) {
            return "(无错误信息)";
        }
        return error.length() > ERROR_MAX_LENGTH ? error.substring(0, ERROR_MAX_LENGTH) : error;
    }

    /** worker 认领后的任务快照。 */
    public record TaskLoad(
            Long taskId,
            Long userId,
            VectorResourceType resourceType,
            String resourceId,
            int chunkIndex,
            VectorTaskOperation operation) {
    }
}
