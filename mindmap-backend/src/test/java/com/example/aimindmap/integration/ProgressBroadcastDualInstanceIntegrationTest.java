package com.example.aimindmap.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.example.aimindmap.progress.ProgressBroadcaster;
import com.example.aimindmap.progress.RedisPubSubBroadcaster;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 阶段七-1 验收（方案 321 行原话："双实例下连 B 实例也能收到 A 发起的进度"）：
 * 8083/8084 两个完整应用上下文共享同一个 Testcontainers 一次性 Redis（随机端口，
 * 与开发机 Redis 完全隔离），前端 SSE 连在 B 实例，进度由 A 实例的 ProgressBroadcaster 发出。
 *
 * <p>走真实 HTTP SSE 链路：JWE 令牌（?access_token=，与前端 EventSource 一致）、
 * 归属校验、Controller 回放、模式订阅回流全部真实装配。另在 A 实例同样挂一路 SSE，
 * 验证发起者自己也只收一次（防双发）。
 */
class ProgressBroadcastDualInstanceIntegrationTest extends AbstractMqIntegrationTest {

    private static final long USER_ID = 888604L;
    private static final long DOCUMENT_ID = 888604L;
    private static final long MINDMAP_ID = 888704L;
    private static final long JOB_ID = 888804L;

    @BeforeAll
    static void startRedis() {
        IntegrationTestRedis.start();
    }

    @AfterEach
    void cleanup() {
        ProgressSseFixtures.cleanupFixtures(cleanupJdbc(), 888000L, 888999L);
    }

    @Test
    void subscriberOnInstanceBReceivesProgressPublishedOnInstanceA() throws Exception {
        JdbcTemplate jdbc = cleanupJdbc();
        ConfigurableApplicationContext instanceA =
                startContext(8083, IntegrationTestRedis.appArgs());
        ConfigurableApplicationContext instanceB =
                startContext(8084, IntegrationTestRedis.appArgs());

        SseTestClient[] streams = new SseTestClient[2];

        try {
            // 等两个实例都完成 PSUBSCRIBE 后再发布，避免竞态丢首条
            await().atMost(Duration.ofSeconds(30)).until(
                    () -> IntegrationTestRedis.patternSubscriberCount() >= 2);
            assertThat(instanceA.getBean(ProgressBroadcaster.class))
                    .isInstanceOf(RedisPubSubBroadcaster.class);
            assertThat(instanceB.getBean(ProgressBroadcaster.class))
                    .isInstanceOf(RedisPubSubBroadcaster.class);

            ProgressSseFixtures.insertFixtures(jdbc, USER_ID, DOCUMENT_ID, MINDMAP_ID, JOB_ID);
            String token = ProgressSseFixtures.issueToken(instanceA, USER_ID);
            String externalJobId = ProgressSseFixtures.externalId(instanceA, JOB_ID);

            // 前端连 B 实例；A 实例也挂一路验证发起者自身只收一次
            streams[1] = SseTestClient.open(8084, externalJobId, token);
            streams[0] = SseTestClient.open(8083, externalJobId, token);
            await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
                assertThat(streams[0].contains("status")).isTrue();
                assertThat(streams[1].contains("status")).isTrue();
            });

            ProgressBroadcaster publisherOnA = instanceA.getBean(ProgressBroadcaster.class);
            publisherOnA.publish(JOB_ID, "status", Map.of(
                    "jobId", externalJobId,
                    "status", "PARSING",
                    "documentId", ProgressSseFixtures.externalId(instanceA, DOCUMENT_ID)));
            publisherOnA.publish(JOB_ID, "ready", Map.of(
                    "jobId", externalJobId,
                    "status", "READY"));
            publisherOnA.complete(JOB_ID);

            await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
                assertThat(streams[0].contains("ready")).isTrue();
                assertThat(streams[1].contains("ready")).isTrue();
            });

            // 终态后两路 SSE 都被关闭（EOF）
            assertThat(streams[0].awaitEnd(Duration.ofSeconds(10))).isTrue();
            assertThat(streams[1].awaitEnd(Duration.ofSeconds(10))).isTrue();

            // 防双发：ready 恰好一次；status = 回放 1 条 + 跨实例 PARSING 1 条
            assertThat(streams[0].count("ready")).isEqualTo(1);
            assertThat(streams[1].count("ready")).isEqualTo(1);
            assertThat(streams[0].count("status")).isEqualTo(2);
            assertThat(streams[1].count("status")).isEqualTo(2);
            assertThat(streams[1].events()).anySatisfy(event -> {
                assertThat(event.name()).isEqualTo("status");
                assertThat(event.data()).contains("PARSING");
            });
        } finally {
            if (streams[0] != null) {
                streams[0].close();
            }
            if (streams[1] != null) {
                streams[1].close();
            }
            instanceA.close();
            instanceB.close();
        }
    }
}
