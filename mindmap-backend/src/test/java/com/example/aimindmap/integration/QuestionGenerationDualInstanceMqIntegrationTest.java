package com.example.aimindmap.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.aimindmap.ai.AiJsonResult;
import com.example.aimindmap.ai.AiUsage;
import com.example.aimindmap.ai.dto.AiQuestion;
import com.example.aimindmap.ai.dto.AiQuestionResponse;
import com.example.aimindmap.mqsupport.MqTopology;
import com.example.aimindmap.mqsupport.TaskMessage;
import com.example.aimindmap.mqsupport.TaskType;
import com.example.aimindmap.question.QuestionDtos;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 阶段六-2 验收打回补测：8083/8084 双实例同一个出题任务只执行一次。两个应用上下文
 * （同一 PG it888 schema、同一 Testcontainers broker、同一个 static AiGateway mock）同时在线，
 * 只发布一条消息；DB 原子条件认领 + 终态幂等行保证跨实例只出题一次；重复投递认领 0 行直接 ack。
 */
class QuestionGenerationDualInstanceMqIntegrationTest extends AbstractMqIntegrationTest {

    private static final long USER_ID = 888603L;
    private static final long DOCUMENT_ID = 888603L;
    private static final long MINDMAP_ID = 888703L;
    private static final long JOB_ID = 888803L;

    private static final String GRAPH_JSON =
            "{\"rootId\":\"root\",\"version\":1,"
                    + "\"tree\":{\"id\":\"root\",\"label\":\"标题\",\"children\":[]},"
                    + "\"updatedAt\":\"2026-10-19T00:00:00Z\"}";

    private final ObjectMapper objectMapper = new ObjectMapper()
            .findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @AfterEach
    void cleanupQuestionRows() {
        JdbcTemplate jdbc = cleanupJdbc();
        // 顺序照抄 QuestionGenerationMqIntegrationTest.cleanupQuestionRows：
        // 先删题目衍生的向量行/任务（题目主键非 888 段，基类清理不到），再删出题任务/题目/导图，
        // 基类 @AfterEach 随后再补删 888 段向量残留、文档与用户。
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
    void twoInstancesProcessQuestionJobExactlyOnce() throws Exception {
        JdbcTemplate jdbc = cleanupJdbc();
        ConfigurableApplicationContext instanceA = startContext(8083);
        ConfigurableApplicationContext instanceB = startContext(8084);
        try {
            String workQueue = MqTopology.queueName(TaskType.QUESTION_GENERATE);
            await().atMost(Duration.ofSeconds(20)).until(() -> queueExists(workQueue));

            insertFixtures(jdbc);
            when(MqIntegrationMocks.AI_GATEWAY.completeJsonForResult(any(), eq(AiQuestionResponse.class)))
                    .thenAnswer(invocation -> successResult());
            when(MqIntegrationMocks.AI_GATEWAY.model()).thenReturn("it-model");

            publishQuestionMessage(JOB_ID);

            await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                    assertThat(jdbc.queryForObject(
                            "SELECT status FROM question_generation_jobs WHERE id = ?",
                            String.class, JOB_ID)).isEqualTo("COMPLETED"));

            // 跨两实例：题目恰好 1 条、AI 恰好 1 次、幂等行 1 条、向量任务入队 1 条
            assertThat(questionCount(jdbc)).isEqualTo(1);
            verify(MqIntegrationMocks.AI_GATEWAY, times(1))
                    .completeJsonForResult(any(), eq(AiQuestionResponse.class));
            assertThat(idempotentCount(jdbc)).isEqualTo(1);
            assertThat(vectorTaskCount(jdbc)).isEqualTo(1);

            // 同一消息再投一次：条件认领 0 行，直接 ack，题目/AI 调用/幂等/向量任务全部不变
            publishQuestionMessage(JOB_ID);
            await().atMost(Duration.ofSeconds(10)).until(() ->
                    MqTestBroker.messageCount(workQueue) == 0);
            Thread.sleep(Duration.ofSeconds(1).toMillis());
            assertThat(questionCount(jdbc)).isEqualTo(1);
            verify(MqIntegrationMocks.AI_GATEWAY, times(1))
                    .completeJsonForResult(any(), eq(AiQuestionResponse.class));
            assertThat(idempotentCount(jdbc)).isEqualTo(1);
            assertThat(vectorTaskCount(jdbc)).isEqualTo(1);
        } finally {
            instanceA.close();
            instanceB.close();
        }
    }

    // ---------- helpers ----------

    private void insertFixtures(JdbcTemplate jdbc) {
        jdbc.update("INSERT INTO users(id, username, password_hash, major) VALUES (?, ?, 'x', '计算机')",
                USER_ID, "it" + USER_ID);
        jdbc.update("INSERT INTO documents(id, title, content, content_version, source_type, status, user_id) "
                        + "VALUES (?, 'it888-question-dual', '# 标题', 1, 'markdown', 'active', ?)",
                DOCUMENT_ID, USER_ID);
        jdbc.update("INSERT INTO mindmaps(id, document_id, graph, graph_version, sync_status, user_id) "
                        + "VALUES (?, ?, CAST(? AS jsonb), 1, 'idle', ?)",
                MINDMAP_ID, DOCUMENT_ID, GRAPH_JSON, USER_ID);
        try {
            String request = objectMapper.writeValueAsString(new QuestionDtos.GenerateQuestionsRequest(
                    "数学", "高一", List.of("short_answer"), "easy", 1, List.of()));
            jdbc.update("INSERT INTO question_generation_jobs(id, mindmap_id, status, request, created_at) "
                            + "VALUES (?, ?, 'PENDING', CAST(? AS jsonb), ?)",
                    JOB_ID, MINDMAP_ID, request, LocalDateTime.ofInstant(Instant.now(), ZoneOffset.UTC));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private AiJsonResult<AiQuestionResponse> successResult() {
        AiQuestion question = new AiQuestion("short_answer", "easy", "1+1 等于几？", null,
                objectMapper.createObjectNode().put("referenceAnswer", "等于 2"),
                "基础加法", List.of("root"));
        return new AiJsonResult<>(new AiQuestionResponse(List.of(question)), AiUsage.empty(), 1L);
    }

    private void publishQuestionMessage(long jobId) throws Exception {
        TaskMessage payload = new TaskMessage(TaskType.QUESTION_GENERATE, jobId,
                String.valueOf(MINDMAP_ID), "GENERATE", Instant.now());
        MessageProperties properties = new MessageProperties();
        properties.setMessageId("QUESTION_GENERATE:" + jobId);
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setContentEncoding(StandardCharsets.UTF_8.name());
        Message message = new Message(objectMapper.writeValueAsBytes(payload), properties);
        MqTestBroker.driver().send(MqTopology.TASK_EXCHANGE,
                MqTopology.routingKey(TaskType.QUESTION_GENERATE), message);
    }

    private Integer questionCount(JdbcTemplate jdbc) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM questions WHERE mindmap_id = ?", Integer.class, MINDMAP_ID);
    }

    private Integer idempotentCount(JdbcTemplate jdbc) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotent_consumptions WHERE message_id = ?",
                Integer.class, "QUESTION_GENERATE:" + JOB_ID);
    }

    private Integer vectorTaskCount(JdbcTemplate jdbc) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM vector_index_tasks WHERE resource_type = 'question' "
                        + "AND resource_id IN (SELECT id::text FROM questions WHERE mindmap_id = ?)",
                Integer.class, MINDMAP_ID);
    }

    private boolean queueExists(String queueName) {
        try {
            MqTestBroker.messageCount(queueName);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}