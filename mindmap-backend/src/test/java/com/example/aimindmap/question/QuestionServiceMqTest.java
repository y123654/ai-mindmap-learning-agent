package com.example.aimindmap.question;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.aimindmap.ai.AiGateway;
import com.example.aimindmap.ai.AiGatewayException;
import com.example.aimindmap.ai.AiJsonRequest;
import com.example.aimindmap.ai.AiJsonResult;
import com.example.aimindmap.ai.AiUsage;
import com.example.aimindmap.ai.QuestionPromptService;
import com.example.aimindmap.ai.dto.AiQuestion;
import com.example.aimindmap.ai.dto.AiQuestionResponse;
import com.example.aimindmap.auth.ExternalIdCodec;
import com.example.aimindmap.common.error.ApiException;
import com.example.aimindmap.document.DocumentRepository;
import com.example.aimindmap.mindmap.GraphJsonCodec;
import com.example.aimindmap.mindmap.MindMapEntity;
import com.example.aimindmap.mindmap.MindMapRepository;
import com.example.aimindmap.mindmap.graph.MindMapGraph;
import com.example.aimindmap.mindmap.graph.MindMapNode;
import com.example.aimindmap.mqsupport.RetriableTaskException;
import com.example.aimindmap.mqsupport.TaskMessage;
import com.example.aimindmap.mqsupport.TaskType;
import com.example.aimindmap.mqsupport.outbox.IdempotentConsumptionEntity;
import com.example.aimindmap.mqsupport.outbox.IdempotentConsumptionRepository;
import com.example.aimindmap.mqsupport.outbox.OutboxEventRepository;
import com.example.aimindmap.mqsupport.outbox.OutboxEventService;
import com.example.aimindmap.mqsupport.outbox.OutboxEventStatus;
import com.example.aimindmap.vectorindex.VectorIndexTaskService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.lang.reflect.Field;
import java.time.Instant;
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
 * 阶段六-2：QuestionService 的 MQ 改造单测（纯 Mockito，无真实 DB/MQ/LLM）。
 * 历史出题后台流程无单测，本类只覆盖本次新增/改动的分支，密度对齐 SyncJobServiceMqTest。
 */
class QuestionServiceMqTest {

    private static final long JOB_ID = 10L;
    private static final long MINDMAP_ID = 777001L;
    private static final long USER_ID = 888500L;
    private static final long QUESTION_ID = 888901L;

    private QuestionRepository questionRepository;
    private QuestionGenerationJobRepository jobRepository;
    private MindMapRepository mindMapRepository;
    private DocumentRepository documentRepository;
    private GraphJsonCodec graphJsonCodec;
    private QuestionPromptService promptService;
    private AiGateway aiGateway;
    private ApplicationEventPublisher eventPublisher;
    private ExternalIdCodec idCodec;
    private VectorIndexTaskService vectorIndexTaskService;
    private OutboxEventService outboxEventService;
    private OutboxEventRepository outboxEventRepository;
    private IdempotentConsumptionRepository idempotentConsumptionRepository;

    private final QuestionValidator validator = new QuestionValidator();
    private final ObjectMapper objectMapper = new ObjectMapper()
            .findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private QuestionGenerationJobEntity job;
    private MindMapEntity mindmap;
    private MindMapGraph graph;

    @BeforeEach
    void setUp() throws Exception {
        questionRepository = Mockito.mock(QuestionRepository.class);
        jobRepository = Mockito.mock(QuestionGenerationJobRepository.class);
        mindMapRepository = Mockito.mock(MindMapRepository.class);
        documentRepository = Mockito.mock(DocumentRepository.class);
        graphJsonCodec = Mockito.mock(GraphJsonCodec.class);
        promptService = Mockito.mock(QuestionPromptService.class);
        aiGateway = Mockito.mock(AiGateway.class);
        eventPublisher = Mockito.mock(ApplicationEventPublisher.class);
        idCodec = Mockito.mock(ExternalIdCodec.class);
        vectorIndexTaskService = Mockito.mock(VectorIndexTaskService.class);
        outboxEventService = Mockito.mock(OutboxEventService.class);
        outboxEventRepository = Mockito.mock(OutboxEventRepository.class);
        idempotentConsumptionRepository = Mockito.mock(IdempotentConsumptionRepository.class);

        job = new QuestionGenerationJobEntity();
        ReflectionTestUtils.setField(job, "id", JOB_ID);
        job.setMindmapId(MINDMAP_ID);
        job.setStatus("PENDING");
        job.setRequest(objectMapper.writeValueAsString(new QuestionDtos.GenerateQuestionsRequest(
                "数学", "高一", List.of("short_answer"), "easy", 1, List.of())));

        mindmap = new MindMapEntity();
        ReflectionTestUtils.setField(mindmap, "id", MINDMAP_ID);
        mindmap.setUserId(USER_ID);
        mindmap.setGraph("{\"rootId\":\"root\"}");

        graph = new MindMapGraph("root", 1,
                new MindMapNode("root", "标题", null, "markdown", null, null, List.of(), Instant.now()),
                Instant.now());

        when(jobRepository.findById(JOB_ID)).thenReturn(Optional.of(job));
        when(jobRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(mindMapRepository.findById(MINDMAP_ID)).thenReturn(Optional.of(mindmap));
        when(graphJsonCodec.read(any())).thenReturn(graph);
        when(promptService.buildRequest(any(), any(), any(), any(), anyInt(), any(), any()))
                .thenReturn(new AiJsonRequest("system", "user", true, 100, 0.2));
        when(aiGateway.completeJsonForResult(any(), eq(AiQuestionResponse.class)))
                .thenReturn(successResult());
        when(aiGateway.model()).thenReturn("it-model");
        when(questionRepository.saveAll(any())).thenAnswer(invocation -> {
            List<QuestionEntity> entities = invocation.getArgument(0);
            entities.forEach(entity -> ReflectionTestUtils.setField(entity, "id", QUESTION_ID));
            return entities;
        });
    }

    private QuestionService newService(boolean mqEnabled) {
        QuestionService service = new QuestionService(
                questionRepository, jobRepository, mindMapRepository, documentRepository,
                graphJsonCodec, validator, promptService, aiGateway, objectMapper, eventPublisher,
                null, idCodec, vectorIndexTaskService, outboxEventService, outboxEventRepository,
                idempotentConsumptionRepository, mqEnabled);
        // self 自注入：构造器无法自引用，反射回填本实例（单测无 Spring 代理，@Transactional 不影响分支）
        try {
            Field selfField = QuestionService.class.getDeclaredField("self");
            selfField.setAccessible(true);
            selfField.set(service, service);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return service;
    }

    private AiJsonResult<AiQuestionResponse> successResult() {
        AiQuestion question = new AiQuestion("short_answer", "easy", "1+1 等于几？", null,
                objectMapper.createObjectNode().put("referenceAnswer", "等于 2"),
                "基础加法", List.of("root"));
        return new AiJsonResult<>(new AiQuestionResponse(List.of(question)), AiUsage.empty(), 1L);
    }

    private QuestionGenerationJobEntity freshPendingJob() {
        job.setStatus("PENDING");
        job.setErrorCode(null);
        job.setErrorMessage(null);
        return job;
    }

    // ---------- 创建任务双模式发布 ----------

    @Test
    void mqEnabledCreateJobWritesOutboxAndPublishesNoSpringEvent() {
        when(jobRepository.save(any())).thenAnswer(invocation -> {
            QuestionGenerationJobEntity saved = invocation.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", JOB_ID);
            return saved;
        });
        QuestionService service = newService(true);

        service.createGenerationJob(MINDMAP_ID, new QuestionDtos.GenerateQuestionsRequest(
                "数学", "高一", List.of("short_answer"), "easy", 1, List.of()));

        ArgumentCaptor<TaskMessage> captor = ArgumentCaptor.forClass(TaskMessage.class);
        verify(outboxEventService).recordPending(captor.capture());
        TaskMessage message = captor.getValue();
        assertThat(message.taskType()).isEqualTo(TaskType.QUESTION_GENERATE);
        assertThat(message.taskId()).isEqualTo(JOB_ID);
        assertThat(message.entityId()).isEqualTo(String.valueOf(MINDMAP_ID));
        assertThat(message.op()).isEqualTo("GENERATE");
        assertThat(message.occurredAt()).isNotNull();
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void mqDisabledCreateJobKeepsDirectEventBehaviorAndWritesNoOutbox() {
        when(jobRepository.save(any())).thenAnswer(invocation -> {
            QuestionGenerationJobEntity saved = invocation.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", JOB_ID);
            return saved;
        });
        QuestionService service = newService(false);

        service.createGenerationJob(MINDMAP_ID, new QuestionDtos.GenerateQuestionsRequest(
                "数学", "高一", List.of("short_answer"), "easy", 1, List.of()));

        verify(outboxEventService, never()).recordPending(any());
        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue()).isInstanceOf(QuestionGenerationRequested.class);
        assertThat(((QuestionGenerationRequested) eventCaptor.getValue()).jobId()).isEqualTo(JOB_ID);
    }

    // ---------- 正常流 / 两种入口状态兼容 ----------

    @Test
    void pendingPathReachesCompletedEnqueuesVectorsAndWritesIdempotentRow() {
        QuestionService service = newService(true);

        QuestionService.GenerationOutcome outcome = service.processGenerationJob(JOB_ID);

        assertThat(outcome).isEqualTo(QuestionService.GenerationOutcome.COMPLETED);
        assertThat(job.getStatus()).isEqualTo("COMPLETED");
        assertThat(job.getResultQuestionIds()).contains(String.valueOf(QUESTION_ID));
        verify(vectorIndexTaskService).enqueueQuestion(USER_ID, QUESTION_ID);
        ArgumentCaptor<IdempotentConsumptionEntity> captor =
                ArgumentCaptor.forClass(IdempotentConsumptionEntity.class);
        verify(idempotentConsumptionRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getMessageId()).isEqualTo("QUESTION_GENERATE:" + JOB_ID);
    }

    @Test
    void runningAlreadyClaimedPathAlsoCompletes() {
        // MQ 消费者已 CAS 置 RUNNING：markJobRunning 必须放行且不再要求 PENDING
        job.setStatus("RUNNING");
        QuestionService service = newService(true);

        QuestionService.GenerationOutcome outcome = service.processGenerationJob(JOB_ID);

        assertThat(outcome).isEqualTo(QuestionService.GenerationOutcome.COMPLETED);
        assertThat(job.getStatus()).isEqualTo("COMPLETED");
    }

    // ---------- AI 异常分级 ----------

    @Test
    void retriableAiCodesBubbleUpWithoutTerminalState() {
        for (String code : List.of("AI_TIMEOUT", "AI_HTTP_429", "AI_HTTP_503")) {
            QuestionGenerationJobEntity freshJob = freshPendingJob();
            when(aiGateway.completeJsonForResult(any(), eq(AiQuestionResponse.class)))
                    .thenThrow(new AiGatewayException(code, "transient " + code));
            QuestionService service = newService(true);

            assertThatThrownBy(() -> service.processGenerationJob(JOB_ID))
                    .isInstanceOf(RetriableTaskException.class)
                    .hasMessageContaining(code)
                    .hasCauseInstanceOf(AiGatewayException.class);

            assertThat(freshJob.getStatus()).isEqualTo("RUNNING");
            verify(idempotentConsumptionRepository, never()).saveAndFlush(any());
            verify(vectorIndexTaskService, never()).enqueueQuestion(anyLong(), anyLong());
            Mockito.reset(aiGateway, idempotentConsumptionRepository, vectorIndexTaskService);
            when(aiGateway.completeJsonForResult(any(), eq(AiQuestionResponse.class)))
                    .thenThrow(new AiGatewayException(code, "transient " + code));
        }
    }

    @Test
    void configurationAiCodesFailConfigurationAndWriteFailedTerminal() throws Exception {
        for (String code : List.of("AI_NOT_CONFIGURED", "AI_HTTP_401")) {
            freshPendingJob();
            when(aiGateway.completeJsonForResult(any(), eq(AiQuestionResponse.class)))
                    .thenThrow(new AiGatewayException(code, "config " + code));
            QuestionService service = newService(true);

            QuestionService.GenerationOutcome outcome = service.processGenerationJob(JOB_ID);

            assertThat(outcome).isEqualTo(QuestionService.GenerationOutcome.FAILED_CONFIGURATION);
            assertThat(job.getStatus()).isEqualTo("FAILED");
            assertThat(job.getErrorCode()).isEqualTo(code);
            verify(idempotentConsumptionRepository).saveAndFlush(any(IdempotentConsumptionEntity.class));
            setUp();
        }
    }

    @Test
    void businessAiCodeFailsBusiness() {
        when(aiGateway.completeJsonForResult(any(), eq(AiQuestionResponse.class)))
                .thenThrow(new AiGatewayException("AI_QUESTION_INVALID", "bad question"));
        QuestionService service = newService(true);

        QuestionService.GenerationOutcome outcome = service.processGenerationJob(JOB_ID);

        assertThat(outcome).isEqualTo(QuestionService.GenerationOutcome.FAILED_BUSINESS);
        assertThat(job.getStatus()).isEqualTo("FAILED");
        assertThat(job.getErrorCode()).isEqualTo("AI_QUESTION_INVALID");
        verify(idempotentConsumptionRepository).saveAndFlush(any(IdempotentConsumptionEntity.class));
    }

    @Test
    void plainApiExceptionDuringProcessingFailsBusiness() {
        when(mindMapRepository.findById(MINDMAP_ID)).thenReturn(Optional.empty());
        QuestionService service = newService(true);

        QuestionService.GenerationOutcome outcome = service.processGenerationJob(JOB_ID);

        assertThat(outcome).isEqualTo(QuestionService.GenerationOutcome.FAILED_BUSINESS);
        assertThat(job.getStatus()).isEqualTo("FAILED");
        assertThat(job.getErrorCode()).isEqualTo("NOT_FOUND");
    }

    @Test
    void unexpectedExceptionKeepsGenericQuestionFailedBusinessSemantics() {
        when(aiGateway.completeJsonForResult(any(), eq(AiQuestionResponse.class)))
                .thenThrow(new IllegalStateException("boom"));
        QuestionService service = newService(true);

        QuestionService.GenerationOutcome outcome = service.processGenerationJob(JOB_ID);

        assertThat(outcome).isEqualTo(QuestionService.GenerationOutcome.FAILED_BUSINESS);
        assertThat(job.getStatus()).isEqualTo("FAILED");
        assertThat(job.getErrorCode()).isEqualTo("QUESTION_GENERATION_FAILED");
    }

    @Test
    void failedErrorMessageIsTruncatedToThousandChars() {
        String longMessage = "x".repeat(1500);
        when(aiGateway.completeJsonForResult(any(), eq(AiQuestionResponse.class)))
                .thenThrow(new RuntimeException(longMessage));
        QuestionService service = newService(true);

        service.processGenerationJob(JOB_ID);

        assertThat(job.getStatus()).isEqualTo("FAILED");
        assertThat(job.getErrorCode()).isEqualTo("QUESTION_GENERATION_FAILED");
        assertThat(job.getErrorMessage()).hasSize(1000);
    }

    // ---------- 数据库异常 / 早退 ----------

    @Test
    void dataAccessExceptionWhileSavingIsRetriableWithoutFailedTerminal() {
        Mockito.doThrow(new DataAccessResourceFailureException("db down"))
                .when(questionRepository).saveAll(any());
        QuestionService service = newService(true);

        assertThatThrownBy(() -> service.processGenerationJob(JOB_ID))
                .isInstanceOf(RetriableTaskException.class);
        assertThat(job.getStatus()).isEqualTo("RUNNING");
        verify(idempotentConsumptionRepository, never()).saveAndFlush(any());
    }

    @Test
    void missingJobReturnsEarlyExit() {
        when(jobRepository.findById(JOB_ID)).thenReturn(Optional.empty());
        QuestionService service = newService(true);

        QuestionService.GenerationOutcome outcome = service.processGenerationJob(JOB_ID);

        assertThat(outcome).isEqualTo(QuestionService.GenerationOutcome.EARLY_EXIT);
        verify(aiGateway, never()).completeJsonForResult(any(), any());
    }

    @Test
    void completedJobReturnsEarlyExitWithoutAiCall() {
        job.setStatus("COMPLETED");
        QuestionService service = newService(true);

        QuestionService.GenerationOutcome outcome = service.processGenerationJob(JOB_ID);

        assertThat(outcome).isEqualTo(QuestionService.GenerationOutcome.EARLY_EXIT);
        assertThat(job.getStatus()).isEqualTo("COMPLETED");
        verify(aiGateway, never()).completeJsonForResult(any(), any());
        verify(idempotentConsumptionRepository, never()).saveAndFlush(any());
    }

    // ---------- 条件认领 / 重试复位（SQL 语义另由 IT 覆盖） ----------

    @Test
    void claimForMessageDelegatesToAtomicUpdate() {
        when(jobRepository.claimForConsumer(JOB_ID)).thenReturn(1);
        QuestionService service = newService(true);
        assertThat(service.claimForMessage(JOB_ID)).isTrue();

        when(jobRepository.claimForConsumer(99L)).thenReturn(0);
        assertThat(service.claimForMessage(99L)).isFalse();
    }

    @Test
    void jobExistsDelegatesToRepository() {
        when(jobRepository.existsById(JOB_ID)).thenReturn(true);
        QuestionService service = newService(true);
        assertThat(service.jobExists(JOB_ID)).isTrue();
    }

    @Test
    void requeueForMessageRetryDelegatesToAtomicUpdate() {
        when(jobRepository.requeueForRetry(JOB_ID)).thenReturn(1);
        QuestionService service = newService(true);
        assertThat(service.requeueForMessageRetry(JOB_ID)).isTrue();

        when(jobRepository.requeueForRetry(99L)).thenReturn(0);
        assertThat(service.requeueForMessageRetry(99L)).isFalse();
    }

    // ---------- 对账 ----------

    @Test
    void reconcileResetsStaleRunningAndReissuesOutboxForBothLists() {
        QuestionGenerationJobEntity stalePending = staleJob(21L, "PENDING");
        QuestionGenerationJobEntity staleRunning = staleJob(22L, "RUNNING");
        when(jobRepository.findStalePending(any(), anyInt())).thenReturn(List.of(stalePending));
        when(jobRepository.findStaleProcessing(any(), anyInt())).thenReturn(List.of(staleRunning));
        when(outboxEventRepository.existsByTaskTypeAndTaskIdAndStatus(
                eq(TaskType.QUESTION_GENERATE.wireName()), anyLong(), eq(OutboxEventStatus.PENDING)))
                .thenReturn(false);
        QuestionService service = newService(true);

        int recovered = service.reconcileStaleJobs();

        assertThat(recovered).isEqualTo(2);
        assertThat(staleRunning.getStatus()).isEqualTo("PENDING");
        ArgumentCaptor<TaskMessage> captor = ArgumentCaptor.forClass(TaskMessage.class);
        verify(outboxEventService, Mockito.times(2)).recordPending(captor.capture());
        assertThat(captor.getAllValues()).extracting(TaskMessage::taskId).containsExactly(21L, 22L);
    }

    @Test
    void reconcileSkipsJobsThatAlreadyHavePendingOutbox() {
        QuestionGenerationJobEntity stalePending = staleJob(23L, "PENDING");
        when(jobRepository.findStalePending(any(), anyInt())).thenReturn(List.of(stalePending));
        when(jobRepository.findStaleProcessing(any(), anyInt())).thenReturn(List.of());
        when(outboxEventRepository.existsByTaskTypeAndTaskIdAndStatus(
                TaskType.QUESTION_GENERATE.wireName(), 23L, OutboxEventStatus.PENDING)).thenReturn(true);
        QuestionService service = newService(true);

        assertThat(service.reconcileStaleJobs()).isZero();
        verify(outboxEventService, never()).recordPending(any());
    }

    // ---------- 终态守卫 ----------

    @Test
    void markJobFailedOnCompletedJobIsGuardedAndWritesNothing() {
        job.setStatus("COMPLETED");
        QuestionService service = newService(true);

        service.markJobFailed(JOB_ID, ApiException.badRequest("X", "y"));

        assertThat(job.getStatus()).isEqualTo("COMPLETED");
        verify(jobRepository, never()).save(any());
        verify(idempotentConsumptionRepository, never()).saveAndFlush(any());
    }

    // ---------- helpers ----------

    private QuestionGenerationJobEntity staleJob(long id, String status) {
        QuestionGenerationJobEntity stale = new QuestionGenerationJobEntity();
        ReflectionTestUtils.setField(stale, "id", id);
        stale.setMindmapId(MINDMAP_ID);
        stale.setStatus(status);
        return stale;
    }
}
