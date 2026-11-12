package com.example.aimindmap.integration;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.aimindmap.ratelimit.InMemorySlidingWindowRateLimiter;
import com.example.aimindmap.ratelimit.RateLimiter;
import com.example.aimindmap.ratelimit.RedisSlidingWindowRateLimiter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.testcontainers.containers.Container;

/**
 * 阶段七-3 接口限流集成测试（完整应用上下文 + 真实 HTTP，方案 321 行验收）：
 * <ol>
 *   <li><b>真实 Redis 阈值生效</b>：阈值 2/min，第 3 次真实 HTTP 请求返回 429 + {@code RATE_LIMITED}
 *       + 中文提示；redis-cli 直查键名 {@code aimindmap:ratelimit:sync-trigger:{userId}} 存在、
 *       类型为 zset、{@code 0 < TTL <= 60}；带 {@code Accept: text/event-stream} 的超限请求仍是 429 JSON
 *       （不是 406，验证 SSE Accept 头坑在真实链路上也已处理）。</li>
 *   <li><b>跨实例共享计数</b>：两个实例（8092/8093）指向同一次性 Redis，同一 userId 交替请求，
 *       合计超过阈值即 429 —— 证明是 Redis 原子共享窗口，不是各实例各算各的。</li>
 *   <li><b>Redis 宕机优雅降级</b>：redis-enabled=true 但指向 127.0.0.1:1（拒绝连接），
 *       上下文正常启动、装配的仍是 Redis 实现，但限流退化为单机内存窗口且<b>仍然生效</b>
 *       （阈值 1/min，第 2 次 429），并有 WARN 日志。</li>
 *   <li><b>redis-enabled=false</b>：装配纯内存窗口实现，限流同样生效（方案 §3.5 第 125 行）。</li>
 * </ol>
 *
 * <p>红线：真 Redis 一律 Testcontainers 一次性容器（随机端口、本项目 db2），宕机场景指向 127.0.0.1:1，
 * 绝不触碰开发机 Redis 容器（6379，db0/db1/db5 他人占用）；键查询一律显式键名 + DEL，禁 KEYS/SCAN。
 *
 * <p>受限接口选 {@code POST /api/documents/{documentId}/sync-jobs}（阈值最小、无需上传文件）。
 * 文档 ID 刻意不存在，请求在归属校验处返回 404，因此<b>不写任何业务数据、不发任何 MQ 消息</b>；
 * 限流判定发生在 {@code preHandle}，超限请求连 Controller 都进不去。
 */
class RateLimitIntegrationTest extends AbstractMqIntegrationTest {

    /** 不存在的文档 ID：让未超限的请求以 404 收场，避免写库与发 MQ。 */
    private static final long DOCUMENT_ID = 888910L;

    private static final long USER_REAL_REDIS = 888911L;
    private static final long USER_DUAL_INSTANCE = 888912L;
    private static final long USER_REDIS_DOWN = 888913L;
    private static final long USER_REDIS_DISABLED = 888914L;

    private static final Logger log = LoggerFactory.getLogger(RateLimitIntegrationTest.class);

    private static final String CREATE_SYNC_JOB_BODY = "{\"expectedContentVersion\":1}";

    private final List<String> usedKeys = new ArrayList<>();

    @BeforeAll
    static void startRedis() {
        IntegrationTestRedis.start();
    }

    @AfterEach
    void cleanupRedisKeys() {
        // 显式键名 DEL（禁 KEYS/SCAN）；键本身 60s 也会自然过期，这里只为避免用例间串扰
        for (String key : usedKeys) {
            try {
                IntegrationTestRedis.exec("redis-cli", "-n", "2", "DEL", key);
            } catch (RuntimeException e) {
                // 一次性容器可能已随 JVM 关闭；清理失败不影响断言，但按项目日志红线不许静默
                log.warn("限流集成测试清理 Redis 键失败（键会在 60s 内自然过期） key={}", key, e);
            }
        }
        usedKeys.clear();
    }

    @Test
    void realRedisThresholdRejectsThirdRequestAndWritesZsetKeyWithTtl() throws Exception {
        ConfigurableApplicationContext context = startContext(8091,
                withRedisArgs("--app.ratelimit.sync-per-min=2"));
        try {
            assertThat(context.getBean(RateLimiter.class))
                    .isInstanceOf(RedisSlidingWindowRateLimiter.class);

            String token = ProgressSseFixtures.issueToken(context, USER_REAL_REDIS);
            String path = syncJobPath(context);
            String key = "aimindmap:ratelimit:sync-trigger:" + USER_REAL_REDIS;
            usedKeys.add(key);

            assertThat(post(8091, path, token, MediaType.APPLICATION_JSON_VALUE).statusCode())
                    .isNotEqualTo(429);
            assertThat(post(8091, path, token, MediaType.APPLICATION_JSON_VALUE).statusCode())
                    .isNotEqualTo(429);
            assertThat(execCli("ZCARD", key)).isEqualTo("2");

            HttpResponse<String> third = post(8091, path, token, MediaType.APPLICATION_JSON_VALUE);
            assertThat(third.statusCode()).isEqualTo(429);
            assertThat(third.headers().firstValue(HttpHeaders.CONTENT_TYPE))
                    .hasValueSatisfying(value -> assertThat(value).contains("application/json"));
            assertThat(third.body())
                    .contains("\"code\":\"RATE_LIMITED\"")
                    .contains("请求过于频繁，请稍后再试");
            // 拒绝不写入窗口：ZCARD 仍是 2
            assertThat(execCli("ZCARD", key)).isEqualTo("2");

            // redis-cli 直查（-n 2 = 本项目 db2）：键存在、类型 zset、TTL 落在 (0, 60]
            assertThat(execCli("EXISTS", key)).isEqualTo("1");
            assertThat(execCli("TYPE", key)).isEqualTo("zset");
            int ttl = Integer.parseInt(execCli("TTL", key));
            assertThat(ttl).isGreaterThan(0).isLessThanOrEqualTo(60);

            // SSE Accept 头坑：Accept 为 text/event-stream 的超限请求仍是 429 JSON，不是 406
            HttpResponse<String> eventStreamAccept =
                    post(8091, path, token, MediaType.TEXT_EVENT_STREAM_VALUE);
            assertThat(eventStreamAccept.statusCode()).isEqualTo(429);
            assertThat(eventStreamAccept.body()).contains("RATE_LIMITED");
            assertThat(eventStreamAccept.headers().firstValue(HttpHeaders.CONTENT_TYPE))
                    .hasValueSatisfying(value -> assertThat(value).contains("application/json"));
        } finally {
            context.close();
        }
    }

    @Test
    void twoInstancesShareOneSlidingWindow() throws Exception {
        ConfigurableApplicationContext instanceA = startContext(8092,
                withRedisArgs("--app.ratelimit.sync-per-min=2"));
        ConfigurableApplicationContext instanceB = null;
        try {
            instanceB = startContext(8093, withRedisArgs("--app.ratelimit.sync-per-min=2"));
            String tokenA = ProgressSseFixtures.issueToken(instanceA, USER_DUAL_INSTANCE);
            String tokenB = ProgressSseFixtures.issueToken(instanceB, USER_DUAL_INSTANCE);
            String path = syncJobPath(instanceA);
            usedKeys.add("aimindmap:ratelimit:sync-trigger:" + USER_DUAL_INSTANCE);

            // A、B 各一次，合计 2 = 阈值；若两实例各算各的，下面两次都不会 429
            assertThat(post(8092, path, tokenA, MediaType.APPLICATION_JSON_VALUE).statusCode())
                    .isNotEqualTo(429);
            assertThat(post(8093, path, tokenB, MediaType.APPLICATION_JSON_VALUE).statusCode())
                    .isNotEqualTo(429);
            assertThat(post(8092, path, tokenA, MediaType.APPLICATION_JSON_VALUE).statusCode())
                    .isEqualTo(429);
            assertThat(post(8093, path, tokenB, MediaType.APPLICATION_JSON_VALUE).statusCode())
                    .isEqualTo(429);

            assertThat(execCli("ZCARD", "aimindmap:ratelimit:sync-trigger:" + USER_DUAL_INSTANCE))
                    .isEqualTo("2");
        } finally {
            if (instanceB != null) {
                instanceB.close();
            }
            instanceA.close();
        }
    }

    @Test
    void redisUnavailableDegradesToInMemoryWindowAndStillRejects() throws Exception {
        ConfigurableApplicationContext context = startContext(8094,
                "--app.cache.redis-enabled=true",
                "--spring.data.redis.host=127.0.0.1",
                "--spring.data.redis.port=1",
                "--spring.data.redis.password=",
                "--app.ratelimit.sync-per-min=1");
        // 日志断言用的 appender 必须在 startContext 之后挂：Spring Boot 每次启动应用都会重新初始化
        // Logback（LoggerContext reset），先挂会被摘掉，导致断言拿到空列表
        ch.qos.logback.classic.Logger limiterLogger = (ch.qos.logback.classic.Logger)
                LoggerFactory.getLogger(RedisSlidingWindowRateLimiter.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        limiterLogger.addAppender(appender);
        try {
            // Redis 宕机不拖垮上下文启动，装配的仍是 Redis 实现
            RateLimiter limiter = context.getBean(RateLimiter.class);
            assertThat(limiter).isInstanceOf(RedisSlidingWindowRateLimiter.class);

            String token = ProgressSseFixtures.issueToken(context, USER_REDIS_DOWN);
            String path = syncJobPath(context);

            assertThat(post(8094, path, token, MediaType.APPLICATION_JSON_VALUE).statusCode())
                    .isNotEqualTo(429);
            HttpResponse<String> second = post(8094, path, token, MediaType.APPLICATION_JSON_VALUE);
            assertThat(second.statusCode()).isEqualTo(429);
            assertThat(second.body()).contains("RATE_LIMITED");

            // 降级必须留痕：WARN 说明本次改由单机内存窗口判定
            assertThat(appender.list).anySatisfy(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getFormattedMessage()).contains("降级单机内存窗口");
                assertThat(event.getFormattedMessage()).contains("sync-trigger");
            });
        } finally {
            limiterLogger.detachAppender(appender);
            context.close();
        }
    }

    @Test
    void redisDisabledUsesPureInMemoryWindow() throws Exception {
        // 测试上下文默认 app.cache.redis-enabled=false（TestRedisDisableEnvironmentPostProcessor），
        // 阈值同样默认 0，这里显式设回 1 才能观察到限流生效
        ConfigurableApplicationContext context = startContext(8095,
                "--app.ratelimit.sync-per-min=1");
        try {
            assertThat(context.getBean(RateLimiter.class))
                    .isInstanceOf(InMemorySlidingWindowRateLimiter.class);

            String token = ProgressSseFixtures.issueToken(context, USER_REDIS_DISABLED);
            String path = syncJobPath(context);

            assertThat(post(8095, path, token, MediaType.APPLICATION_JSON_VALUE).statusCode())
                    .isNotEqualTo(429);
            HttpResponse<String> second = post(8095, path, token, MediaType.APPLICATION_JSON_VALUE);
            assertThat(second.statusCode()).isEqualTo(429);
            assertThat(second.body())
                    .contains("\"code\":\"RATE_LIMITED\"")
                    .contains("请求过于频繁，请稍后再试");
        } finally {
            context.close();
        }
    }

    private String syncJobPath(ConfigurableApplicationContext context) {
        return "/api/documents/" + ProgressSseFixtures.externalId(context, DOCUMENT_ID) + "/sync-jobs";
    }

    private static String[] withRedisArgs(String... extra) {
        List<String> args = new ArrayList<>(List.of(IntegrationTestRedis.appArgs()));
        args.addAll(List.of(extra));
        return args.toArray(String[]::new);
    }

    private static HttpResponse<String> post(int port, String path, String token, String accept)
            throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(30))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .header(HttpHeaders.ACCEPT, accept)
                .POST(HttpRequest.BodyPublishers.ofString(CREATE_SYNC_JOB_BODY, StandardCharsets.UTF_8))
                .build();
        return HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
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