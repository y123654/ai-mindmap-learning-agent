package com.example.aimindmap.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.aimindmap.mindmap.SyncEngine;
import com.example.aimindmap.mindmap.graph.MindMapGraph;
import com.example.aimindmap.mindmap.graph.MindMapNode;
import com.example.aimindmap.mqsupport.MqTopology;
import com.example.aimindmap.mqsupport.TaskMessage;
import com.example.aimindmap.mqsupport.TaskType;
import com.example.aimindmap.parser.MarkdownDiff;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 阶段六-1：8083/8084 双实例同一个同步任务只执行一次。两个应用上下文（同一 PG it888 schema、
 * 同一 Testcontainers broker、同一个 static SyncEngine mock）同时在线，只发布一条消息；
 * DB 原子条件认领 + 终态幂等行保证跨实例只准备一次；重复投递认领 0 行直接 ack。
 */
class SyncJobDualInstanceMqIntegrationTest extends AbstractMqIntegrationTest {

    private static final long USER_ID = 888602L;
    private static final long DOCUMENT_ID = 888602L;
    private static final long MINDMAP_ID = 888702L;
    private static final long JOB_ID = 888802L;

    private final ObjectMapper objectMapper = new ObjectMapper()
            .findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @AfterEach
    void cleanupSyncRows() {
        JdbcTemplate jdbc = cleanupJdbc();
        jdbc.update("DELETE FROM outbox_events WHERE task_type = 'MINDMAP_SYNC' AND task_id IN ("
                + "SELECT id FROM sync_jobs WHERE document_id BETWEEN 888000 AND 888999)");
        jdbc.update("DELETE FROM idempotent_consumptions WHERE message_id IN ("
                + "SELECT 'MINDMAP_SYNC:' || id FROM sync_jobs WHERE document_id BETWEEN 888000 AND 888999)");
        jdbc.update("DELETE FROM document_block_snapshots WHERE document_id BETWEEN 888000 AND 888999");
        jdbc.update("DELETE FROM sync_jobs WHERE document_id BETWEEN 888000 AND 888999");
        jdbc.update("DELETE FROM mindmaps WHERE document_id BETWEEN 888000 AND 888999");
        jdbc.update("DELETE FROM documents WHERE id BETWEEN 888000 AND 888999");
        jdbc.update("DELETE FROM users WHERE id BETWEEN 888000 AND 888999");
    }

    @Test
    void twoInstancesProcessSyncJobExactlyOnce() throws Exception {
        JdbcTemplate jdbc = cleanupJdbc();
        ConfigurableApplicationContext instanceA = startContext(8083);
        ConfigurableApplicationContext instanceB = startContext(8084);
        try {
            String workQueue = MqTopology.queueName(TaskType.MINDMAP_SYNC);
            await().atMost(Duration.ofSeconds(20)).until(() -> queueExists(workQueue));

            insertFixtures(jdbc);
            when(MqIntegrationMocks.SYNC_ENGINE.prepare(any(), any(), any(), any(), any()))
                    .thenAnswer(invocation -> successPreview());

            publishSyncMessage(JOB_ID);

            await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                    assertThat(jdbc.queryForObject(
                            "SELECT status FROM sync_jobs WHERE id = ?", String.class, JOB_ID))
                            .isEqualTo("READY"));

            // 跨两实例总 prepare 恰好 1 次，幂等行 1 条
            verify(MqIntegrationMocks.SYNC_ENGINE, times(1))
                    .prepare(any(), any(), any(), any(), any());
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM idempotent_consumptions WHERE message_id = ?",
                    Integer.class, "MINDMAP_SYNC:" + JOB_ID)).isEqualTo(1);

            // 同一消息再投一次：条件认领 0 行，直接 ack，prepare 次数不变
            publishSyncMessage(JOB_ID);
            await().atMost(Duration.ofSeconds(10)).until(() ->
                    MqTestBroker.messageCount(workQueue) == 0);
            Thread.sleep(Duration.ofSeconds(1).toMillis());
            verify(MqIntegrationMocks.SYNC_ENGINE, times(1))
                    .prepare(any(), any(), any(), any(), any());
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM idempotent_consumptions WHERE message_id = ?",
                    Integer.class, "MINDMAP_SYNC:" + JOB_ID)).isEqualTo(1);
        } finally {
            instanceA.close();
            instanceB.close();
        }
    }

    private void insertFixtures(JdbcTemplate jdbc) {
        jdbc.update("INSERT INTO users(id, username, password_hash, major) VALUES (?, ?, 'x', '计算机')",
                USER_ID, "it" + USER_ID);
        jdbc.update("INSERT INTO documents(id, title, content, content_version, source_type, status, user_id) "
                        + "VALUES (?, 'it888-dual', '# 标题', 1, 'markdown', 'active', ?)",
                DOCUMENT_ID, USER_ID);
        jdbc.update("INSERT INTO mindmaps(id, document_id, graph, graph_version, sync_status, user_id) "
                        + "VALUES (?, ?, CAST(? AS jsonb), 1, 'idle', ?)",
                MINDMAP_ID, DOCUMENT_ID,
                "{\"rootId\":\"root\",\"version\":1,\"tree\":{\"id\":\"root\",\"label\":\"标题\"}}",
                USER_ID);
        jdbc.update("INSERT INTO sync_jobs(id, document_id, mindmap_id, status, "
                        + "target_content_version, base_graph_version) VALUES (?, ?, ?, 'PENDING', 1, 1)",
                JOB_ID, DOCUMENT_ID, MINDMAP_ID);
    }

    private SyncEngine.SyncPreview successPreview() {
        MindMapGraph graph = new MindMapGraph("root", 1,
                new MindMapNode("root", "标题", null, "markdown", null, null, List.of(), Instant.now()),
                Instant.now());
        MarkdownDiff emptyDiff = new MarkdownDiff(
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        return new SyncEngine.SyncPreview(List.of(), graph, emptyDiff, List.of(), null);
    }

    private void publishSyncMessage(long jobId) throws Exception {
        TaskMessage payload = new TaskMessage(
                TaskType.MINDMAP_SYNC, jobId, String.valueOf(DOCUMENT_ID), "SYNC", Instant.now());
        MessageProperties properties = new MessageProperties();
        properties.setMessageId("MINDMAP_SYNC:" + jobId);
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setContentEncoding(StandardCharsets.UTF_8.name());
        Message message = new Message(objectMapper.writeValueAsBytes(payload), properties);
        MqTestBroker.driver().send(MqTopology.TASK_EXCHANGE,
                MqTopology.routingKey(TaskType.MINDMAP_SYNC), message);
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
