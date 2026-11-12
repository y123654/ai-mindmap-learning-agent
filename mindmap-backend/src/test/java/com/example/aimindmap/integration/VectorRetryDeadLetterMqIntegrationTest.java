package com.example.aimindmap.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.example.aimindmap.mqsupport.MqTopology;
import com.example.aimindmap.mqsupport.TaskMessage;
import com.example.aimindmap.mqsupport.TaskType;
import com.example.aimindmap.vectorindex.VectorIndexTaskService;
import com.example.aimindmap.vectorindex.VectorResourceType;
import com.example.aimindmap.vectorindex.VectorTaskOperation;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.amqp.core.Message;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * H1-4：停 Qdrant 走三档重试——
 * a) 第 1 次失败进 5s 重试队列，恢复后自动成功；
 * b) 持续失败依次经过 5s/30s/2m，第 4 次处理后 nack 进 DLQ，任务 FAILED、死信头可检。
 * 真实等待 TTL，全类约 160 秒；不为提速改生产 TTL。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Import(MqIntegrationMocks.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class VectorRetryDeadLetterMqIntegrationTest extends AbstractMqIntegrationTest {

    @Autowired private VectorIndexTaskService taskService;
    @Autowired private PlatformTransactionManager txManager;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void h1_4aFirstFailureRetriesThenRecovers() throws Exception {
        // 第 1 次 delete 抛带 cause 的异常（模拟 Qdrant 宕机），第 2 次正常
        Mockito.doThrow(new RuntimeException("Qdrant 宕机模拟", new RuntimeException("io exception")))
                .doNothing()
                .when(MqIntegrationMocks.GATEWAY).delete(anyString(), any(UUID.class));

        long taskId = invokeAddTask(taskService, txManager, null,
                VectorResourceType.DOCUMENT_CHUNK, "888401", 0, VectorTaskOperation.DELETE);
        String retry5s = MqTopology.retryQueueName(TaskType.VECTOR_DELETE, MqTopology.RetryTier.FIRST);
        String workQueue = MqTopology.queueName(TaskType.VECTOR_DELETE);

        // 消息立即进入 5s 重试队列，任务行复位为可重新认领的 PENDING（attempts=1）
        await().atMost(Duration.ofSeconds(10)).until(() ->
                MqTestBroker.messageCount(retry5s) == 1);
        assertThat(taskStatus(taskId)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject(
                "SELECT attempts FROM vector_index_tasks WHERE id = ?", Integer.class, taskId))
                .isEqualTo(1);

        // 5s TTL 到期回到工作队列，第 2 次处理成功
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(taskStatus(taskId)).isEqualTo("SUCCEEDED"));
        await().atMost(Duration.ofSeconds(5)).until(() ->
                MqTestBroker.messageCount(retry5s) == 0
                        && MqTestBroker.messageCount(MqTopology.retryQueueName(
                                TaskType.VECTOR_DELETE, MqTopology.RetryTier.SECOND)) == 0
                        && MqTestBroker.messageCount(MqTopology.retryQueueName(
                                TaskType.VECTOR_DELETE, MqTopology.RetryTier.THIRD)) == 0
                        && MqTestBroker.messageCount(workQueue) == 0);
        verify(MqIntegrationMocks.GATEWAY, times(2)).delete(anyString(), any(UUID.class));
        assertThat(jdbc.queryForObject(
                "SELECT attempts FROM vector_index_tasks WHERE id = ?", Integer.class, taskId))
                .isEqualTo(2);
    }

    @Test
    @SuppressWarnings("unchecked")
    void h1_4bPersistentFailureTravelsThreeTiersAndEndsInDeadLetterQueue() throws Exception {
        // Qdrant 始终不可用
        doThrow(new RuntimeException("Qdrant 持续宕机模拟", new RuntimeException("timeout")))
                .when(MqIntegrationMocks.GATEWAY).delete(anyString(), any(UUID.class));

        long taskId = invokeAddTask(taskService, txManager, null,
                VectorResourceType.DOCUMENT_CHUNK, "888402", 0, VectorTaskOperation.DELETE);
        String retry5s = MqTopology.retryQueueName(TaskType.VECTOR_DELETE, MqTopology.RetryTier.FIRST);
        String retry30s = MqTopology.retryQueueName(TaskType.VECTOR_DELETE, MqTopology.RetryTier.SECOND);
        String retry2m = MqTopology.retryQueueName(TaskType.VECTOR_DELETE, MqTopology.RetryTier.THIRD);

        // 依次真实经过三档重试队列（证明 TTL 死信逐档流转，而不是直接进 DLQ）
        await().atMost(Duration.ofSeconds(10)).until(() ->
                MqTestBroker.messageCount(retry5s) == 1);
        await().atMost(Duration.ofSeconds(50)).until(() ->
                MqTestBroker.messageCount(retry30s) == 1);
        await().atMost(Duration.ofSeconds(60)).until(() ->
                MqTestBroker.messageCount(retry2m) == 1);

        // 2m 到期后第 4 次处理仍失败 → nack → 工作队列 DLX → aimindmap.dlq（真实约 155s）
        await().atMost(Duration.ofSeconds(200)).until(() ->
                MqTestBroker.messageCount(MqTopology.DEAD_LETTER_QUEUE) == 1);

        assertThat(taskStatus(taskId)).isEqualTo("FAILED");
        verify(MqIntegrationMocks.GATEWAY, times(4)).delete(anyString(), any(UUID.class));

        Message dead = MqTestBroker.driver().receive(MqTopology.DEAD_LETTER_QUEUE, 5000);
        assertThat(dead).isNotNull();
        assertThat(dead.getMessageProperties().getMessageId())
                .isEqualTo("VECTOR_DELETE:" + taskId);
        TaskMessage payload = objectMapper.readValue(dead.getBody(), TaskMessage.class);
        assertThat(payload.taskType()).isEqualTo(TaskType.VECTOR_DELETE);
        assertThat(payload.taskId()).isEqualTo(taskId);
        assertThat(payload.op()).isEqualTo("DELETE");

        List<Map<String, Object>> xDeath =
                (List<Map<String, Object>>) dead.getMessageProperties().getHeaders().get("x-death");
        assertThat(xDeath).isNotNull().isNotEmpty();
        List<Object> queues = xDeath.stream().map(e -> e.get("queue")).toList();
        List<Object> reasons = xDeath.stream().map(e -> e.get("reason")).toList();
        assertThat(queues).contains(MqTopology.queueName(TaskType.VECTOR_DELETE));
        assertThat(reasons).contains("rejected");
        assertThat(queues).contains(retry5s);
        assertThat(reasons).contains("expired");

        assertThat(MqTestBroker.messageCount(MqTopology.queueName(TaskType.VECTOR_DELETE)))
                .isZero();
        assertThat(MqTestBroker.messageCount(retry5s)).isZero();
        assertThat(MqTestBroker.messageCount(retry30s)).isZero();
        assertThat(MqTestBroker.messageCount(retry2m)).isZero();
    }

    private String taskStatus(long taskId) {
        return jdbc.queryForObject(
                "SELECT status FROM vector_index_tasks WHERE id = ?", String.class, taskId);
    }
}
