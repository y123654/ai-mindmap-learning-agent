package com.example.aimindmap.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.example.aimindmap.vectorindex.VectorIndexTaskService;
import com.example.aimindmap.vectorindex.VectorResourceType;
import com.example.aimindmap.vectorindex.VectorTaskOperation;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * H1-2：confirm 前 kill 应用（relay 不跑），outbox 保持 PENDING；
 * 重启后 relay 补投递，任务仍被消费成功——本地发件箱保证不丢。
 * 两个应用上下文在同一测试方法内手工启停。
 */
class OutboxRestartRecoveryMqIntegrationTest extends AbstractMqIntegrationTest {

    @Test
    void h1_2PendingOutboxSurvivesApplicationKillAndRestart() throws Exception {
        JdbcTemplate jdbc = cleanupJdbc();
        jdbc.update("INSERT INTO users(id, username, password_hash, major) "
                + "VALUES (888201, 'it888201', 'x', '计算机')");
        jdbc.update("INSERT INTO documents(id, title, content, status, user_id) "
                + "VALUES (888201, '设备管理', '通道是一种 IO 设备。', 'active', 888201)");

        ConfigurableApplicationContext context1 = startContext(0,
                "--app.mq.outbox-fixed-delay-ms=3600000");
        try {
            VectorIndexTaskService service1 =
                    context1.getBean(VectorIndexTaskService.class);
            PlatformTransactionManager txManager1 =
                    context1.getBean(PlatformTransactionManager.class);
            long taskId = invokeAddTask(service1, txManager1, 888201L,
                    VectorResourceType.DOCUMENT_CHUNK, "888201", 0, VectorTaskOperation.UPSERT);

            // relay 间隔被拉到 1 小时：任务行 PENDING、outbox PENDING，并保持住
            assertThat(taskStatus(jdbc, taskId)).isEqualTo("PENDING");
            assertThat(outboxStatus(jdbc, taskId)).isEqualTo("PENDING");
            Thread.sleep(Duration.ofSeconds(2).toMillis());
            assertThat(taskStatus(jdbc, taskId)).isEqualTo("PENDING");
            assertThat(outboxStatus(jdbc, taskId)).isEqualTo("PENDING");
        } finally {
            // 模拟 kill：直接关闭上下文（没有优雅发消息的机会）
            context1.close();
        }

        ConfigurableApplicationContext context2 = startContext(0);
        try {
            long taskId = jdbc.queryForObject(
                    "SELECT id FROM vector_index_tasks WHERE resource_id = '888201'", Long.class);
            // 重启后 relay（1s）补投递，消费者完成
            await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                    assertThat(outboxStatus(jdbc, taskId)).isEqualTo("SENT"));
            await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                    assertThat(taskStatus(jdbc, taskId)).isEqualTo("SUCCEEDED"));
            verify(MqIntegrationMocks.GATEWAY, times(1))
                    .upsert(org.mockito.ArgumentMatchers.anyString(),
                            org.mockito.ArgumentMatchers.any());
        } finally {
            context2.close();
        }
    }

    private static String taskStatus(JdbcTemplate jdbc, long taskId) {
        return jdbc.queryForObject(
                "SELECT status FROM vector_index_tasks WHERE id = ?", String.class, taskId);
    }

    private static String outboxStatus(JdbcTemplate jdbc, long taskId) {
        return jdbc.queryForObject(
                "SELECT status FROM outbox_events WHERE task_id = ?", String.class, taskId);
    }
}
