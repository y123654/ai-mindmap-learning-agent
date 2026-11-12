package com.example.aimindmap.integration;

import com.example.aimindmap.auth.ExternalIdCodec;
import com.example.aimindmap.auth.JwtService;
import com.example.aimindmap.auth.UserEntity;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

/** 阶段七-1 SSE 集成测试的公共夹具：888 前缀测试数据的插入/清理与 JWE 令牌签发。 */
final class ProgressSseFixtures {

    private ProgressSseFixtures() {
    }

    static void insertFixtures(JdbcTemplate jdbc, long userId, long documentId,
                               long mindmapId, long jobId) {
        jdbc.update("INSERT INTO users(id, username, password_hash, major) VALUES (?, ?, 'x', '计算机')",
                userId, "it" + userId);
        jdbc.update("INSERT INTO documents(id, title, content, content_version, source_type, status, user_id) "
                        + "VALUES (?, 'it888-sse', '# 标题', 1, 'markdown', 'active', ?)",
                documentId, userId);
        jdbc.update("INSERT INTO mindmaps(id, document_id, graph, graph_version, sync_status, user_id) "
                        + "VALUES (?, ?, CAST(? AS jsonb), 1, 'idle', ?)",
                mindmapId, documentId,
                "{\"rootId\":\"root\",\"version\":1,\"tree\":{\"id\":\"root\",\"label\":\"标题\"}}", userId);
        jdbc.update("INSERT INTO sync_jobs(id, document_id, mindmap_id, status, "
                        + "target_content_version, base_graph_version) VALUES (?, ?, ?, 'PENDING', 1, 1)",
                jobId, documentId, mindmapId);
    }

    static void cleanupFixtures(JdbcTemplate jdbc, long low, long high) {
        jdbc.update("DELETE FROM outbox_events WHERE task_type = 'MINDMAP_SYNC' AND task_id IN ("
                + "SELECT id FROM sync_jobs WHERE document_id BETWEEN ? AND ?)", low, high);
        jdbc.update("DELETE FROM idempotent_consumptions WHERE message_id IN ("
                + "SELECT 'MINDMAP_SYNC:' || id FROM sync_jobs WHERE document_id BETWEEN ? AND ?)", low, high);
        jdbc.update("DELETE FROM document_block_snapshots WHERE document_id BETWEEN ? AND ?", low, high);
        jdbc.update("DELETE FROM sync_jobs WHERE document_id BETWEEN ? AND ?", low, high);
        jdbc.update("DELETE FROM mindmaps WHERE document_id BETWEEN ? AND ?", low, high);
        jdbc.update("DELETE FROM documents WHERE id BETWEEN ? AND ?", low, high);
        jdbc.update("DELETE FROM users WHERE id BETWEEN ? AND ?", low, high);
    }

    /** 用上下文内 JwtService（默认开发密钥）为测试用户签发 JWE 令牌，复用生产同一套签发逻辑。 */
    static String issueToken(ConfigurableApplicationContext context, long userId) {
        UserEntity user = new UserEntity();
        ReflectionTestUtils.setField(user, "id", userId);
        user.setUsername("it" + userId);
        return context.getBean(JwtService.class).issue(user);
    }

    static String externalId(ConfigurableApplicationContext context, Long internalId) {
        return context.getBean(ExternalIdCodec.class).encode(internalId);
    }
}
