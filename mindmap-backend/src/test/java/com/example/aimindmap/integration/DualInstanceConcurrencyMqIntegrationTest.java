package com.example.aimindmap.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.example.aimindmap.mqsupport.MqTopology;
import com.example.aimindmap.mqsupport.TaskMessage;
import com.example.aimindmap.mqsupport.TaskType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * H1-5：8081/8082 双实例同任务只执行一次。两个应用上下文（同一 PG、同一 Testcontainers broker、
 * 同一个 static gateway mock）同时在线，只发布一条消息；幂等三件套保证跨实例只删一个点。
 */
class DualInstanceConcurrencyMqIntegrationTest extends AbstractMqIntegrationTest {

    private final ObjectMapper objectMapper = new ObjectMapper()
            .findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Test
    void h1_5TwoInstancesExecuteTaskExactlyOnce() throws Exception {
        JdbcTemplate jdbc = cleanupJdbc();
        ConfigurableApplicationContext instanceA = startContext(8081);
        ConfigurableApplicationContext instanceB = startContext(8082);
        try {
            String workQueue = MqTopology.queueName(TaskType.VECTOR_DELETE);
            await().atMost(Duration.ofSeconds(20)).until(() -> queueExists(workQueue));

            jdbc.update("INSERT INTO vector_index_tasks(user_id, resource_type, resource_id, "
                            + "chunk_index, operation, status, attempts, created_at, updated_at) "
                            + "VALUES (NULL, 'document_chunk', '888501', 0, 'DELETE', 'PENDING', 0, "
                            + "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
            long taskId = jdbc.queryForObject(
                    "SELECT id FROM vector_index_tasks WHERE resource_id = '888501'", Long.class);

            publishDeleteMessage(taskId);

            await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                    assertThat(jdbc.queryForObject(
                            "SELECT status FROM vector_index_tasks WHERE id = ?",
                            String.class, taskId)).isEqualTo("SUCCEEDED"));

            // 跨两实例总调用恰好 1 次，attempts=1，幂等行 1 条
            verify(MqIntegrationMocks.GATEWAY, times(1)).delete(anyString(), any(UUID.class));
            assertThat(jdbc.queryForObject(
                    "SELECT attempts FROM vector_index_tasks WHERE id = ?", Integer.class, taskId))
                    .isEqualTo(1);
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM idempotent_consumptions WHERE message_id = ?",
                    Integer.class, "VECTOR_DELETE:" + taskId)).isEqualTo(1);

            // 同一消息再投一次：条件认领 0 行，直接 ack，点数仍不变
            publishDeleteMessage(taskId);
            await().atMost(Duration.ofSeconds(10)).until(() ->
                    MqTestBroker.messageCount(workQueue) == 0);
            Thread.sleep(Duration.ofSeconds(1).toMillis());
            verify(MqIntegrationMocks.GATEWAY, times(1)).delete(anyString(), any(UUID.class));
            assertThat(jdbc.queryForObject(
                    "SELECT attempts FROM vector_index_tasks WHERE id = ?", Integer.class, taskId))
                    .isEqualTo(1);
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM idempotent_consumptions WHERE message_id = ?",
                    Integer.class, "VECTOR_DELETE:" + taskId)).isEqualTo(1);
        } finally {
            instanceA.close();
            instanceB.close();
        }
    }

    private void publishDeleteMessage(long taskId) throws Exception {
        TaskMessage payload = new TaskMessage(
                TaskType.VECTOR_DELETE, taskId, "888501", "DELETE", Instant.now());
        MessageProperties properties = new MessageProperties();
        properties.setMessageId("VECTOR_DELETE:" + taskId);
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setContentEncoding(StandardCharsets.UTF_8.name());
        Message message = new Message(objectMapper.writeValueAsBytes(payload), properties);
        MqTestBroker.driver().send(MqTopology.TASK_EXCHANGE,
                MqTopology.routingKey(TaskType.VECTOR_DELETE), message);
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