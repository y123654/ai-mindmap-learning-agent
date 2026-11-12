package com.example.aimindmap.question;

import com.example.aimindmap.vectorindex.VectorIndexTaskService;
import com.example.aimindmap.ai.AiGateway;
import com.example.aimindmap.ai.AiGatewayException;
import com.example.aimindmap.auth.ExternalIdCodec;
import com.example.aimindmap.ai.AiJsonRequest;
import com.example.aimindmap.ai.AiJsonResult;
import com.example.aimindmap.ai.QuestionPromptService;
import com.example.aimindmap.ai.dto.AiQuestion;
import com.example.aimindmap.ai.dto.AiQuestionOption;
import com.example.aimindmap.ai.dto.AiQuestionResponse;
import com.example.aimindmap.common.error.ApiException;
import com.example.aimindmap.document.DocumentEntity;
import com.example.aimindmap.document.DocumentRepository;
import com.example.aimindmap.mqsupport.AiRetryPolicy;
import com.example.aimindmap.mqsupport.RetriableTaskException;
import com.example.aimindmap.mqsupport.TaskMessage;
import com.example.aimindmap.mqsupport.TaskType;
import com.example.aimindmap.mqsupport.outbox.IdempotentConsumptionEntity;
import com.example.aimindmap.mqsupport.outbox.IdempotentConsumptionRepository;
import com.example.aimindmap.mqsupport.outbox.OutboxEventRepository;
import com.example.aimindmap.mqsupport.outbox.OutboxEventService;
import com.example.aimindmap.mqsupport.outbox.OutboxEventStatus;
import com.example.aimindmap.mindmap.GraphJsonCodec;
import com.example.aimindmap.mindmap.MindMapEntity;
import com.example.aimindmap.mindmap.MindMapRepository;
import com.example.aimindmap.mindmap.graph.MindMapGraph;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.transaction.Transactional;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
 * v1.1 阶段 4：题目生成、查询、手动编辑和单题重新生成。
 *
 * <p>阶段六-2 起出题链路双模式（与 SyncJobService 同构）：
 * MQ 模式（app.mq.enabled=true，缺省）创建任务同事务写 outbox，由
 * {@link QuestionGenerationMessageConsumer} 条件认领后调
 * {@link #processGenerationJob(Long)}；降级模式（mq=false）完整保留 afterCommit
 * {@link QuestionGenerationRequested} 事件 + {@link QuestionGenerationWorker} @Async。
 */
@Service
public class QuestionService {

    private static final Logger log = LoggerFactory.getLogger(QuestionService.class);

    private static final String AI_PENDING_STATUS = "pending_confirmation";
    private static final String HUMAN_CONFIRMED_STATUS = "active";

    /**
     * 对账超时门槛：PENDING/RUNNING 超过 5 分钟才视为漏发或消费者崩溃。
     * 正常 outbox 投递与单次出题均为秒级，不会误伤。
     */
    static final Duration RECONCILE_STALE_AGE = Duration.ofMinutes(5);
    /** 单轮对账最多补投的行数，防止风暴。 */
    static final int RECONCILE_BATCH_LIMIT = 20;

    private final QuestionRepository questionRepository;
    private final QuestionGenerationJobRepository jobRepository;
    private final MindMapRepository mindMapRepository;
    private final DocumentRepository documentRepository;
    private final GraphJsonCodec graphJsonCodec;
    private final QuestionValidator validator;
    private final QuestionPromptService promptService;
    private final AiGateway aiGateway;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;
    private final QuestionService self;
    private final ExternalIdCodec idCodec;
    private final VectorIndexTaskService vectorIndexTaskService;
    private final OutboxEventService outboxEventService;
    private final OutboxEventRepository outboxEventRepository;
    private final IdempotentConsumptionRepository idempotentConsumptionRepository;
    private final boolean mqEnabled;

    public QuestionService(QuestionRepository questionRepository,
                           QuestionGenerationJobRepository jobRepository,
                           MindMapRepository mindMapRepository,
                           DocumentRepository documentRepository,
                           GraphJsonCodec graphJsonCodec,
                           QuestionValidator validator,
                           QuestionPromptService promptService,
                           AiGateway aiGateway,
                           ObjectMapper objectMapper,
                           ApplicationEventPublisher eventPublisher,
                          @Lazy QuestionService self,
                          ExternalIdCodec idCodec,
                          VectorIndexTaskService vectorIndexTaskService,
                          OutboxEventService outboxEventService,
                          OutboxEventRepository outboxEventRepository,
                          IdempotentConsumptionRepository idempotentConsumptionRepository,
                          @Value("${app.mq.enabled:true}") boolean mqEnabled) {
        this.questionRepository = questionRepository;
        this.jobRepository = jobRepository;
        this.mindMapRepository = mindMapRepository;
        this.documentRepository = documentRepository;
        this.graphJsonCodec = graphJsonCodec;
        this.validator = validator;
        this.promptService = promptService;
        this.aiGateway = aiGateway;
        this.objectMapper = objectMapper;
        this.eventPublisher = eventPublisher;
        this.self = self;
        this.idCodec = idCodec;
        this.vectorIndexTaskService = vectorIndexTaskService;
        this.outboxEventService = outboxEventService;
        this.outboxEventRepository = outboxEventRepository;
        this.idempotentConsumptionRepository = idempotentConsumptionRepository;
        this.mqEnabled = mqEnabled;
    }

    @Transactional
    public QuestionGenerationJobEntity createGenerationJob(Long mindmapId,
                                                           QuestionDtos.GenerateQuestionsRequest rawRequest) {
        MindMapEntity mindmap = getMindmapOrThrow(mindmapId);
        MindMapGraph graph = graphJsonCodec.read(mindmap.getGraph());
        QuestionDtos.GenerateQuestionsRequest request = validator.validateRequest(rawRequest, graph);

        QuestionGenerationJobEntity job = new QuestionGenerationJobEntity();
        job.setMindmapId(mindmapId);
        job.setStatus("PENDING");
        job.setRequest(writeJson(request));
        QuestionGenerationJobEntity saved = jobRepository.save(job);

        if (mqEnabled) {
            // 阶段六-2（缺省）：任务行与 outbox 同一事务原子落库（事务性发件箱），
            // 不再注册 afterCommit 事件、不再 publishEvent；由 OutboxRelay 投递、
            // QuestionGenerationMessageConsumer 条件认领后执行。
            outboxEventService.recordPending(new TaskMessage(
                    TaskType.QUESTION_GENERATE, saved.getId(),
                    String.valueOf(mindmapId), "GENERATE", Instant.now()));
        } else if (TransactionSynchronizationManager.isSynchronizationActive()) {
            // §3.5 降级路径（mq=false）：行为与阶段 4 完全一致——
            // 事务提交后再让后台线程执行，避免后台读不到未提交数据
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    eventPublisher.publishEvent(new QuestionGenerationRequested(saved.getId()));
                }
            });
        } else {
            // 无活动事务（理论上不应发生）：保持阶段 4 直接发事件的兜底行为
            eventPublisher.publishEvent(new QuestionGenerationRequested(saved.getId()));
        }
        return saved;
    }

    public QuestionGenerationJobEntity getJob(Long jobId) {
        return jobRepository.findById(jobId)
                .orElseThrow(() -> ApiException.notFound("题目生成任务不存在：" + jobId));
    }

    public List<QuestionEntity> listQuestions(Long mindmapId) {
        getMindmapOrThrow(mindmapId);
        return questionRepository.findByMindmapIdOrderByIdAsc(mindmapId);
    }

    /**
     * 后台编排结果（阶段六-2）：MQ 消费者据此决定 ack / 重试 / DLQ。
     * COMPLETED 成功；EARLY_EXIT 任务行不存在或已终态（非错误）；FAILED_BUSINESS 业务失败已落 FAILED；
     * FAILED_CONFIGURATION 配置类失败已落 FAILED（消息进 DLQ 仅作运维信号）。
     * 基础设施瞬时故障不落任何终态，直接抛 {@link RetriableTaskException} 走 MQ 三档重试。
     */
    public enum GenerationOutcome {
        COMPLETED,
        FAILED_BUSINESS,
        FAILED_CONFIGURATION,
        EARLY_EXIT
    }

    /**
     * 后台任务入口：AI 调用不放在数据库事务中。
     *
     * <p>MQ 模式由 {@link QuestionGenerationMessageConsumer} 调用，调用前任务已被 DB 原子条件
     * 认领到 RUNNING；降级模式（mq=false）由 {@link QuestionGenerationWorker} @Async 调用，
     * 任务仍为 PENDING，{@link #markJobRunning(Long)} 同时兼容两种状态。
     */
    public GenerationOutcome processGenerationJob(Long jobId) {
        QuestionGenerationJobEntity initial = jobRepository.findById(jobId).orElse(null);
        if (initial == null) {
            // MQ 模式下该情况已被消费者 jobExists 拦截（进 DLQ）；只剩 mq=false 的异常竞态会到这里。
            log.warn("题目生成任务行不存在，提前退出 jobId={}", jobId);
            return GenerationOutcome.EARLY_EXIT;
        }
        try {
            QuestionGenerationJobEntity job = self.markJobRunning(jobId);
            MindMapEntity mindmap = getMindmapOrThrow(job.getMindmapId());
            MindMapGraph graph = graphJsonCodec.read(mindmap.getGraph());
            QuestionDtos.GenerateQuestionsRequest request =
                    readRequest(job.getRequest(), graph);

            AiJsonRequest aiRequest = promptService.buildRequest(
                    request.subject(), request.grade(), request.questionTypes(),
                    request.difficulty(), request.count(), graph, request.nodeIds());
            AiJsonResult<AiQuestionResponse> result =
                    aiGateway.completeJsonForResult(aiRequest, AiQuestionResponse.class);
            validator.validateAiResponse(result.value(), request, graph);
            List<Long> questionIds = self.saveGeneratedQuestions(
                    jobId, job.getMindmapId(), mindmap.getUserId(), request, result.value(), aiGateway.model());
            self.markJobCompleted(jobId, questionIds);
            return GenerationOutcome.COMPLETED;
        } catch (AiGatewayException e) {
            if (AiRetryPolicy.isRetriableCode(e.getCode())) {
                // AI_TIMEOUT / AI_HTTP_429 / AI_HTTP_5xx：不落任何终态，交 MQ 三档重试
                log.warn("题目生成 AI 调用遇到可重试错误，任务不落终态，转延时重试 jobId={} code={}",
                        jobId, e.getCode());
                throw new RetriableTaskException("retriable AI failure: " + e.getCode(), e);
            }
            if (AiRetryPolicy.isConfigurationCode(e.getCode())) {
                // AI_NOT_CONFIGURED / 4xx（429 除外）：配置类问题，重试无意义，落 FAILED；
                // 消息进 DLQ 仅作运维信号（任务已是 FAILED 终态）
                log.warn("题目生成 AI 配置类错误，任务落 FAILED，消息进 DLQ 作运维信号 jobId={} code={} message={}",
                        jobId, e.getCode(), e.getMessage());
                self.markJobFailed(jobId, e);
                return GenerationOutcome.FAILED_CONFIGURATION;
            }
            // 其余 AI 码（AI_QUESTION_INVALID、AI_INPUT_TOO_LARGE、AI_RESPONSE_* 等内容/响应类）：业务失败
            log.warn("题目生成 AI 业务失败 jobId={} code={} message={}",
                    jobId, e.getCode(), e.getMessage(), e);
            self.markJobFailed(jobId, e);
            return GenerationOutcome.FAILED_BUSINESS;
        } catch (DataAccessException e) {
            // 基础设施瞬时故障：不落 FAILED，抛可重试异常交 MQ 三档重试（对账亦会兜底）
            log.error("题目生成遇数据库异常，不落终态，转可重试 jobId={}", jobId, e);
            throw new RetriableTaskException(
                    "question generation DataAccessException: " + safeMessage(e), e);
        } catch (ApiException e) {
            // markJobRunning 对已终态任务抛 INVALID_JOB_STATUS：非错误早退；其余业务校验失败落 FAILED
            QuestionGenerationJobEntity current = jobRepository.findById(jobId).orElse(null);
            if (current != null && isTerminalStatus(current.getStatus())) {
                log.info("题目生成任务已终态，提前退出 jobId={} status={} code={}",
                        jobId, current.getStatus(), e.getCode());
                return GenerationOutcome.EARLY_EXIT;
            }
            log.warn("题目生成业务校验失败 jobId={} code={} message={}",
                    jobId, e.getCode(), e.getMessage());
            self.markJobFailed(jobId, e);
            return GenerationOutcome.FAILED_BUSINESS;
        } catch (Exception e) {
            // 后台出题失败必须在后端日志可见（含堆栈），便于定位 AI/校验/存储问题
            log.error("题目生成任务失败 jobId={}", jobId, e);
            self.markJobFailed(jobId, e);
            return GenerationOutcome.FAILED_BUSINESS;
        }
    }

    /**
     * MQ 模式任务已由消费者条件 UPDATE 置为 RUNNING，直接放行；
     * mq=false @Async 路径任务仍为 PENDING，由本方法读-改-写置为 RUNNING。
     * COMPLETED/FAILED 终态一律拒绝（抛 INVALID_JOB_STATUS），由 processGenerationJob 转 EARLY_EXIT。
     */
    @Transactional
    public QuestionGenerationJobEntity markJobRunning(Long jobId) {
        QuestionGenerationJobEntity job = getJob(jobId);
        String status = job.getStatus();
        if ("RUNNING".equals(status)) {
            // MQ 消费者已 CAS 认领（或 mq=false 事件重复进入已运行行）：保持认领结果
            return job;
        }
        if (!"PENDING".equals(status)) {
            throw ApiException.conflict("INVALID_JOB_STATUS",
                    "题目任务不是待处理状态：" + status);
        }
        job.setStatus("RUNNING");
        return jobRepository.save(job);
    }

    @Transactional
    public List<Long> saveGeneratedQuestions(Long jobId,
                                             Long mindmapId,
                                             Long userId,
                                             QuestionDtos.GenerateQuestionsRequest request,
                                             AiQuestionResponse aiResponse,
                                             String aiModel) {
        getJob(jobId);
        List<QuestionEntity> entities = new ArrayList<>();
        for (AiQuestion aiQuestion : aiResponse.safeQuestions()) {
            QuestionEntity entity = new QuestionEntity();
            entity.setUserId(userId);
            entity.setMindmapId(mindmapId);
            entity.setNodeIds(writeJson(distinct(aiQuestion.nodeIds())));
            entity.setSubject(request.subject());
            entity.setGrade(request.grade());
            entity.setQuestionType(aiQuestion.questionType().trim());
            entity.setDifficulty(aiQuestion.difficulty().trim());
            entity.setStem(aiQuestion.stem().trim());
            entity.setOptions(aiQuestion.options() == null || aiQuestion.options().isEmpty()
                    ? null : writeJson(aiQuestion.options()));
            entity.setAnswer(writeJson(aiQuestion.answer()));
            entity.setExplanation(aiQuestion.explanation().trim());
            entity.setAiModel(aiModel);
            entity.setStatus(AI_PENDING_STATUS);
            entities.add(entity);
        }
        List<Long> questionIds = questionRepository.saveAll(entities).stream()
                .map(QuestionEntity::getId).toList();
        for (Long questionId : questionIds) {
            vectorIndexTaskService.enqueueQuestion(userId, questionId);
        }
        return questionIds;
    }

    @Transactional
    public QuestionGenerationJobEntity markJobCompleted(Long jobId, List<Long> questionIds) {
        QuestionGenerationJobEntity job = getJob(jobId);
        job.setStatus("COMPLETED");
        job.setResultQuestionIds(writeJson(questionIds));
        job.setErrorCode(null);
        job.setErrorMessage(null);
        job.setCompletedAt(Instant.now());
        QuestionGenerationJobEntity saved = jobRepository.save(job);
        // 幂等三件套之三：COMPLETED 终态与幂等记录同一事务；唯一冲突外抛由消费者按重复投递 ack。
        idempotentConsumptionRepository.saveAndFlush(
                new IdempotentConsumptionEntity(
                        TaskType.QUESTION_GENERATE.wireName() + ":" + jobId));
        return saved;
    }

    @Transactional
    public void markJobFailed(Long jobId, Exception e) {
        QuestionGenerationJobEntity job = jobRepository.findById(jobId).orElse(null);
        if (job == null || "COMPLETED".equals(job.getStatus())) {
            return;
        }
        job.setStatus("FAILED");
        if (e instanceof AiGatewayException aiException) {
            job.setErrorCode(aiException.getCode());
        } else if (e instanceof ApiException apiException) {
            job.setErrorCode(apiException.getCode());
        } else {
            job.setErrorCode("QUESTION_GENERATION_FAILED");
        }
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        job.setErrorMessage(message.length() > 1000 ? message.substring(0, 1000) : message);
        job.setCompletedAt(Instant.now());
        jobRepository.save(job);
        // FAILED 终态与幂等记录同一事务写入；唯一冲突外抛由消费者按重复投递 ack。
        idempotentConsumptionRepository.saveAndFlush(
                new IdempotentConsumptionEntity(
                        TaskType.QUESTION_GENERATE.wireName() + ":" + jobId));
    }

    // ---------- 阶段六-2：MQ 条件认领 / 重试复位 / 对账 ----------

    /**
     * 消费者条件认领（幂等三件套之一）：仅 PENDING 行能被 DB 原子条件 UPDATE 置为 RUNNING；
     * 0 行说明任务已终态或已被认领（重复投递/重试竞态），调用方直接 ack。
     */
    @Transactional
    public boolean claimForMessage(Long jobId) {
        return jobRepository.claimForConsumer(jobId) > 0;
    }

    /** 任务行是否存在：条件认领 0 行时区分"重复投递"与"毒消息（任务行不存在）"。 */
    @Transactional
    public boolean jobExists(Long jobId) {
        return jobRepository.existsById(jobId);
    }

    /**
     * 可重试失败转延时重试前复位任务行：RUNNING→PENDING，
     * 重试消息到期回来后才能被 {@link #claimForMessage(Long)} 再次认领。
     */
    @Transactional
    public boolean requeueForMessageRetry(Long jobId) {
        int updated = jobRepository.requeueForRetry(jobId);
        if (updated == 0) {
            log.warn("题目生成任务重试复位未命中 RUNNING 行（可能存在并发或已终态） jobId={}", jobId);
        }
        return updated > 0;
    }

    /**
     * 对账（MQ 开启时 {@link QuestionGenerationReconciler} 每 60s 调用一次）：
     * 补投 PENDING 超时漏发（含消息丢失）；RUNNING 超时（消费者崩溃/重试耗尽进 DLQ 后
     * 基础设施恢复）复位 PENDING 并补写 outbox；已有 PENDING outbox 不重复补写。
     * 返回实际补写 outbox 的条数。年龄锚点只有 created_at（本表无 updated_at，取舍与 6-1 一致）。
     */
    @Transactional
    public int reconcileStaleJobs() {
        Instant cutoff = Instant.now().minus(RECONCILE_STALE_AGE);
        List<QuestionGenerationJobEntity> stalePending =
                jobRepository.findStalePending(cutoff, RECONCILE_BATCH_LIMIT);
        List<QuestionGenerationJobEntity> staleRunning =
                jobRepository.findStaleProcessing(cutoff, RECONCILE_BATCH_LIMIT);

        for (QuestionGenerationJobEntity job : staleRunning) {
            job.setStatus("PENDING");
            jobRepository.save(job);
            log.warn("题目生成任务卡在 RUNNING 超过 {}，复位为 PENDING 等待补投 jobId={} mindmapId={}",
                    RECONCILE_STALE_AGE, job.getId(), job.getMindmapId());
        }

        int recovered = 0;
        for (QuestionGenerationJobEntity job : stalePending) {
            recovered += reissueOutboxIfAbsent(job);
        }
        for (QuestionGenerationJobEntity job : staleRunning) {
            recovered += reissueOutboxIfAbsent(job);
        }
        if (recovered > 0) {
            log.info("题目生成任务对账补投 stalePending={} staleRunning={} recovered={}",
                    stalePending.size(), staleRunning.size(), recovered);
        }
        return recovered;
    }

    private int reissueOutboxIfAbsent(QuestionGenerationJobEntity job) {
        if (outboxEventRepository.existsByTaskTypeAndTaskIdAndStatus(
                TaskType.QUESTION_GENERATE.wireName(), job.getId(), OutboxEventStatus.PENDING)) {
            return 0;
        }
        outboxEventService.recordPending(new TaskMessage(
                TaskType.QUESTION_GENERATE, job.getId(),
                String.valueOf(job.getMindmapId()), "GENERATE", Instant.now()));
        return 1;
    }

    private static boolean isTerminalStatus(String status) {
        return "COMPLETED".equals(status) || "FAILED".equals(status);
    }

    private static String safeMessage(Exception e) {
        String message = e.getMessage();
        return message == null ? e.getClass().getSimpleName() : message;
    }

    public QuestionEntity regenerateQuestion(Long questionId) {
        QuestionEntity existing = getQuestionOrThrow(questionId);
        MindMapEntity mindmap = getMindmapOrThrow(existing.getMindmapId());
        MindMapGraph graph = graphJsonCodec.read(mindmap.getGraph());
        List<String> nodeIds = readStringList(existing.getNodeIds());
        QuestionDtos.GenerateQuestionsRequest request = new QuestionDtos.GenerateQuestionsRequest(
                existing.getSubject(), existing.getGrade(), List.of(existing.getQuestionType()),
                existing.getDifficulty(), 1, nodeIds);
        QuestionDtos.GenerateQuestionsRequest validRequest = validator.validateRequest(request, graph);

        AiJsonRequest aiRequest = promptService.buildRequest(
                validRequest.subject(), validRequest.grade(), validRequest.questionTypes(),
                validRequest.difficulty(), 1, graph, nodeIds);
        AiJsonResult<AiQuestionResponse> result =
                aiGateway.completeJsonForResult(aiRequest, AiQuestionResponse.class);
        validator.validateAiResponse(result.value(), validRequest, graph);
        AiQuestion fresh = result.value().safeQuestions().get(0);
        return self.applyRegeneratedQuestion(existing.getId(), fresh, aiGateway.model());
    }

    @Transactional
    public QuestionEntity applyRegeneratedQuestion(Long questionId, AiQuestion fresh, String aiModel) {
        QuestionEntity question = getQuestionOrThrow(questionId);
        question.setQuestionType(fresh.questionType().trim());
        question.setDifficulty(fresh.difficulty().trim());
        question.setStem(fresh.stem().trim());
        question.setOptions(fresh.options() == null || fresh.options().isEmpty()
                ? null : writeJson(fresh.options()));
        question.setAnswer(writeJson(fresh.answer()));
        question.setExplanation(fresh.explanation().trim());
        question.setNodeIds(writeJson(distinct(fresh.nodeIds())));
        question.setAiModel(aiModel);
        question.setStatus(AI_PENDING_STATUS);
        question.setUpdatedAt(Instant.now());
        QuestionEntity saved = questionRepository.save(question);
        vectorIndexTaskService.enqueueQuestion(saved.getUserId(), saved.getId());
        return saved;
    }

    public List<QuestionDtos.QuestionListItemResponse> listQuestionsForUser(Long userId) {
        List<QuestionEntity> questions = questionRepository.findByUserIdOrderByUpdatedAtDescIdDesc(userId);
        if (questions.isEmpty()) {
            return List.of();
        }

        List<Long> mindmapIds = questions.stream().map(QuestionEntity::getMindmapId).distinct().toList();
        Map<Long, MindMapEntity> mindmapById = new HashMap<>();
        mindMapRepository.findAllById(mindmapIds).forEach(mindmap -> mindmapById.put(mindmap.getId(), mindmap));

        List<Long> documentIds = mindmapById.values().stream()
                .map(MindMapEntity::getDocumentId).distinct().toList();
        Map<Long, DocumentEntity> documentById = new HashMap<>();
        documentRepository.findAllById(documentIds).forEach(document -> documentById.put(document.getId(), document));

        List<QuestionDtos.QuestionListItemResponse> result = new ArrayList<>();
        for (QuestionEntity question : questions) {
            MindMapEntity mindmap = mindmapById.get(question.getMindmapId());
            Long documentId = mindmap == null ? null : mindmap.getDocumentId();
            DocumentEntity document = documentId == null ? null : documentById.get(documentId);
            result.add(new QuestionDtos.QuestionListItemResponse(
                    idCodec.encode(question.getId()),
                    idCodec.encode(question.getMindmapId()),
                    idCodec.encode(documentId),
                    document == null ? "未知笔记" : document.getTitle(),
                    question.getSubject(),
                    question.getGrade(),
                    question.getQuestionType(),
                    question.getDifficulty(),
                    question.getStem(),
                    readJson(question.getOptions()),
                    readJson(question.getAnswer()),
                    question.getExplanation(),
                    readJson(question.getNodeIds()),
                    question.getStatus(),
                    question.getCreatedAt(),
                    question.getUpdatedAt()
            ));
        }
        return result;
    }

    public QuestionEntity getQuestionOrThrow(Long questionId) {
        return questionRepository.findById(questionId)
                .orElseThrow(() -> ApiException.notFound("题目不存在：" + questionId));
    }

    private MindMapEntity getMindmapOrThrow(Long mindmapId) {
        return mindMapRepository.findById(mindmapId)
                .orElseThrow(() -> ApiException.notFound("思维导图不存在：" + mindmapId));
    }

    private QuestionDtos.GenerateQuestionsRequest readRequest(String json, MindMapGraph graph) {
        try {
            QuestionDtos.GenerateQuestionsRequest parsed =
                    objectMapper.readValue(json, QuestionDtos.GenerateQuestionsRequest.class);
            return validator.validateRequest(parsed, graph);
        } catch (JsonProcessingException e) {
            throw ApiException.badRequest("QUESTION_JOB_REQUEST_INVALID", "题目任务参数无法读取");
        }
    }

    private List<String> distinct(List<String> values) {
        return values == null ? List.of() : values.stream().map(String::trim).distinct().toList();
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("题目 JSON 序列化失败", e);
        }
    }

    private Object readJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, com.fasterxml.jackson.databind.JsonNode.class);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private List<String> readStringList(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {
            });
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }
}
