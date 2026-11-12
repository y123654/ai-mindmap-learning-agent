package com.example.aimindmap.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.example.aimindmap.mqsupport.MqTopology;
import com.example.aimindmap.mqsupport.TaskMessage;
import com.example.aimindmap.mqsupport.TaskType;
import com.example.aimindmap.vectorindex.QdrantProperties;
import com.example.aimindmap.vectorindex.QdrantVectorGateway;
import com.example.aimindmap.vectorindex.VectorIndexTaskService;
import com.example.aimindmap.vectorindex.VectorPointIds;
import com.example.aimindmap.vectorindex.VectorResourceType;
import com.example.aimindmap.vectorindex.VectorTaskOperation;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * H1-1 正常流（PENDING→SENT→SUCCEEDED→可检）与 H1-3 重复投递点数不变。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Import(MqIntegrationMocks.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class VectorHappyPathMqIntegrationTest extends AbstractMqIntegrationTest {

    @Autowired private VectorIndexTaskService taskService;
    @Autowired private PlatformTransactionManager txManager;
    @Autowired private QdrantProperties qdrantProperties;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void h1_1HappyPathPendingSentSucceededSearchable() throws Exception {
        jdbc.update("INSERT INTO users(id, username, password_hash, major) "
                + "VALUES (888001, 'it888001', 'x', '计算机')");
        jdbc.update("INSERT INTO documents(id, title, content, status, user_id) "
                + "VALUES (888001, '进程与线程', '进程是资源分配的基本单位。', 'active', 888001)");

        long taskId = invokeAddTask(taskService, txManager, 888001L,
                VectorResourceType.DOCUMENT_CHUNK, "888001", 0, VectorTaskOperation.UPSERT);

        // outbox：PENDING 被 relay 投递成功后置 SENT
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(status("SELECT status FROM outbox_events WHERE task_id = ?", taskId))
                        .isEqualTo("SENT"));
        // 任务：消费者条件认领→处理→SUCCEEDED，attempts 恰为 1
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(status("SELECT status FROM vector_index_tasks WHERE id = ?", taskId))
                        .isEqualTo("SUCCEEDED"));
        assertThat(jdbc.queryForObject(
                "SELECT attempts FROM vector_index_tasks WHERE id = ?", Integer.class, taskId))
                .isEqualTo(1);

        // 确定性 pointId 恰好写一次（点数不变的第二重保障）
        ArgumentCaptor<QdrantVectorGateway.VectorPoint> pointCaptor =
                ArgumentCaptor.forClass(QdrantVectorGateway.VectorPoint.class);
        verify(MqIntegrationMocks.GATEWAY, times(1))
                .upsert(org.mockito.ArgumentMatchers.eq(qdrantProperties.getCollection()),
                        pointCaptor.capture());
        QdrantVectorGateway.VectorPoint point = pointCaptor.getValue();
        UUID expectedPointId = VectorPointIds.pointId(
                VectorResourceType.DOCUMENT_CHUNK, "888001", 0);
        assertThat(point.pointId()).isEqualTo(expectedPointId);

        // "可检"：映射表 ACTIVE，content_hash 与实际入参内容的 sha256 一致
        java.util.Map<String, Object> entry = jdbc.queryForMap(
                "SELECT status, content_hash FROM vector_index_entries "
                        + "WHERE resource_type = 'document_chunk' AND resource_id = '888001' "
                        + "AND chunk_index = 0");
        assertThat(entry.get("status")).isEqualTo("ACTIVE");
        assertThat((String) entry.get("content_hash")).isEqualTo(sha256(point.data().content()));

        // 幂等三件套之三：恰好一行；工作队列消息排空
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotent_consumptions WHERE message_id = ?",
                Integer.class, "VECTOR_UPSERT:" + taskId)).isEqualTo(1);
        assertThat(MqTestBroker.messageCount(MqTopology.queueName(TaskType.VECTOR_UPSERT)))
                .isZero();
    }

    @Test
    void h1_3DuplicateDeliveryDoesNotIncreasePointCount() throws Exception {
        jdbc.update("INSERT INTO users(id, username, password_hash, major) "
                + "VALUES (888003, 'it888003', 'x', '计算机')");
        jdbc.update("INSERT INTO documents(id, title, content, status, user_id) "
                + "VALUES (888003, '内存管理', '分页与分段是两种内存管理方式。', 'active', 888003)");

        long taskId = invokeAddTask(taskService, txManager, 888003L,
                VectorResourceType.DOCUMENT_CHUNK, "888003", 0, VectorTaskOperation.UPSERT);
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(status("SELECT status FROM vector_index_tasks WHERE id = ?", taskId))
                        .isEqualTo("SUCCEEDED"));

        // 用完全相同的 messageId 向工作交换机重发同一条消息
        TaskMessage duplicate = new TaskMessage(
                TaskType.VECTOR_UPSERT, taskId, "888003", "UPSERT", Instant.now());
        MessageProperties properties = new MessageProperties();
        properties.setMessageId("VECTOR_UPSERT:" + taskId);
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setContentEncoding(StandardCharsets.UTF_8.name());
        Message message = new Message(objectMapper.writeValueAsBytes(duplicate), properties);
        MqTestBroker.driver().send(
                MqTopology.TASK_EXCHANGE, MqTopology.routingKey(TaskType.VECTOR_UPSERT), message);

        // 工作队列排空；≤1s 短等让重复投递走完条件认领→直接 ack
        await().atMost(Duration.ofSeconds(10)).until(() ->
                MqTestBroker.messageCount(MqTopology.queueName(TaskType.VECTOR_UPSERT)) == 0);
        Thread.sleep(Duration.ofSeconds(1).toMillis());

        verify(MqIntegrationMocks.GATEWAY, times(1))
                .upsert(org.mockito.ArgumentMatchers.eq(qdrantProperties.getCollection()),
                        org.mockito.ArgumentMatchers.any());
        assertThat(jdbc.queryForObject(
                "SELECT attempts FROM vector_index_tasks WHERE id = ?", Integer.class, taskId))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotent_consumptions WHERE message_id = ?",
                Integer.class, "VECTOR_UPSERT:" + taskId)).isEqualTo(1);
    }

    private String status(String sql, Long id) {
        return jdbc.queryForObject(sql, String.class, id);
    }

    private static String sha256(String content) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(content.getBytes(StandardCharsets.UTF_8)));
    }
}
