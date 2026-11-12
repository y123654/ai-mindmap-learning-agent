package com.example.aimindmap.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.aimindmap.ai.AiGatewayException;
import com.example.aimindmap.ai.AiJsonResult;
import com.example.aimindmap.ai.AiUsage;
import com.example.aimindmap.ai.dto.AiQuestion;
import com.example.aimindmap.ai.dto.AiQuestionResponse;
import com.example.aimindmap.mqsupport.MqTopology;
import com.example.aimindmap.mqsupport.TaskMessage;
import com.example.aimindmap.mqsupport.TaskType;
import com.example.aimindmap.question.QuestionDtos;
import com.example.aimindmap.question.QuestionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;

/**
 * 阶段六-2 出题链路 MQ 集成测试（复用 4-4 Testcontainers 设施，无 Docker 自动跳过）：
 * a) outbox→MQ→条件认领→COMPLETED 正常流，result_question_ids 落库，每题向量入队，DLQ=0；
 * b) AI 先抛 AI_HTTP_429 一次再成功：消息真实经过 5s 重试队列后最终 COMPLETED；
 * c) AI_NOT_CONFIGURED：任务落 FAILED 且 DLQ 收到 1 条（配置类运维信号）；
 * d) 条件认领/重试复位/对账的原生 SQL 语义；
 * e) 同一 messageId 重复投递两次只处理一次：题目不重复生成，第二条直接 ack。
 * broker 一律 Testcontainers 临时实例，不用开发机 5672；不碰 redis/Dify/任何现有容器。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Import(MqIntegrationMocks.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class QuestionGenerationMqIntegrationTest extends AbstractMqIntegrationTest {

    private static final String QUEUE = MqTopology.queueName(TaskType.QUESTION_GENERATE);
    private static final String RETRY_5S =
            MqTopology.retryQueueName(TaskType.QUESTION_GENERATE, MqTopology.RetryTier.FIRST);
    private static final String RETRY_30S =
            MqTopology.retryQueueName(TaskType.QUESTION_GENERATE, MqTopology.RetryTier.SECOND);
    private static final String RETRY_2M =
            MqTopology.retryQueueName(TaskType.QUESTION_GENERATE, MqTopology.RetryTier.THIRD);

    private static final long USER_ID = 888801L;
    private static final long DOCUMENT_ID = 888801L;
    private static final long MINDMAP_ID = 888901L;

    private static final String GRAPH_JSON =
            "{\"rootId\":\"root\",\"version\":1,"
                    + "\"tree\":{\"id\":\"root\",\"label\":\"标题\",\"children\":[]},"
                    + "\"updatedAt\":\"2026-10-19T00:00:00Z\"}";

    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private QuestionService questionService;

    @BeforeEach
    void insertBaseFixturesAndStubSuccessGateway() {
        jdbc.update("INSERT INTO users(id, username, password_hash, major) VALUES (?, ?, 'x', '计算机')",
                USER_ID, "it" + USER_ID);
        jdbc.update("INSERT INTO documents(id, title, content, content_version, source_type, status, user_id) "
                        + "VALUES (?, 'it888-question', '# 标题', 1, 'markdown', 'active', ?)",
                DOCUMENT_ID, USER_ID);
        jdbc.update("INSERT INTO mindmaps(id, document_id, graph, graph_version, sync_status, user_id) "
                        + "VALUES (?, ?, CAST(? AS jsonb), 1, 'idle', ?)",
                MINDMAP_ID, DOCUMENT_ID, GRAPH_JSON, USER_ID);
        when(MqIntegrationMocks.AI_GATEWAY.completeJsonForResult(any(), eq(AiQuestionResponse.class)))
                .thenAnswer(invocation -> successResult());
        when(MqIntegrationMocks.AI_GATEWAY.model()).thenReturn("it-model");
    }

    @AfterEach
    void cleanupQuestionRows() {
        // 子类 @AfterEach 先于基类：先删题目衍生的向量行/任务（题目主键非 888 段，基类清理不到），
        // 再删出题任务/题目/导图，基类随后删 888 段文档与用户。
        jdbc.update("DELETE FROM vector_index_entries WHERE resource_type = 'question' AND resource_id IN ("
                + "SELECT id::text FROM questions WHERE mindmap_id BETWEEN 888000 AND 888999)");
        jdbc.update("DELETE FROM outbox_events WHERE task_type = 'VECTOR_UPSERT' AND task_id IN ("
                + "SELECT id FROM vector_index_tasks WHERE resource_type = 'question' AND resource_id IN ("
                + "SELECT id::text FROM questions WHERE mindmap_id BETWEEN 888000 AND 888999))");
        jdbc.update("DELETE FROM idempotent_consumptions WHERE message_id IN ("
                + "SELECT 'VECTOR_UPSERT:' || id FROM vector_index_tasks "
                + "WHERE resource_type = 'question' AND resource_id IN ("
                + "SELECT id::text FROM questions WHERE mindmap_id BETWEEN 888000 AND 888999))");
        jdbc.update("DELETE FROM vector_index_tasks WHERE resource_type = 'question' AND resource_id IN ("
                + "SELECT id::text FROM questions WHERE mindmap_id BETWEEN 888000 AND 888999)");
        jdbc.update("DELETE FROM outbox_events WHERE task_type = 'QUESTION_GENERATE' AND task_id IN ("
                + "SELECT id FROM question_generation_jobs WHERE mindmap_id BETWEEN 888000 AND 888999)");
        jdbc.update("DELETE FROM idempotent_consumptions WHERE message_id IN ("
                + "SELECT 'QUESTION_GENERATE:' || id FROM question_generation_jobs "
                + "WHERE mindmap_id BETWEEN 888000 AND 888999)");
        jdbc.update("DELETE FROM questions WHERE mindmap_id BETWEEN 888000 AND 888999");
        jdbc.update("DELETE FROM question_generation_jobs WHERE mindmap_id BETWEEN 888000 AND 888999");
        jdbc.update("DELETE FROM mindmaps WHERE id BETWEEN 888000 AND 888999");
        jdbc.update("DELETE FROM documents WHERE id BETWEEN 888000 AND 888999");
        jdbc.update("DELETE FROM users WHERE id BETWEEN 888000 AND 888999");
    }

    @Test
    void pendingOutboxReachesCompletedWithQuestionsAndVectorTasksAndEmptyDeadLetterQueue() {
        long jobId = insertJob(888911L, "PENDING", Instant.now());
        insertOutbox(jobId);

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(jobStatus(jobId)).isEqualTo("COMPLETED"));

        long questionId = jdbc.queryForObject(
                "SELECT id FROM questions WHERE mindmap_id = ?", Long.class, MINDMAP_ID);
        assertThat(jdbc.queryForObject(
                "SELECT result_question_ids IS NOT NULL FROM question_generation_jobs WHERE id = ?",
                Boolean.class, jobId)).isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM vector_index_tasks WHERE resource_type = 'question' AND resource_id = ?",
                Integer.class, String.valueOf(questionId))).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotent_consumptions WHERE message_id = ?",
                Integer.class, "QUESTION_GENERATE:" + jobId)).isEqualTo(1);
        verify(MqIntegrationMocks.AI_GATEWAY, times(1))
                .completeJsonForResult(any(), eq(AiQuestionResponse.class));

        await().atMost(Duration.ofSeconds(5)).until(() -> MqTestBroker.messageCount(QUEUE) == 0);
        assertThat(MqTestBroker.messageCount(RETRY_5S)).isZero();
        assertThat(MqTestBroker.messageCount(MqTopology.DEAD_LETTER_QUEUE)).isZero();
    }

    @Test
    void rateLimitedOnceTravelsFirstRetryQueueThenRecoversWithoutDuplicateQuestions() {
        long jobId = insertJob(888912L, "PENDING", Instant.now());
        insertOutbox(jobId);
        when(MqIntegrationMocks.AI_GATEWAY.completeJsonForResult(any(), eq(AiQuestionResponse.class)))
                .thenThrow(new AiGatewayException("AI_HTTP_429", "rate limited"))
                .thenAnswer(invocation -> successResult());

        // 第 1 次处理失败：消息真实进入 5s 重试队列，任务行已复位 PENDING
        await().atMost(Duration.ofSeconds(10)).until(() ->
                MqTestBroker.messageCount(RETRY_5S) == 1);
        assertThat(jobStatus(jobId)).isEqualTo("PENDING");

        // 5s TTL 到期回到工作队列，第 2 次处理成功
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(jobStatus(jobId)).isEqualTo("COMPLETED"));

        verify(MqIntegrationMocks.AI_GATEWAY, times(2))
                .completeJsonForResult(any(), eq(AiQuestionResponse.class));
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM questions WHERE mindmap_id = ?", Integer.class, MINDMAP_ID))
                .isEqualTo(1);
        await().atMost(Duration.ofSeconds(5)).until(() ->
                MqTestBroker.messageCount(QUEUE) == 0
                        && MqTestBroker.messageCount(RETRY_5S) == 0
                        && MqTestBroker.messageCount(RETRY_30S) == 0
                        && MqTestBroker.messageCount(RETRY_2M) == 0);
        assertThat(MqTestBroker.messageCount(MqTopology.DEAD_LETTER_QUEUE)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotent_consumptions WHERE message_id = ?",
                Integer.class, "QUESTION_GENERATE:" + jobId)).isEqualTo(1);
    }

    @Test
    void notConfiguredFailsJobAndOneMessageReachesDeadLetterQueue() throws Exception {
        long jobId = insertJob(888913L, "PENDING", Instant.now());
        insertOutbox(jobId);
        when(MqIntegrationMocks.AI_GATEWAY.completeJsonForResult(any(), eq(AiQuestionResponse.class)))
                .thenThrow(new AiGatewayException("AI_NOT_CONFIGURED", "not configured"));

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(jobStatus(jobId)).isEqualTo("FAILED"));
        assertThat(jdbc.queryForObject(
                "SELECT error_code FROM question_generation_jobs WHERE id = ?", String.class, jobId))
                .isEqualTo("AI_NOT_CONFIGURED");

        await().atMost(Duration.ofSeconds(10)).until(() ->
                MqTestBroker.messageCount(MqTopology.DEAD_LETTER_QUEUE) == 1);
        Message dead = MqTestBroker.driver().receive(MqTopology.DEAD_LETTER_QUEUE, 5000);
        assertThat(dead).isNotNull();
        assertThat(dead.getMessageProperties().getMessageId()).isEqualTo("QUESTION_GENERATE:" + jobId);
        TaskMessage payload = objectMapper.readValue(dead.getBody(), TaskMessage.class);
        assertThat(payload.taskType()).isEqualTo(TaskType.QUESTION_GENERATE);
        assertThat(payload.taskId()).isEqualTo(jobId);
        assertThat(payload.op()).isEqualTo("GENERATE");

        assertThat(MqTestBroker.messageCount(RETRY_5S)).isZero();
        assertThat(MqTestBroker.messageCount(RETRY_30S)).isZero();
        assertThat(MqTestBroker.messageCount(RETRY_2M)).isZero();
        // 任务已落 FAILED 终态，幂等行同事务写入；DLQ 仅作运维信号
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotent_consumptions WHERE message_id = ?",
                Integer.class, "QUESTION_GENERATE:" + jobId)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM questions WHERE mindmap_id = ?", Integer.class, MINDMAP_ID)).isZero();
    }

    @Test
    void claimRequeueAndReconcileSqlSemantics() {
        // 条件认领：仅 PENDING→RUNNING，CAS 第二次 0 行；COMPLETED/FAILED 不认领
        long claimJob = insertJob(888921L, "PENDING", Instant.now());
        assertThat(questionService.claimForMessage(claimJob)).isTrue();
        assertThat(jobStatus(claimJob)).isEqualTo("RUNNING");
        assertThat(questionService.claimForMessage(claimJob)).isFalse();
        insertJob(888922L, "COMPLETED", Instant.now());
        insertJob(888923L, "FAILED", Instant.now());
        assertThat(questionService.claimForMessage(888922L)).isFalse();
        assertThat(questionService.claimForMessage(888923L)).isFalse();

        // 重试复位：唯一中间态 RUNNING→PENDING；PENDING/COMPLETED 不命中
        long runningJob = insertJob(888931L, "RUNNING", Instant.now());
        assertThat(questionService.requeueForMessageRetry(runningJob)).isTrue();
        assertThat(jobStatus(runningJob)).isEqualTo("PENDING");
        long pendingJob = insertJob(888932L, "PENDING", Instant.now());
        assertThat(questionService.requeueForMessageRetry(pendingJob)).isFalse();
        long completedJob = insertJob(888933L, "COMPLETED", Instant.now());
        assertThat(questionService.requeueForMessageRetry(completedJob)).isFalse();

        // 对账：超 5 分钟的 PENDING 补 outbox；RUNNING 复位 PENDING 并补 outbox；新鲜行不动
        Instant old = Instant.now().minus(Duration.ofMinutes(10));
        long stalePending = insertJob(888941L, "PENDING", old);
        long staleRunning = insertJob(888951L, "RUNNING", old);
        long freshPending = insertJob(888961L, "PENDING", Instant.now());

        int recovered = questionService.reconcileStaleJobs();

        assertThat(recovered).isEqualTo(2);
        assertThat(jobStatus(staleRunning)).isEqualTo("PENDING");
        assertThat(outboxCount(stalePending)).isEqualTo(1);
        assertThat(outboxCount(staleRunning)).isEqualTo(1);
        assertThat(outboxCount(freshPending)).isZero();
        assertThat(jobStatus(freshPending)).isEqualTo("PENDING");
    }

    @Test
    void duplicateDeliveryOfSameMessageIdProcessesOnlyOnceAndAcksBoth() throws Exception {
        long jobId = insertJob(888915L, "PENDING", Instant.now());
        // 不写 outbox：直接把同一 messageId 的消息手工投递两次到工作队列
        TaskMessage message = new TaskMessage(TaskType.QUESTION_GENERATE, jobId,
                String.valueOf(MINDMAP_ID), "GENERATE", Instant.now());
        byte[] body = objectMapper.writeValueAsBytes(message);
        for (int i = 0; i < 2; i++) {
            MessageProperties properties = new MessageProperties();
            properties.setMessageId("QUESTION_GENERATE:" + jobId);
            properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
            properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
            properties.setContentEncoding("UTF-8");
            MqTestBroker.driver().send(MqTopology.TASK_EXCHANGE,
                    MqTopology.routingKey(TaskType.QUESTION_GENERATE), new Message(body, properties));
        }

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(jobStatus(jobId)).isEqualTo("COMPLETED"));

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM questions WHERE mindmap_id = ?", Integer.class, MINDMAP_ID))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotent_consumptions WHERE message_id = ?",
                Integer.class, "QUESTION_GENERATE:" + jobId)).isEqualTo(1);
        verify(MqIntegrationMocks.AI_GATEWAY, times(1))
                .completeJsonForResult(any(), eq(AiQuestionResponse.class));
        await().atMost(Duration.ofSeconds(5)).until(() -> MqTestBroker.messageCount(QUEUE) == 0);
        assertThat(MqTestBroker.messageCount(RETRY_5S)).isZero();
        assertThat(MqTestBroker.messageCount(MqTopology.DEAD_LETTER_QUEUE)).isZero();
    }

    // ---------- helpers ----------

    private AiJsonResult<AiQuestionResponse> successResult() {
        AiQuestion question = new AiQuestion("short_answer", "easy", "1+1 等于几？", null,
                objectMapper.createObjectNode().put("referenceAnswer", "等于 2"),
                "基础加法", List.of("root"));
        return new AiJsonResult<>(new AiQuestionResponse(List.of(question)), AiUsage.empty(), 1L);
    }

    private long insertJob(long id, String status, Instant createdAt) {
        try {
            String request = objectMapper.writeValueAsString(new QuestionDtos.GenerateQuestionsRequest(
                    "数学", "高一", List.of("short_answer"), "easy", 1, List.of()));
            jdbc.update("INSERT INTO question_generation_jobs(id, mindmap_id, status, request, created_at) "
                            + "VALUES (?, ?, ?, CAST(? AS jsonb), ?)",
                    id, MINDMAP_ID, status, request,
                    LocalDateTime.ofInstant(createdAt, ZoneOffset.UTC));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return id;
    }

    private void insertOutbox(long jobId) {
        try {
            String payload = objectMapper.writeValueAsString(new TaskMessage(
                    TaskType.QUESTION_GENERATE, jobId, String.valueOf(MINDMAP_ID), "GENERATE", Instant.now()));
            jdbc.update("INSERT INTO outbox_events(task_type, task_id, payload, status, attempts) "
                    + "VALUES ('QUESTION_GENERATE', ?, ?, 'PENDING', 0)", jobId, payload);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String jobStatus(long jobId) {
        return jdbc.queryForObject(
                "SELECT status FROM question_generation_jobs WHERE id = ?", String.class, jobId);
    }

    private int outboxCount(long jobId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE task_type = 'QUESTION_GENERATE' AND task_id = ?",
                Integer.class, jobId);
        return count == null ? 0 : count;
    }
}
