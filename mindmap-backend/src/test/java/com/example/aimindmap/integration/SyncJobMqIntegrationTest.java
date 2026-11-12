package com.example.aimindmap.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.aimindmap.ai.AiGatewayException;
import com.example.aimindmap.mindmap.GraphJsonCodec;
import com.example.aimindmap.mindmap.SyncEngine;
import com.example.aimindmap.mindmap.SyncJobService;
import com.example.aimindmap.mindmap.graph.MindMapGraph;
import com.example.aimindmap.mindmap.graph.MindMapNode;
import com.example.aimindmap.mqsupport.MqTopology;
import com.example.aimindmap.mqsupport.TaskMessage;
import com.example.aimindmap.mqsupport.TaskType;
import com.example.aimindmap.parser.MarkdownDiff;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;

/**
 * 阶段六-1 思维导图同步 MQ 集成测试（复用 4-4 Testcontainers 设施，无 Docker 自动跳过）：
 * a) outbox→MQ→条件认领→READY 正常流，DLQ=0，终态同事务写幂等行；
 * b) SyncEngine 先抛 AI_HTTP_429 一次再成功：消息真实经过 5s 重试队列后最终 READY；
 * c) AI_NOT_CONFIGURED：任务落 FAILED 且 DLQ 收到 1 条（配置类运维信号）；
 * d) 条件认领/重试复位/对账的原生 SQL 语义。
 * broker 一律 Testcontainers 临时实例，不用开发机 5672；不碰 redis/Dify/任何现有容器。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Import(MqIntegrationMocks.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SyncJobMqIntegrationTest extends AbstractMqIntegrationTest {

    private static final String QUEUE = MqTopology.queueName(TaskType.MINDMAP_SYNC);
    private static final String RETRY_5S =
            MqTopology.retryQueueName(TaskType.MINDMAP_SYNC, MqTopology.RetryTier.FIRST);
    private static final String RETRY_30S =
            MqTopology.retryQueueName(TaskType.MINDMAP_SYNC, MqTopology.RetryTier.SECOND);
    private static final String RETRY_2M =
            MqTopology.retryQueueName(TaskType.MINDMAP_SYNC, MqTopology.RetryTier.THIRD);

    private static final long USER_ID = 888601L;
    private static final long DOCUMENT_ID = 888601L;
    private static final long MINDMAP_ID = 888701L;

    private static final String GRAPH_JSON =
            "{\"rootId\":\"root\",\"version\":1,"
                    + "\"tree\":{\"id\":\"root\",\"label\":\"标题\",\"children\":[]},"
                    + "\"updatedAt\":\"2026-10-17T00:00:00Z\"}";

    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private GraphJsonCodec graphJsonCodec;
    @Autowired private SyncJobService syncJobService;

    @BeforeEach
    void insertBaseFixturesAndStubSuccessEngine() {
        jdbc.update("INSERT INTO users(id, username, password_hash, major) VALUES (?, ?, 'x', '计算机')",
                USER_ID, "it" + USER_ID);
        jdbc.update("INSERT INTO documents(id, title, content, content_version, source_type, status, user_id) "
                        + "VALUES (?, 'it888-sync', '# 标题', 1, 'markdown', 'active', ?)",
                DOCUMENT_ID, USER_ID);
        jdbc.update("INSERT INTO mindmaps(id, document_id, graph, graph_version, sync_status, user_id) "
                        + "VALUES (?, ?, CAST(? AS jsonb), 1, 'idle', ?)",
                MINDMAP_ID, DOCUMENT_ID, GRAPH_JSON, USER_ID);
        when(MqIntegrationMocks.SYNC_ENGINE.prepare(any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> successPreview());
    }

    @AfterEach
    void cleanupSyncRows() {
        // 子类 @AfterEach 先于基类：先删依赖 sync_jobs/document 的行，基类随后删 888 段文档与用户
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
    void pendingOutboxReachesReadyWithIdempotentRowAndEmptyDeadLetterQueue() {
        long jobId = insertSyncJob(888801L, "PENDING", Instant.now());
        insertOutbox(jobId);

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(jobStatus(jobId)).isEqualTo("READY"));

        assertThat(jdbc.queryForObject(
                "SELECT target_graph IS NOT NULL FROM sync_jobs WHERE id = ?",
                Boolean.class, jobId)).isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotent_consumptions WHERE message_id = ?",
                Integer.class, "MINDMAP_SYNC:" + jobId)).isEqualTo(1);
        verify(MqIntegrationMocks.SYNC_ENGINE, times(1)).prepare(any(), any(), any(), any(), any());

        await().atMost(Duration.ofSeconds(5)).until(() -> MqTestBroker.messageCount(QUEUE) == 0);
        assertThat(MqTestBroker.messageCount(RETRY_5S)).isZero();
        assertThat(MqTestBroker.messageCount(MqTopology.DEAD_LETTER_QUEUE)).isZero();
    }

    @Test
    void rateLimitedOnceTravelsFirstRetryQueueThenRecovers() {
        long jobId = insertSyncJob(888802L, "PENDING", Instant.now());
        insertOutbox(jobId);
        when(MqIntegrationMocks.SYNC_ENGINE.prepare(any(), any(), any(), any(), any()))
                .thenThrow(new AiGatewayException("AI_HTTP_429", "rate limited"))
                .thenAnswer(invocation -> successPreview());

        // 第 1 次处理失败：消息真实进入 5s 重试队列，任务行已复位 PENDING
        await().atMost(Duration.ofSeconds(10)).until(() ->
                MqTestBroker.messageCount(RETRY_5S) == 1);
        assertThat(jobStatus(jobId)).isEqualTo("PENDING");

        // 5s TTL 到期回到工作队列，第 2 次处理成功
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(jobStatus(jobId)).isEqualTo("READY"));

        verify(MqIntegrationMocks.SYNC_ENGINE, times(2)).prepare(any(), any(), any(), any(), any());
        await().atMost(Duration.ofSeconds(5)).until(() ->
                MqTestBroker.messageCount(QUEUE) == 0
                        && MqTestBroker.messageCount(RETRY_5S) == 0
                        && MqTestBroker.messageCount(RETRY_30S) == 0
                        && MqTestBroker.messageCount(RETRY_2M) == 0);
        assertThat(MqTestBroker.messageCount(MqTopology.DEAD_LETTER_QUEUE)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotent_consumptions WHERE message_id = ?",
                Integer.class, "MINDMAP_SYNC:" + jobId)).isEqualTo(1);
    }

    @Test
    void notConfiguredFailsJobAndOneMessageReachesDeadLetterQueue() throws Exception {
        long jobId = insertSyncJob(888803L, "PENDING", Instant.now());
        insertOutbox(jobId);
        when(MqIntegrationMocks.SYNC_ENGINE.prepare(any(), any(), any(), any(), any()))
                .thenThrow(new AiGatewayException("AI_NOT_CONFIGURED", "not configured"));

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(jobStatus(jobId)).isEqualTo("FAILED"));
        assertThat(jdbc.queryForObject(
                "SELECT error_code FROM sync_jobs WHERE id = ?", String.class, jobId))
                .isEqualTo("AI_NOT_CONFIGURED");

        await().atMost(Duration.ofSeconds(10)).until(() ->
                MqTestBroker.messageCount(MqTopology.DEAD_LETTER_QUEUE) == 1);
        Message dead = MqTestBroker.driver().receive(MqTopology.DEAD_LETTER_QUEUE, 5000);
        assertThat(dead).isNotNull();
        assertThat(dead.getMessageProperties().getMessageId()).isEqualTo("MINDMAP_SYNC:" + jobId);
        TaskMessage payload = objectMapper.readValue(dead.getBody(), TaskMessage.class);
        assertThat(payload.taskType()).isEqualTo(TaskType.MINDMAP_SYNC);
        assertThat(payload.taskId()).isEqualTo(jobId);
        assertThat(payload.op()).isEqualTo("SYNC");

        assertThat(MqTestBroker.messageCount(RETRY_5S)).isZero();
        assertThat(MqTestBroker.messageCount(RETRY_30S)).isZero();
        assertThat(MqTestBroker.messageCount(RETRY_2M)).isZero();
        // 任务已落 FAILED 终态，幂等行同事务写入；DLQ 仅作运维信号
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotent_consumptions WHERE message_id = ?",
                Integer.class, "MINDMAP_SYNC:" + jobId)).isEqualTo(1);
    }

    @Test
    void claimRequeueAndReconcileSqlSemantics() {
        // 条件认领：仅 PENDING→PARSING，CAS 第二次 0 行；READY/FAILED/CANCELLED 不认领
        long claimJob = insertSyncJob(888811L, "PENDING", Instant.now());
        assertThat(syncJobService.claimForMessage(claimJob)).isTrue();
        assertThat(jobStatus(claimJob)).isEqualTo("PARSING");
        assertThat(syncJobService.claimForMessage(claimJob)).isFalse();
        for (long id : List.of(888812L, 888813L, 888814L)) {
            String terminal = id == 888812L ? "READY" : id == 888813L ? "FAILED" : "CANCELLED";
            insertSyncJob(id, terminal, Instant.now());
            assertThat(syncJobService.claimForMessage(id)).isFalse();
        }

        // 重试复位：四个中间态→PENDING；PENDING/READY 不命中
        int idx = 0;
        for (String processing : List.of("PARSING", "DIFFING", "AI_GENERATING", "VALIDATING")) {
            long id = 888821L + idx++;
            insertSyncJob(id, processing, Instant.now());
            assertThat(syncJobService.requeueForMessageRetry(id)).isTrue();
            assertThat(jobStatus(id)).isEqualTo("PENDING");
        }
        long pendingJob = 888831L;
        insertSyncJob(pendingJob, "PENDING", Instant.now());
        assertThat(syncJobService.requeueForMessageRetry(pendingJob)).isFalse();
        long readyJob = 888832L;
        insertSyncJob(readyJob, "READY", Instant.now());
        assertThat(syncJobService.requeueForMessageRetry(readyJob)).isFalse();

        // 对账：超 5 分钟的 PENDING 补 outbox；中间态复位 PENDING 并补 outbox；新鲜行不动
        Instant old = Instant.now().minus(Duration.ofMinutes(10));
        long stalePending = insertSyncJob(888841L, "PENDING", old);
        long staleGenerating = insertSyncJob(888851L, "AI_GENERATING", old);
        long freshPending = insertSyncJob(888861L, "PENDING", Instant.now());

        int recovered = syncJobService.reconcileStaleJobs();

        assertThat(recovered).isEqualTo(2);
        assertThat(jobStatus(staleGenerating)).isEqualTo("PENDING");
        assertThat(outboxCount(stalePending)).isEqualTo(1);
        assertThat(outboxCount(staleGenerating)).isEqualTo(1);
        assertThat(outboxCount(freshPending)).isZero();
        assertThat(jobStatus(freshPending)).isEqualTo("PENDING");
    }

    // ---------- helpers ----------

    private SyncEngine.SyncPreview successPreview() {
        MindMapGraph graph = graphJsonCodec.read(GRAPH_JSON);
        MarkdownDiff emptyDiff = new MarkdownDiff(
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        return new SyncEngine.SyncPreview(List.of(), graph, emptyDiff, List.of(), null);
    }

    private long insertSyncJob(long id, String status, Instant createdAt) {
        jdbc.update("INSERT INTO sync_jobs(id, document_id, mindmap_id, status, "
                        + "target_content_version, base_graph_version, created_at) "
                        + "VALUES (?, ?, ?, ?, 1, 1, ?)",
                id, DOCUMENT_ID, MINDMAP_ID, status,
                // 与 Hibernate TIMESTAMP_UTC 写入口径一致：UTC 墙钟时间写入无时区列
                LocalDateTime.ofInstant(createdAt, ZoneOffset.UTC));
        return id;
    }

    private void insertOutbox(long jobId) {
        try {
            String payload = objectMapper.writeValueAsString(new TaskMessage(
                    TaskType.MINDMAP_SYNC, jobId, String.valueOf(DOCUMENT_ID), "SYNC", Instant.now()));
            jdbc.update("INSERT INTO outbox_events(task_type, task_id, payload, status, attempts) "
                    + "VALUES ('MINDMAP_SYNC', ?, ?, 'PENDING', 0)", jobId, payload);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String jobStatus(long jobId) {
        return jdbc.queryForObject(
                "SELECT status FROM sync_jobs WHERE id = ?", String.class, jobId);
    }

    private int outboxCount(long jobId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE task_type = 'MINDMAP_SYNC' AND task_id = ?",
                Integer.class, jobId);
        return count == null ? 0 : count;
    }
}
