package com.example.aimindmap.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.aimindmap.mqsupport.MqTopology;
import com.example.aimindmap.mqsupport.TaskMessage;
import com.example.aimindmap.mqsupport.TaskType;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;

/**
 * 阶段六-3 摘要链路 MQ 集成测试（复用 4-4 Testcontainers 设施，无 Docker 自动跳过）：
 * a) outbox→MQ→消费：22 条消息会话摘要成功，会话三列更新、幂等行 1、DLQ=0、重试队列空；
 * b) ChatModel 先抛 TransientAiException 一次再成功：消息真实经过 5s 重试队列后成功，
 *    ChatModel.call 恰好 2 次；
 * c) 恒抛 NonTransientAiException：摘要列保持 NULL、DLQ=1（校验 messageId 头与 payload
 *    四字段）、幂等行 0；
 * d) 同 messageId 手工直投两次：ChatModel.call 总次数恰 1、摘要只更新一次、第二条 ack。
 * broker 一律 Testcontainers 临时实例，不用开发机 5672；不碰 redis/Dify/任何现有容器。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Import(MqIntegrationMocks.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SessionSummaryMqIntegrationTest extends AbstractMqIntegrationTest {

    private static final String QUEUE = MqTopology.queueName(TaskType.SESSION_SUMMARY);
    private static final String RETRY_5S =
            MqTopology.retryQueueName(TaskType.SESSION_SUMMARY, MqTopology.RetryTier.FIRST);
    private static final String RETRY_30S =
            MqTopology.retryQueueName(TaskType.SESSION_SUMMARY, MqTopology.RetryTier.SECOND);
    private static final String RETRY_2M =
            MqTopology.retryQueueName(TaskType.SESSION_SUMMARY, MqTopology.RetryTier.THIRD);

    private static final long USER_ID = 888001L;

    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper objectMapper;

    @BeforeEach
    void insertUserAndStubSuccessChatModel() {
        jdbc.update("INSERT INTO users(id, username, password_hash, major) VALUES (?, ?, 'x', '计算机')",
                USER_ID, "it" + USER_ID);
        when(MqIntegrationMocks.CHAT_MODEL.call(any(Prompt.class)))
                .thenAnswer(invocation -> successResponse());
    }

    @AfterEach
    void cleanupSummaryRows() {
        // 子类 @AfterEach 先于基类：先删幂等/outbox（无外键），再按外键顺序删消息、会话、用户。
        jdbc.update("DELETE FROM idempotent_consumptions WHERE message_id LIKE 'SESSION_SUMMARY:888%'");
        jdbc.update("DELETE FROM outbox_events WHERE task_type = 'SESSION_SUMMARY' "
                + "AND task_id BETWEEN 888000 AND 888999");
        jdbc.update("DELETE FROM ai_chat_messages WHERE session_id BETWEEN 888000 AND 888999");
        jdbc.update("DELETE FROM ai_chat_sessions WHERE id BETWEEN 888000 AND 888999");
        jdbc.update("DELETE FROM users WHERE id BETWEEN 888000 AND 888999");
    }

    @Test
    void pendingOutboxSummarizesSessionWritesIdempotentRowAndKeepsQueuesEmpty() {
        long sessionId = 888511L;
        insertSessionAndMessages(sessionId, 888520L, 22);
        long triggerMessageId = 888520L + 22 - 1;
        insertOutbox(triggerMessageId, sessionId);

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(summaryIsNull(sessionId)).isFalse());

        assertThat(jdbc.queryForObject(
                "SELECT summarized_until_message_id FROM ai_chat_sessions WHERE id = ?",
                Long.class, sessionId)).isEqualTo(888531L);
        assertThat(jdbc.queryForObject(
                "SELECT summary_updated_at IS NOT NULL FROM ai_chat_sessions WHERE id = ?",
                Boolean.class, sessionId)).isTrue();
        assertThat(idempotentCount(triggerMessageId)).isEqualTo(1);
        verify(MqIntegrationMocks.CHAT_MODEL, times(1)).call(any(Prompt.class));

        await().atMost(Duration.ofSeconds(5)).until(() -> MqTestBroker.messageCount(QUEUE) == 0);
        assertThat(MqTestBroker.messageCount(RETRY_5S)).isZero();
        assertThat(MqTestBroker.messageCount(RETRY_30S)).isZero();
        assertThat(MqTestBroker.messageCount(RETRY_2M)).isZero();
        assertThat(MqTestBroker.messageCount(MqTopology.DEAD_LETTER_QUEUE)).isZero();
    }

    @Test
    void transientFailureOnceTravelsFirstRetryQueueThenRecoversAndCallsChatModelTwice() {
        long sessionId = 888611L;
        insertSessionAndMessages(sessionId, 888620L, 22);
        long triggerMessageId = 888620L + 22 - 1;
        insertOutbox(triggerMessageId, sessionId);
        when(MqIntegrationMocks.CHAT_MODEL.call(any(Prompt.class)))
                .thenThrow(new TransientAiException("上游暂时不可用"))
                .thenAnswer(invocation -> successResponse());

        // 第 1 次处理失败：消息真实进入 5s 重试队列
        await().atMost(Duration.ofSeconds(10)).until(() ->
                MqTestBroker.messageCount(RETRY_5S) == 1);
        assertThat(summaryIsNull(sessionId)).isTrue();

        // 5s TTL 到期回到工作队列，第 2 次处理成功
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(summaryIsNull(sessionId)).isFalse());

        verify(MqIntegrationMocks.CHAT_MODEL, times(2)).call(any(Prompt.class));
        assertThat(idempotentCount(triggerMessageId)).isEqualTo(1);
        await().atMost(Duration.ofSeconds(5)).until(() ->
                MqTestBroker.messageCount(QUEUE) == 0
                        && MqTestBroker.messageCount(RETRY_5S) == 0
                        && MqTestBroker.messageCount(RETRY_30S) == 0
                        && MqTestBroker.messageCount(RETRY_2M) == 0);
        assertThat(MqTestBroker.messageCount(MqTopology.DEAD_LETTER_QUEUE)).isZero();
    }

    @Test
    void nonTransientFailureKeepsSummaryNullAndOneMessageReachesDeadLetterQueue() throws Exception {
        long sessionId = 888711L;
        insertSessionAndMessages(sessionId, 888720L, 22);
        long triggerMessageId = 888720L + 22 - 1;
        insertOutbox(triggerMessageId, sessionId);
        when(MqIntegrationMocks.CHAT_MODEL.call(any(Prompt.class)))
                .thenThrow(new NonTransientAiException("配置错误"));

        await().atMost(Duration.ofSeconds(20)).until(() ->
                MqTestBroker.messageCount(MqTopology.DEAD_LETTER_QUEUE) == 1);

        Message dead = MqTestBroker.driver().receive(MqTopology.DEAD_LETTER_QUEUE, 5000);
        assertThat(dead).isNotNull();
        assertThat(dead.getMessageProperties().getMessageId())
                .isEqualTo("SESSION_SUMMARY:" + triggerMessageId);
        TaskMessage payload = objectMapper.readValue(dead.getBody(), TaskMessage.class);
        assertThat(payload.taskType()).isEqualTo(TaskType.SESSION_SUMMARY);
        assertThat(payload.taskId()).isEqualTo(triggerMessageId);
        assertThat(payload.entityId()).isEqualTo(String.valueOf(sessionId));
        assertThat(payload.op()).isEqualTo("SUMMARY");

        assertThat(summaryIsNull(sessionId)).isTrue();
        assertThat(idempotentCount(triggerMessageId)).isZero();
        assertThat(MqTestBroker.messageCount(RETRY_5S)).isZero();
        assertThat(MqTestBroker.messageCount(RETRY_30S)).isZero();
        assertThat(MqTestBroker.messageCount(RETRY_2M)).isZero();
    }

    @Test
    void duplicateDeliveryOfSameMessageIdSummarizesExactlyOnceAndAcksBoth() {
        long sessionId = 888811L;
        insertSessionAndMessages(sessionId, 888820L, 22);
        long triggerMessageId = 888820L + 22 - 1;
        // 不写 outbox：直接把同一 messageId 的消息手工投递两次到工作队列
        publishSummaryMessage(triggerMessageId, sessionId);
        publishSummaryMessage(triggerMessageId, sessionId);

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(summaryIsNull(sessionId)).isFalse());

        assertThat(idempotentCount(triggerMessageId)).isEqualTo(1);
        verify(MqIntegrationMocks.CHAT_MODEL, times(1)).call(any(Prompt.class));
        await().atMost(Duration.ofSeconds(5)).until(() ->
                MqTestBroker.messageCount(QUEUE) == 0
                        && MqTestBroker.messageCount(RETRY_5S) == 0
                        && MqTestBroker.messageCount(MqTopology.DEAD_LETTER_QUEUE) == 0);
    }

    // ---------- helpers ----------

    private ChatResponse successResponse() {
        return new ChatResponse(List.of(new Generation(new AssistantMessage("摘".repeat(900)))));
    }

    private void insertSessionAndMessages(long sessionId, long firstMessageId, int count) {
        jdbc.update("INSERT INTO ai_chat_sessions(id, user_id, title) VALUES (?, ?, 'it888-summary')",
                sessionId, USER_ID);
        for (int i = 0; i < count; i++) {
            long messageId = firstMessageId + i;
            String role = (i + 1) % 2 == 0 ? "assistant" : "user";
            jdbc.update("INSERT INTO ai_chat_messages(id, session_id, role, content) "
                            + "VALUES (?, ?, ?, ?)",
                    messageId, sessionId, role, "message-" + (i + 1));
        }
    }

    private void insertOutbox(long triggerMessageId, long sessionId) {
        try {
            String payload = objectMapper.writeValueAsString(new TaskMessage(
                    TaskType.SESSION_SUMMARY, triggerMessageId, String.valueOf(sessionId),
                    "SUMMARY", Instant.now()));
            jdbc.update("INSERT INTO outbox_events(task_type, task_id, payload, status, attempts) "
                    + "VALUES ('SESSION_SUMMARY', ?, ?, 'PENDING', 0)", triggerMessageId, payload);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void publishSummaryMessage(long triggerMessageId, long sessionId) {
        try {
            TaskMessage payload = new TaskMessage(TaskType.SESSION_SUMMARY, triggerMessageId,
                    String.valueOf(sessionId), "SUMMARY", Instant.now());
            MessageProperties properties = new MessageProperties();
            properties.setMessageId("SESSION_SUMMARY:" + triggerMessageId);
            properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
            properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
            properties.setContentEncoding(StandardCharsets.UTF_8.name());
            Message message = new Message(objectMapper.writeValueAsBytes(payload), properties);
            MqTestBroker.driver().send(MqTopology.TASK_EXCHANGE,
                    MqTopology.routingKey(TaskType.SESSION_SUMMARY), message);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private boolean summaryIsNull(long sessionId) {
        return jdbc.queryForObject(
                "SELECT summary IS NULL FROM ai_chat_sessions WHERE id = ?",
                Boolean.class, sessionId);
    }

    private int idempotentCount(long triggerMessageId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotent_consumptions WHERE message_id = ?",
                Integer.class, "SESSION_SUMMARY:" + triggerMessageId);
        return count == null ? 0 : count;
    }
}
