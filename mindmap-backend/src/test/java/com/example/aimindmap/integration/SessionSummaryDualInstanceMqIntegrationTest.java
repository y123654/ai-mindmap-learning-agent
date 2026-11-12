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
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 阶段六-3：8083/8084 双实例同一会话摘要只生成一次。两个手工启动的完整应用上下文
 * （同一 PG it888 schema、同一 Testcontainers broker、同一个 static ChatModel mock）同时在线，
 * 只发布一条消息；幂等预查 + 终态幂等行唯一约束保证跨实例只调一次 ChatModel；
 * 同消息再投一次计数不变（第二条命中幂等预查直接 ack）。
 */
class SessionSummaryDualInstanceMqIntegrationTest extends AbstractMqIntegrationTest {

    private static final long USER_ID = 888401L;
    private static final long SESSION_ID = 888411L;
    private static final long FIRST_MESSAGE_ID = 888420L;
    private static final int MESSAGE_COUNT = 22;
    private static final long TRIGGER_MESSAGE_ID = FIRST_MESSAGE_ID + MESSAGE_COUNT - 1;

    private final ObjectMapper objectMapper = new ObjectMapper()
            .findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @AfterEach
    void cleanupSummaryRows() {
        JdbcTemplate jdbc = cleanupJdbc();
        jdbc.update("DELETE FROM idempotent_consumptions WHERE message_id LIKE 'SESSION_SUMMARY:888%'");
        jdbc.update("DELETE FROM outbox_events WHERE task_type = 'SESSION_SUMMARY' "
                + "AND task_id BETWEEN 888000 AND 888999");
        jdbc.update("DELETE FROM ai_chat_messages WHERE session_id BETWEEN 888000 AND 888999");
        jdbc.update("DELETE FROM ai_chat_sessions WHERE id BETWEEN 888000 AND 888999");
        jdbc.update("DELETE FROM users WHERE id BETWEEN 888000 AND 888999");
    }

    @Test
    void twoInstancesSummarizeExactlyOnce() throws Exception {
        JdbcTemplate jdbc = cleanupJdbc();
        ConfigurableApplicationContext instanceA = startContext(8083);
        ConfigurableApplicationContext instanceB = startContext(8084);
        try {
            String workQueue = MqTopology.queueName(TaskType.SESSION_SUMMARY);
            await().atMost(Duration.ofSeconds(20)).until(() -> queueExists(workQueue));

            insertFixtures(jdbc);
            when(MqIntegrationMocks.CHAT_MODEL.call(any(Prompt.class))).thenAnswer(invocation ->
                    new ChatResponse(List.of(new Generation(new AssistantMessage("摘".repeat(900))))));

            publishSummaryMessage();

            await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                    assertThat(summaryIsNull(jdbc)).isFalse());

            // 跨两实例：ChatModel 恰好 1 次、幂等行 1 条
            verify(MqIntegrationMocks.CHAT_MODEL, times(1)).call(any(Prompt.class));
            assertThat(idempotentCount(jdbc)).isEqualTo(1);

            // 同一消息再投一次：命中幂等预查直接 ack，调用次数/幂等行全部不变
            publishSummaryMessage();
            await().atMost(Duration.ofSeconds(10)).until(() ->
                    MqTestBroker.messageCount(workQueue) == 0);
            Thread.sleep(Duration.ofSeconds(1).toMillis());
            verify(MqIntegrationMocks.CHAT_MODEL, times(1)).call(any(Prompt.class));
            assertThat(idempotentCount(jdbc)).isEqualTo(1);
        } finally {
            instanceA.close();
            instanceB.close();
        }
    }

    // ---------- helpers ----------

    private void insertFixtures(JdbcTemplate jdbc) {
        jdbc.update("INSERT INTO users(id, username, password_hash, major) VALUES (?, ?, 'x', '计算机')",
                USER_ID, "it" + USER_ID);
        jdbc.update("INSERT INTO ai_chat_sessions(id, user_id, title) VALUES (?, ?, 'it888-summary-dual')",
                SESSION_ID, USER_ID);
        for (int i = 0; i < MESSAGE_COUNT; i++) {
            long messageId = FIRST_MESSAGE_ID + i;
            String role = (i + 1) % 2 == 0 ? "assistant" : "user";
            jdbc.update("INSERT INTO ai_chat_messages(id, session_id, role, content) "
                            + "VALUES (?, ?, ?, ?)",
                    messageId, SESSION_ID, role, "message-" + (i + 1));
        }
    }

    private void publishSummaryMessage() throws Exception {
        TaskMessage payload = new TaskMessage(TaskType.SESSION_SUMMARY, TRIGGER_MESSAGE_ID,
                String.valueOf(SESSION_ID), "SUMMARY", Instant.now());
        MessageProperties properties = new MessageProperties();
        properties.setMessageId("SESSION_SUMMARY:" + TRIGGER_MESSAGE_ID);
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setContentEncoding(StandardCharsets.UTF_8.name());
        Message message = new Message(objectMapper.writeValueAsBytes(payload), properties);
        MqTestBroker.driver().send(MqTopology.TASK_EXCHANGE,
                MqTopology.routingKey(TaskType.SESSION_SUMMARY), message);
    }

    private boolean summaryIsNull(JdbcTemplate jdbc) {
        return jdbc.queryForObject(
                "SELECT summary IS NULL FROM ai_chat_sessions WHERE id = ?",
                Boolean.class, SESSION_ID);
    }

    private Integer idempotentCount(JdbcTemplate jdbc) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotent_consumptions WHERE message_id = ?",
                Integer.class, "SESSION_SUMMARY:" + TRIGGER_MESSAGE_ID);
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
