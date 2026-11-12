package com.example.aimindmap.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.example.aimindmap.progress.InMemoryProgressBroadcaster;
import com.example.aimindmap.progress.ProgressBroadcaster;
import com.example.aimindmap.progress.RedisPubSubBroadcaster;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 阶段七-1 降级链路：
 * <ol>
 *   <li>redis-enabled=false：装配 {@link InMemoryProgressBroadcaster}，publish/subscribe 本机正常；
 *       该开关也是全部测试上下文的默认值（见 TestRedisDisableEnvironmentPostProcessor），
 *       因此任何测试都不会连接开发机 Redis。</li>
 *   <li>redis-enabled=true 但 Redis 不可达：上下文必须正常启动（监听容器后台重连，不拖垮启动），
 *       广播发布失败时 warn 后降级本机直投，本机 SSE 仍能收到终态并正常关闭。</li>
 * </ol>
 * 指向 127.0.0.1:1（拒绝连接）模拟宕机，绝不使用开发机 6379。
 */
class ProgressBroadcastFallbackIntegrationTest extends AbstractMqIntegrationTest {

    private static final long USER_ID = 888605L;
    private static final long DOCUMENT_ID = 888605L;
    private static final long MINDMAP_ID = 888705L;
    private static final long JOB_ID = 888805L;

    @AfterEach
    void cleanup() {
        ProgressSseFixtures.cleanupFixtures(cleanupJdbc(), 888000L, 888999L);
    }

    @Test
    void redisDisabledUsesInMemoryBroadcasterAndLocalDeliveryWorks() throws Exception {
        ConfigurableApplicationContext context = startContext(8085);
        SseTestClient[] stream = new SseTestClient[1];
        try {
            ProgressBroadcaster broadcaster = context.getBean(ProgressBroadcaster.class);
            assertThat(broadcaster).isInstanceOf(InMemoryProgressBroadcaster.class);

            ProgressSseFixtures.insertFixtures(cleanupJdbc(), USER_ID, DOCUMENT_ID, MINDMAP_ID, JOB_ID);
            String token = ProgressSseFixtures.issueToken(context, USER_ID);
            String externalJobId = ProgressSseFixtures.externalId(context, JOB_ID);

            stream[0] = SseTestClient.open(8085, externalJobId, token);
            await().atMost(Duration.ofSeconds(15))
                    .until(() -> stream[0].contains("status"));

            broadcaster.publish(JOB_ID, "ready",
                    Map.of("jobId", externalJobId, "status", "READY"));
            broadcaster.complete(JOB_ID);

            await().atMost(Duration.ofSeconds(10)).until(() -> stream[0].contains("ready"));
            assertThat(stream[0].awaitEnd(Duration.ofSeconds(10))).isTrue();
            assertThat(stream[0].count("ready")).isEqualTo(1);
        } finally {
            if (stream[0] != null) {
                stream[0].close();
            }
            context.close();
        }
    }

    @Test
    void redisUnavailableDoesNotBlockStartupAndPublishFallsBackToLocal() throws Exception {
        long startedAt = System.currentTimeMillis();
        ConfigurableApplicationContext context = startContext(8086,
                "--app.cache.redis-enabled=true",
                "--spring.data.redis.host=127.0.0.1",
                "--spring.data.redis.port=1",
                "--spring.data.redis.password=");
        SseTestClient[] stream = new SseTestClient[1];
        try {
            // 监听容器在独立线程异步建连+后台重连，上下文启动不被 Redis 宕机拖垮
            assertThat(System.currentTimeMillis() - startedAt).isLessThan(60_000L);
            ProgressBroadcaster broadcaster = context.getBean(ProgressBroadcaster.class);
            assertThat(broadcaster).isInstanceOf(RedisPubSubBroadcaster.class);

            JdbcTemplate jdbc = cleanupJdbc();
            ProgressSseFixtures.insertFixtures(jdbc, USER_ID, DOCUMENT_ID, MINDMAP_ID, JOB_ID);
            String token = ProgressSseFixtures.issueToken(context, USER_ID);
            String externalJobId = ProgressSseFixtures.externalId(context, JOB_ID);

            stream[0] = SseTestClient.open(8086, externalJobId, token);
            await().atMost(Duration.ofSeconds(15))
                    .until(() -> stream[0].contains("status"));

            // convertAndSend 连接失败被 catch 并降级本机直投
            broadcaster.publish(JOB_ID, "ready",
                    Map.of("jobId", externalJobId, "status", "READY"));
            broadcaster.complete(JOB_ID);

            await().atMost(Duration.ofSeconds(30)).until(() -> stream[0].contains("ready"));
            assertThat(stream[0].awaitEnd(Duration.ofSeconds(15))).isTrue();
        } finally {
            if (stream[0] != null) {
                stream[0].close();
            }
            context.close();
        }
    }
}
