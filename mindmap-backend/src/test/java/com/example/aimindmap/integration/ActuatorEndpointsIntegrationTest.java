package com.example.aimindmap.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.aimindmap.metrics.BusinessMetrics;
import com.example.aimindmap.metrics.MqTaskMetrics;
import com.example.aimindmap.mqsupport.TaskType;
import com.example.aimindmap.progress.LocalSseRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * 阶段八-1（2026-10-22 返工重写）：actuator 端点暴露与鉴权放行的<b>真实 servlet 上下文 +
 * 真实 HTTP</b> 集成测试。
 *
 * <p><b>为什么不再用 {@code @SpringBootTest(webEnvironment=MOCK)} + MockMvc：</b>
 * MOCK 环境下 MockMvc 不映射 servlet 型的 PrometheusScrapeEndpoint，GET /actuator/prometheus
 * 落到静态资源兜底 ResourceHttpRequestHandler → NoResourceFoundException → 被
 * GlobalExceptionHandler 兜底成 500（生产真实容器里该端点注册正常，组长已实测：启动日志
 * "Exposing 2 endpoints beneath base path '/actuator'"）；同时匿名访问未暴露端点会先被
 * SecurityConfig 的 {@code anyRequest().authenticated()} 拦成 401 而非 404。
 * 因此本测试复用 7-1/7-2/7-3 已验证可行的 IT 范式：继承 {@link AbstractMqIntegrationTest}，
 * 用 {@code startContext} 起真实 SERVLET 上下文（Testcontainers RabbitMQ + 本机 PG it888
 * schema，Redis 由 TestRedisDisableEnvironmentPostProcessor 默认关），再用 JDK HttpClient
 * （java.net.http，零新增依赖）对 http://127.0.0.1:{@value #PORT} 发真实 GET。
 *
 * <p>覆盖七件事（语义与原 MOCK 版 7 例一致，未暴露端点断言强化为「匿名 / 带票」两态）：
 * <ol>
 *   <li>{@code /actuator/prometheus} <b>匿名</b> → 200，文本含七组业务指标各自的代表性
 *       Prometheus 名（阶段八-2 Grafana 面板的引用依据）+ JVM 指标 + 分位直方图证据；</li>
 *   <li>抓取文本逐行不含 user_id=/session_id= 等高基数 tag，不含 sk-/api_key/password；</li>
 *   <li>{@code aimindmap_sse_connections} Gauge 反映真实活跃连接数；</li>
 *   <li>{@code /actuator/health} 匿名 → 200 且含 {@code status} 字段；</li>
 *   <li>未暴露端点（env/beans/configprops/heapdump/threaddump）两态：匿名 → <b>401</b>
 *       （被鉴权拦）；带合法 JWE → <b>404 或 500-INTERNAL_ERROR，绝不 200、绝不回端点数据</b>
 *       （证明端点确实未暴露，而非仅被鉴权挡住）。两种收场都是真实 servlet 环境的合法行为：
 *       exposure 白名单只有 health,prometheus，未暴露路径无端点映射，落到静态资源兜底
 *       ResourceHttpRequestHandler 抛 NoResourceFoundException —— 若被 GlobalExceptionHandler
 *       的 catch-all 接住即 500 + INTERNAL_ERROR（组长在 MOCK 环境已确诊此机制），若走容器
 *       默认错误处理即 404。断言同时锁死「响应体绝不携带 propertySources/spring.datasource
 *       等端点数据」，证明力度不降；</li>
 *   <li>受保护业务接口匿名 → 401 + {@code code=UNAUTHORIZED}（放行只针对两个 actuator 路径）；</li>
 *   <li>{@code /actuator} 发现页：匿名 → 401；带合法 JWE → 200 时链接页只含 health/prometheus，
 *       <b>绝不允许</b>列出 env/beans/heapdump 等未暴露端点；404/500 同样接受（发现页不在
 *       exposure 白名单里，不同 Spring Boot 小版本行为不同，按真实行为宽断言）。</li>
 * </ol>
 *
 * <p>①②③④ 四组指标是「首次记录才产生序列」（micrometer 默认行为），启动上下文后先经
 * <b>真实 Bean</b> 各记一次样本再抓端点，验证「组件 → PrometheusMeterRegistry → 真实 HTTP
 * 抓取文本」完整链路；⑤⑥⑦ 三组由 {@code BusinessMetricsBinder} 启动即绑定，无需流量。
 * {@code --management.health.redis.enabled=false} 只影响本测试上下文（Redis 在全部测试上下文
 * 里默认关，避免健康聚合随开发机 6379 开关抖动），生产 application.yml 零改动。
 */
class ActuatorEndpointsIntegrationTest extends AbstractMqIntegrationTest {

    /** 本测试专用端口：既有 IT 占用 0/8081~8089/8091~8095，8096 经全仓检索无冲突。 */
    private static final int PORT = 8096;

    /** 签发 JWE 用的测试用户 ID（888 前缀，基类 @AfterEach 统一清理；签发/解析不查库）。 */
    private static final long TOKEN_USER_ID = 888990L;

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private static ConfigurableApplicationContext context;
    private static String userToken;

    /** 七组指标各取一个代表性 Prometheus 名（阶段八-2 面板直接引用这些名字）。 */
    private static final String[] REPRESENTATIVE_METRIC_NAMES = {
            // ① 任务入队 / 消费 / 死信计数与耗时
            "aimindmap_task_enqueued_total",
            "aimindmap_task_consumed_total",
            "aimindmap_task_dead_letter_total",
            "aimindmap_task_consume_seconds_count",
            // ② PDF 上传 RT
            "aimindmap_pdf_upload_seconds_count",
            // ③ 同步 RT
            "aimindmap_sync_job_seconds_count",
            // ④ AI / Embedding 耗时与失败率
            "aimindmap_ai_call_seconds_count",
            "aimindmap_ai_call_failure_total",
            "aimindmap_embedding_call_seconds_count",
            "aimindmap_embedding_call_failure_total",
            // ⑤ 缓存命中率分子分母
            "aimindmap_chat_recent_cache_hits_total",
            "aimindmap_chat_recent_cache_misses_total",
            // ⑥ 限流拒绝数
            "aimindmap_ratelimit_rejections_total",
            // ⑦ SSE 连接数
            "aimindmap_sse_connections",
    };

    @BeforeAll
    static void startRealServletContext() {
        context = startContext(PORT, "--management.health.redis.enabled=false");
        recordOneSamplePerLazyGroup();
        userToken = ProgressSseFixtures.issueToken(context, TOKEN_USER_ID);
    }

    @AfterAll
    static void stopRealServletContext() {
        if (context != null) {
            context.close();
        }
    }

    @Test
    void prometheusEndpointIsAnonymousOverRealHttpAndCarriesAllSevenMetricGroups() throws Exception {
        HttpResponse<String> response = get("/actuator/prometheus", null);

        assertThat(response.statusCode()).isEqualTo(200);
        String body = response.body();
        for (String metricName : REPRESENTATIVE_METRIC_NAMES) {
            assertThat(body).as("七组业务指标代表名 %s", metricName).contains(metricName);
        }
        // JVM 指标由 micrometer 自动装配，随抓取一并出现
        assertThat(body).contains("jvm_memory_used_bytes");
        // 组长选定的 A 方案：aimindmap.* 耗时 Timer 全部开服务端分位直方图（P95 跨实例可聚合）
        assertThat(body).contains("# TYPE aimindmap_sync_job_seconds histogram");
        assertThat(body).contains("aimindmap_sync_job_seconds_bucket{");
        assertThat(body).contains("le=\"+Inf\"");
    }

    @Test
    void prometheusTextCarriesNoHighCardinalityTagsOrSecretsOverRealHttp() throws Exception {
        HttpResponse<String> response = get("/actuator/prometheus", null);
        assertThat(response.statusCode()).isEqualTo(200);
        String body = response.body();

        for (String line : body.split("\n")) {
            if (!line.startsWith("aimindmap_")) {
                continue;
            }
            // 埋点铁律：业务指标 tag 只允许枚举级维度，绝不允许 ID 类高基数/隐私 tag
            assertThat(line).doesNotContain("user_id=").doesNotContain("session_id=")
                    .doesNotContain("job_id=").doesNotContain("task_id=")
                    .doesNotContain("message_id=").doesNotContain("document_id=");
        }
        // 安全红线：指标文本不含任何密钥/口令痕迹（配置类端点本身也未暴露，见两态测试）
        assertThat(body).doesNotContain("sk-").doesNotContain("api_key").doesNotContain("password");
    }

    @Test
    void sseGaugeOverRealHttpReflectsLiveConnectionCount() throws Exception {
        LocalSseRegistry registry = context.getBean(LocalSseRegistry.class);
        try {
            registry.subscribe(990001L);
            registry.subscribe(990002L);

            HttpResponse<String> response = get("/actuator/prometheus", null);
            assertThat(response.statusCode()).isEqualTo(200);

            Double gaugeValue = parseGaugeValue(response.body(), "aimindmap_sse_connections");
            assertThat(gaugeValue).isNotNull();
            assertThat(gaugeValue).isGreaterThanOrEqualTo(2.0);
            assertThat(registry.getActiveConnectionCount()).isGreaterThanOrEqualTo(2);
        } finally {
            registry.complete(990001L);
            registry.complete(990002L);
        }
    }

    @Test
    void healthEndpointIsAnonymousOverRealHttpAndReturnsStatusField() throws Exception {
        HttpResponse<String> response = get("/actuator/health", null);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"status\"");
    }

    @Test
    void unexposedActuatorEndpointsAre401AnonymousAndNeverServedWithValidToken() throws Exception {
        for (String path : List.of("/actuator/env", "/actuator/beans", "/actuator/configprops",
                "/actuator/heapdump", "/actuator/threaddump")) {
            // 匿名：被 SecurityConfig 的 anyRequest().authenticated() 拦成 401（真实行为，
            // 原 MOCK 版误写 404 导致失败）
            assertThat(get(path, null).statusCode())
                    .as("匿名 %s 应被鉴权拦成 401", path).isEqualTo(401);

            // 带合法 JWE：通过鉴权后仍然拿不到端点 —— exposure 白名单只有 health,prometheus。
            // 真实 servlet 环境下未映射路径落到静态资源兜底：NoResourceFoundException 被
            // GlobalExceptionHandler catch-all 接住即 500+INTERNAL_ERROR（组长在 MOCK 环境
            // 确诊的同一机制），走容器默认错误处理即 404。两种都接受，但绝不接受 200，
            // 且响应体绝不许携带任何端点数据（证明力度不降：端点确实未暴露，而非仅被鉴权挡住）。
            HttpResponse<String> authenticated = get(path, userToken);
            assertThat(authenticated.statusCode())
                    .as("带票 %s 应因未暴露而 404/500，绝不 200", path)
                    .isIn(404, 500);
            if (authenticated.statusCode() == 500) {
                assertThat(authenticated.body())
                        .as("500 必须是 GlobalExceptionHandler 的兜底 JSON，而非端点数据")
                        .contains("INTERNAL_ERROR");
            }
            assertThat(authenticated.body())
                    .as("带票 %s 响应体绝不许泄露端点数据", path)
                    .doesNotContain("propertySources")
                    .doesNotContain("spring.datasource")
                    .doesNotContain("activeProfiles");
        }
    }

    @Test
    void businessApiStillRequiresAuthenticationOverRealHttp() throws Exception {
        HttpResponse<String> response = get("/api/documents", null);

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body()).contains("\"code\":\"UNAUTHORIZED\"");
    }

    @Test
    void actuatorDiscoveryPageIs401AnonymousAndNeverListsUnexposedEndpoints() throws Exception {
        // 匿名访问发现页同样被鉴权拦截
        assertThat(get("/actuator", null).statusCode()).isEqualTo(401);

        // 带合法 JWE：发现页不在 exposure 白名单里，按真实行为宽断言——200（链接页）、
        // 404（未暴露）或 500（落到静态资源兜底被 GlobalExceptionHandler 接住，与未暴露
        // 端点同一机制）都接受；但 200 时链接页只许含 health/prometheus，且任何状态下
        // 绝不允许把未暴露端点列出来 —— 那等于告诉攻击者 env/heapdump 等敏感端点存在。
        HttpResponse<String> response = get("/actuator", userToken);
        int status = response.statusCode();
        assertThat(status).isIn(200, 404, 500);
        String body = response.body();
        if (status == 200) {
            assertThat(body).contains("/actuator/health").contains("/actuator/prometheus");
        }
        assertThat(body).doesNotContain("/actuator/env")
                .doesNotContain("/actuator/beans")
                .doesNotContain("/actuator/configprops")
                .doesNotContain("/actuator/heapdump")
                .doesNotContain("/actuator/threaddump");
    }

    /** 经上下文内真实 Bean 各记一次样本，让懒创建的 ①②③④ 四组序列出现在抓取结果里。 */
    private static void recordOneSamplePerLazyGroup() {
        MqTaskMetrics mqTaskMetrics = context.getBean(MqTaskMetrics.class);
        BusinessMetrics businessMetrics = context.getBean(BusinessMetrics.class);

        long startedNanos = mqTaskMetrics.startTimer();
        mqTaskMetrics.recordEnqueued(TaskType.PDF_IMPORT);
        mqTaskMetrics.recordConsumed(TaskType.PDF_IMPORT,
                MqTaskMetrics.ConsumeOutcome.SUCCESS, startedNanos);
        mqTaskMetrics.recordDeadLettered(TaskType.MINDMAP_SYNC,
                MqTaskMetrics.DeadLetterReason.RETRIES_EXHAUSTED, mqTaskMetrics.startTimer());

        businessMetrics.recordPdfUploadAccepted(businessMetrics.startTimer());
        businessMetrics.recordSyncJobProcess("ready", businessMetrics.startTimer());
        businessMetrics.recordAiCallSuccess(BusinessMetrics.AI_OP_JSON, "test-model",
                businessMetrics.startTimer());
        businessMetrics.recordAiCallFailure(BusinessMetrics.AI_OP_CHAT, "test-model",
                "AI_TIMEOUT", businessMetrics.startTimer());
        businessMetrics.recordEmbeddingSuccess(BusinessMetrics.EMBEDDING_OP_INDEX,
                "text-embedding-v3", businessMetrics.startTimer());
        businessMetrics.recordEmbeddingFailure(BusinessMetrics.EMBEDDING_OP_SEARCH,
                "text-embedding-v3", businessMetrics.startTimer());
    }

    /** 对 http://127.0.0.1:{@value #PORT} 发真实 GET；token 为 null 时匿名。 */
    private static HttpResponse<String> get(String path, String token) throws Exception {
        HttpRequest.Builder builder = HttpRequest
                .newBuilder(URI.create("http://127.0.0.1:" + PORT + path))
                .timeout(Duration.ofSeconds(30))
                .GET();
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return HTTP_CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** 从 Prometheus 抓取文本解析无 tag 的 Gauge 当前值（跳过 # HELP / # TYPE 注释行）。 */
    private static Double parseGaugeValue(String body, String metricName) {
        for (String line : body.split("\n")) {
            if (line.startsWith("#") || !line.startsWith(metricName)) {
                continue;
            }
            String[] parts = line.trim().split("\\s+");
            if (parts.length == 2) {
                return Double.parseDouble(parts[1]);
            }
        }
        return null;
    }
}
