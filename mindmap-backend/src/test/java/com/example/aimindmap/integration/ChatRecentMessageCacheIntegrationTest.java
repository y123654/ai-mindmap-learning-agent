package com.example.aimindmap.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.example.aimindmap.assistant.ChatRecentMessageCache;
import com.example.aimindmap.assistant.ChatRecentMessageCache.CachedMessage;
import com.example.aimindmap.assistant.DisabledChatRecentMessageCache;
import com.example.aimindmap.assistant.KnowledgeAssistantService;
import com.example.aimindmap.assistant.RedisChatRecentMessageCache;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.Container;

/**
 * 阶段七-2 短期记忆缓存集成测试（真实装配，完整应用上下文）：
 * <ol>
 *   <li>真实 Redis 往返：put 后用 redis-cli 直查 EXISTS/TTL，验证键名布局
 *       {@code aimindmap:chat:{sessionId}:recent:{N}} 与 0&lt;TTL&le;60；get 命中且内容
 *       逐字节一致；evictSession 后两个键显式消失；</li>
 *   <li>降级：redis-enabled=true 但指向 127.0.0.1:1（拒绝连接），get/put/evict 全部
 *       异常降级、不抛出，聊天组件装配完整（参考 ProgressBroadcastFallbackIntegrationTest 手法）；</li>
 *   <li>redis-enabled=false：装配关闭实现，全 no-op。</li>
 * </ol>
 * 红线：真 Redis 一律 Testcontainers 一次性容器（随机端口，db2），宕机场景指向 127.0.0.1:1，
 * 绝不触碰开发机 Redis 容器（6379，db0/db1/db5 他人占用）。
 */
class ChatRecentMessageCacheIntegrationTest extends AbstractMqIntegrationTest {

    private static final long SESSION_ID = 888901L;
    private static final String KEY_RECENT_30 = "aimindmap:chat:888901:recent:30";
    private static final String KEY_RECENT_10 = "aimindmap:chat:888901:recent:10";

    @BeforeAll
    static void startRedis() {
        IntegrationTestRedis.start();
    }

    @Test
    void realRedisRoundTripKeyLayoutTtlAndExplicitEvict() {
        ConfigurableApplicationContext context =
                startContext(8087, IntegrationTestRedis.appArgs());
        try {
            ChatRecentMessageCache bean = context.getBean(ChatRecentMessageCache.class);
            assertThat(bean).isInstanceOf(RedisChatRecentMessageCache.class);
            RedisChatRecentMessageCache cache = (RedisChatRecentMessageCache) bean;

            List<CachedMessage> snapshots = List.of(
                    new CachedMessage("user", "什么是进程"),
                    new CachedMessage("assistant", "进程是资源分配的基本单位"));
            cache.put(SESSION_ID, 30, snapshots);
            cache.put(SESSION_ID, 10, List.of(new CachedMessage("user", "最近十条快照")));

            // redis-cli 直查（不经过被测应用，-n 2 = 本项目 db2）：键名布局与 TTL
            assertThat(execCli("EXISTS", KEY_RECENT_30)).isEqualTo("1");
            assertThat(execCli("EXISTS", KEY_RECENT_10)).isEqualTo("1");
            int ttl = Integer.parseInt(execCli("TTL", KEY_RECENT_30));
            assertThat(ttl).isGreaterThan(0).isLessThanOrEqualTo(60);

            // get 命中且内容一致（含中文往返保真），命中计数增加
            long hitsBefore = cache.getHits();
            assertThat(cache.get(SESSION_ID, 30)).isEqualTo(snapshots);
            assertThat(cache.getHits()).isEqualTo(hitsBefore + 1);

            // 未命中语义：不存在的会话返回 null
            assertThat(cache.get(SESSION_ID + 1, 30)).isNull();

            // evictSession 显式删两键（无 KEYS/SCAN），删除后 get 回到未命中
            cache.evictSession(SESSION_ID);
            assertThat(execCli("EXISTS", KEY_RECENT_30)).isEqualTo("0");
            assertThat(execCli("EXISTS", KEY_RECENT_10)).isEqualTo("0");
            assertThat(cache.get(SESSION_ID, 30)).isNull();
        } finally {
            context.close();
        }
    }

    @Test
    void redisUnavailableDegradesAllOperationsWithoutBreakingChatWiring() {
        ConfigurableApplicationContext context = startContext(8088,
                "--app.cache.redis-enabled=true",
                "--spring.data.redis.host=127.0.0.1",
                "--spring.data.redis.port=1",
                "--spring.data.redis.password=");
        try {
            ChatRecentMessageCache bean = context.getBean(ChatRecentMessageCache.class);
            assertThat(bean).isInstanceOf(RedisChatRecentMessageCache.class);

            // get/put/evict 全部异常降级：不抛出，get 按未命中返回 null
            assertThatCode(() -> bean.put(SESSION_ID, 30,
                    List.of(new CachedMessage("user", "你好")))).doesNotThrowAnyException();
            assertThat(bean.get(SESSION_ID, 30)).isNull();
            assertThatCode(() -> bean.evictSession(SESSION_ID)).doesNotThrowAnyException();

            // 聊天组件装配完整：Redis 宕机不影响上下文启动与服务可用性（主链路不受缓存故障影响）
            assertThat(context.getBean(KnowledgeAssistantService.class)).isNotNull();
        } finally {
            context.close();
        }
    }

    @Test
    void redisDisabledWiresNoOpCache() {
        // 测试上下文默认 app.cache.redis-enabled=false（TestRedisDisableEnvironmentPostProcessor）
        ConfigurableApplicationContext context = startContext(8089);
        try {
            ChatRecentMessageCache bean = context.getBean(ChatRecentMessageCache.class);
            assertThat(bean).isInstanceOf(DisabledChatRecentMessageCache.class);

            assertThat(bean.get(SESSION_ID, 30)).isNull();
            assertThatCode(() -> {
                bean.put(SESSION_ID, 30, List.of(new CachedMessage("user", "你好")));
                bean.evictSession(SESSION_ID);
            }).doesNotThrowAnyException();
            assertThat(bean.get(SESSION_ID, 30)).isNull();
        } finally {
            context.close();
        }
    }

    /** redis-cli 直查一次性容器（--raw 去引号，-n 2 固定本项目 db2）。 */
    private static String execCli(String... redisArgs) {
        String[] command = new String[4 + redisArgs.length];
        command[0] = "redis-cli";
        command[1] = "--raw";
        command[2] = "-n";
        command[3] = "2";
        System.arraycopy(redisArgs, 0, command, 4, redisArgs.length);
        Container.ExecResult result = IntegrationTestRedis.exec(command);
        if (result.getExitCode() != 0) {
            throw new IllegalStateException("redis-cli 执行失败: " + result.getStderr());
        }
        return result.getStdout().trim();
    }
}
