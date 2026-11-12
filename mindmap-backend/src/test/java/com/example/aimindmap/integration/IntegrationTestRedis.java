package com.example.aimindmap.integration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * 阶段七-1/7-2：跨实例 SSE 与短期记忆缓存集成测试专用的 JVM 内单例 Redis（Testcontainers 一次性实例）。
 *
 * <p>红线：严禁使用开发机 Redis 容器（127.0.0.1:6379，db2 为本项目数据、其它 db 他人占用）。
 * 本容器使用随机映射端口、默认无密码，与开发机完全隔离；应用连接仍走 database=2
 * （Pub/Sub 频道不区分 db 归属，但与既有配置保持一致）。
 */
final class IntegrationTestRedis {

    private static final Logger log = LoggerFactory.getLogger(IntegrationTestRedis.class);

    private static final DockerImageName IMAGE = DockerImageName.parse("redis:7-alpine");
    private static final int REDIS_PORT = 6379;

    private static GenericContainer<?> container;

    private IntegrationTestRedis() {
    }

    /** 幂等启动；同一 JVM 内所有 SSE IT 共用一个 Redis。 */
    static synchronized void start() {
        if (container == null) {
            container = new GenericContainer<>(IMAGE)
                    .withExposedPorts(REDIS_PORT)
                    .waitingFor(Wait.forListeningPort());
            container.start();
            log.info("Testcontainers Redis 已启动 host={} port={}", host(), port());
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                if (container != null) {
                    container.stop();
                }
            }));
        }
    }

    static String host() {
        return container.getHost();
    }

    static int port() {
        return container.getMappedPort(REDIS_PORT);
    }

    /** 命令行参数：把被测应用上下文指向本一次性 Redis，并清空默认口令（测试镜像无密码）。 */
    static String[] appArgs() {
        return new String[]{
                "--app.cache.redis-enabled=true",
                "--spring.data.redis.host=" + host(),
                "--spring.data.redis.port=" + port(),
                "--spring.data.redis.password="
        };

}
    /**
     * 通用容器内命令执行入口（redis-cli 直查，不经过被测应用）：
     * 阶段七-2 缓存 IT 用它断言真实键名布局与 TTL（db2 需带 {@code -n 2}）。
     */
    static Container.ExecResult exec(String... command) {
        try {
            return container.execInContainer(command);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "容器内命令执行失败: " + String.join(" ", command), e);
        }
    }

    /**
     * 当前持有 PSUBSCRIBE 模式订阅的客户端连接数（redis-cli 驱动，不经过被测应用）。
     * 注意不能用 PUBSUB NUMPAT：它统计去重后的模式数，两个实例订阅同一模式时仍为 1。
     */
    static int patternSubscriberCount() {
        try {
            Container.ExecResult result = container.execInContainer(
                    "redis-cli", "--raw", "CLIENT", "LIST");
            if (result.getExitCode() != 0) {
                throw new IllegalStateException("CLIENT LIST 失败: " + result.getStderr());
            }
            int count = 0;
            for (String line : result.getStdout().split("\n")) {
                if (line.contains("cmd=psubscribe")) {
                    count++;
                }
            }
            return count;
        } catch (Exception e) {
            throw new IllegalStateException("查询 psubscribe 客户端数失败", e);
        }
    }
}
