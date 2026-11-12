package com.example.aimindmap.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.aimindmap.mqsupport.TaskType;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.autoconfigure.metrics.MetricsAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.metrics.export.prometheus.PrometheusMetricsExportAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.origin.OriginTrackedValue;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * 阶段八-1（10.1 的 A 方案，组长 2026-10-22 选定）：验证 {@code application.yml} 里的
 * {@code management.metrics.distribution.percentiles-histogram."[aimindmap]": true}
 * 真的让全部 {@code aimindmap.*} 耗时 Timer 发布<b>服务端分位直方图</b>，
 * 也就是阶段八-2 用 {@code histogram_quantile(0.95, ...)} 画 P95 的前提成立
 * （方案 326 行 P95 是硬交付；328 行双实例下只有服务端直方图可跨实例聚合）。
 *
 * <p><b>为什么这个测试能在本机沙盒跑：</b>用 {@link ApplicationContextRunner} 只装配
 * metrics + prometheus 两个自动配置（非 Web 上下文、不创建 {@code RestClient}），
 * 因此不触碰「JDK 回环通道被禁」这条既有环境限制。
 *
 * <p><b>为什么直接加载仓库里真实的 application.yml：</b>断言链是
 * 「yml 写法 → 绑定后的 map 键 → Spring Boot {@code PropertiesMeterFilter} 生效 →
 * scrape 出 {@code _bucket} + {@code le} + {@code # TYPE ... histogram}」，
 * 任何一环被人改错（尤其是把方括号去掉、或改回 {@code * } 通配写法）都会立刻失败。
 *
 * <p><b>安全：</b>本类断言只对 {@code management.*} 前缀的键做集合断言，
 * 绝不对整份 yml 扁平 map 做断言 —— 那样失败信息会把 {@code app.ai.api-key}
 * 的默认值打进 surefire 报告（红线：日志/汇报禁明文 Key）。
 */
class HistogramDistributionWiringTest {

    /** yml 里 {@code "[aimindmap]": true} 经 YAML 扁平化后的属性键（方括号写法保留原键）。 */
    private static final String YAML_KEY =
            "management.metrics.distribution.percentiles-histogram[aimindmap]";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(MetricsAutoConfiguration.class,
                    PrometheusMetricsExportAutoConfiguration.class));

    @Test
    void applicationYamlDeclaresAimindmapPrefixPercentilesHistogramKey() throws Exception {
        Map<String, Object> managementKeys = managementKeysFromRealApplicationYaml();

        // 只断言 management.* 子集：失败信息里不会出现 app.* 的任何值（含 API Key 默认值）
        assertThat(managementKeys).containsEntry(YAML_KEY, "true");
        assertThat(managementKeys).containsEntry(
                "management.endpoints.web.exposure.include[0]", "health");
        assertThat(managementKeys).containsEntry(
                "management.endpoints.web.exposure.include[1]", "prometheus");
        // 除 exposure 白名单与 percentiles-histogram 开关外，management 块下没有任何其它键
        // （对应「yml 只许新增 management 块、不新增其它键」的红线）
        assertThat(managementKeys.keySet()).containsExactlyInAnyOrder(
                "management.endpoints.web.exposure.include[0]",
                "management.endpoints.web.exposure.include[1]",
                YAML_KEY);
    }

    @Test
    void realApplicationYamlEnablesHistogramBucketsForAllAimindmapTimers() throws Exception {
        withRealApplicationYaml().run(context -> {
            PrometheusMeterRegistry registry = context.getBean(PrometheusMeterRegistry.class);
            BusinessMetrics businessMetrics = new BusinessMetrics(registry);
            MqTaskMetrics mqTaskMetrics = new MqTaskMetrics(registry);

            businessMetrics.recordSyncJobProcess("ready", businessMetrics.startTimer());
            businessMetrics.recordPdfUploadAccepted(businessMetrics.startTimer());
            businessMetrics.recordAiCallSuccess(BusinessMetrics.AI_OP_JSON, "test-model",
                    businessMetrics.startTimer());
            businessMetrics.recordEmbeddingSuccess(BusinessMetrics.EMBEDDING_OP_INDEX,
                    "text-embedding-v3", businessMetrics.startTimer());
            mqTaskMetrics.recordConsumed(TaskType.PDF_IMPORT,
                    MqTaskMetrics.ConsumeOutcome.SUCCESS, mqTaskMetrics.startTimer());

            String scraped = registry.scrape();

            // 5 个耗时 Timer 全部发布直方图（8-2 的 P95 面板逐条可用）
            assertThat(scraped)
                    .contains("aimindmap_sync_job_seconds_bucket")
                    .contains("aimindmap_pdf_upload_seconds_bucket")
                    .contains("aimindmap_ai_call_seconds_bucket")
                    .contains("aimindmap_embedding_call_seconds_bucket")
                    .contains("aimindmap_task_consume_seconds_bucket");
        });
    }

    /**
     * {@code histogram_quantile} 的前提逐条核对：同一个 Timer 必须同时有带 {@code le} 标签的
     * {@code _bucket} 序列（含 {@code le="+Inf"}）、{@code _count}、{@code _sum}，
     * 且 {@code # TYPE} 是 histogram 而不是 summary。
     */
    @Test
    void histogramQuantilePremiseHoldsBucketLeCountSumAllPresent() throws Exception {
        withRealApplicationYaml().run(context -> {
            PrometheusMeterRegistry registry = context.getBean(PrometheusMeterRegistry.class);
            BusinessMetrics businessMetrics = new BusinessMetrics(registry);

            businessMetrics.recordSyncJobProcess("ready", businessMetrics.startTimer());

            String scraped = registry.scrape();

            assertThat(scraped).contains("# TYPE aimindmap_sync_job_seconds histogram");
            assertThat(scraped).contains("aimindmap_sync_job_seconds_bucket{outcome=\"ready\",le=\"");
            assertThat(scraped).contains("aimindmap_sync_job_seconds_bucket{outcome=\"ready\",le=\"+Inf\"}");
            assertThat(scraped).contains("aimindmap_sync_job_seconds_count{outcome=\"ready\"}");
            assertThat(scraped).contains("aimindmap_sync_job_seconds_sum{outcome=\"ready\"}");
            // 桶数量足够算 P95（micrometer 默认预置一组 SLA 边界，远多于 1 个桶）
            assertThat(countLines(scraped, "aimindmap_sync_job_seconds_bucket{outcome=\"ready\",le="))
                    .isGreaterThan(10L);
        });
    }

    /** 前缀开关只影响 Timer：同前缀的 Counter / Gauge 不应产生任何 bucket 序列。 */
    @Test
    void countersAndGaugesUnderSamePrefixGetNoBuckets() throws Exception {
        withRealApplicationYaml().run(context -> {
            PrometheusMeterRegistry registry = context.getBean(PrometheusMeterRegistry.class);
            MqTaskMetrics mqTaskMetrics = new MqTaskMetrics(registry);

            mqTaskMetrics.recordEnqueued(TaskType.PDF_IMPORT);
            mqTaskMetrics.recordDeadLettered(TaskType.PDF_IMPORT,
                    MqTaskMetrics.DeadLetterReason.POISON_MESSAGE, mqTaskMetrics.startTimer());

            String scraped = registry.scrape();

            assertThat(scraped).contains("aimindmap_task_enqueued_total");
            assertThat(scraped).doesNotContain("aimindmap_task_enqueued_bucket");
            assertThat(scraped).doesNotContain("aimindmap_task_dead_letter_bucket");
        });
    }

    /** 前缀只作用于 aimindmap：其它 meter 不被顺带打开，避免序列无谓膨胀。 */
    @Test
    void otherPrefixesAreNotAffected() throws Exception {
        withRealApplicationYaml().run(context -> {
            PrometheusMeterRegistry registry = context.getBean(PrometheusMeterRegistry.class);

            registry.timer("other.vendor.call").record(Duration.ofMillis(5));

            String scraped = registry.scrape();

            assertThat(scraped).contains("other_vendor_call_seconds_count");
            assertThat(scraped).doesNotContain("other_vendor_call_seconds_bucket");
        });
    }

    /** 反向对照：去掉这个键时 Timer 退回 summary（只有 count/sum/max），证明开关确实在起作用。 */
    @Test
    void withoutThePropertyTimersPublishNoBuckets() {
        runner.run(context -> {
            PrometheusMeterRegistry registry = context.getBean(PrometheusMeterRegistry.class);
            BusinessMetrics businessMetrics = new BusinessMetrics(registry);

            businessMetrics.recordSyncJobProcess("ready", businessMetrics.startTimer());

            String scraped = registry.scrape();

            assertThat(scraped).contains("# TYPE aimindmap_sync_job_seconds summary");
            assertThat(scraped).contains("aimindmap_sync_job_seconds_count");
            assertThat(scraped).doesNotContain("aimindmap_sync_job_seconds_bucket");
        });
    }

    /**
     * 回归护栏：Spring Boot 3.4 的 {@code PropertiesMeterFilter} 只支持「精确名 / 逐级前缀」，
     * <b>不支持 {@code *} 通配</b>。当初按直觉写的 {@code "[aimindmap.*]"} 与 {@code "[*]"}
     * 实测都不生效（Timer 仍是 summary），因此 yml 里用的是前缀写法。
     * 谁要是把它改回通配写法，本用例会失败并说明原因。
     */
    @Test
    void starWildcardFormIsNotSupportedBySpringBoot34() {
        for (String unsupported : List.of(
                "management.metrics.distribution.percentiles-histogram[aimindmap.*]=true",
                "management.metrics.distribution.percentiles-histogram[*]=true")) {
            runner.withPropertyValues(unsupported).run(context -> {
                PrometheusMeterRegistry registry = context.getBean(PrometheusMeterRegistry.class);
                BusinessMetrics businessMetrics = new BusinessMetrics(registry);

                businessMetrics.recordSyncJobProcess("ready", businessMetrics.startTimer());

                assertThat(registry.scrape())
                        .as("通配写法 %s 不生效，必须用前缀 [aimindmap] 或精确名", unsupported)
                        .doesNotContain("aimindmap_sync_job_seconds_bucket");
            });
        }
    }

    /** 把仓库里真实的 application.yml 作为属性源喂给最小上下文（不启 Web、不建数据源）。 */
    private ApplicationContextRunner withRealApplicationYaml() throws Exception {
        // 先在 lambda 外加载（loadApplicationYaml 声明 checked 异常，不能写在 initializer lambda 里）
        List<PropertySource<?>> sources = loadApplicationYaml();
        return runner.withInitializer(context -> sources.forEach(
                source -> context.getEnvironment().getPropertySources().addLast(source)));
    }

    private static List<PropertySource<?>> loadApplicationYaml() throws Exception {
        return new YamlPropertySourceLoader()
                .load("applicationYaml", new ClassPathResource("application.yml"));
    }

    /** 只挑出 management.* 的键并把值转成字符串（避开 OriginTrackedValue 的 equals 语义与敏感值）。 */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> managementKeysFromRealApplicationYaml() throws Exception {
        Map<String, Object> managementKeys = new LinkedHashMap<>();
        for (PropertySource<?> source : loadApplicationYaml()) {
            ((Map<String, Object>) source.getSource()).forEach((key, value) -> {
                if (key.startsWith("management.")) {
                    managementKeys.put(key, valueAsString(value));
                }
            });
        }
        return managementKeys;
    }

    /** YAML 加载出的值是 OriginTrackedValue（equals 不与裸值互通），统一解包成字符串再断言。 */
    private static String valueAsString(Object value) {
        if (value instanceof OriginTrackedValue tracked) {
            return String.valueOf(tracked.getValue());
        }
        return String.valueOf(value);
    }

    private static long countLines(String text, String prefix) {
        long count = 0;
        for (String line : text.split("\n")) {
            if (line.startsWith(prefix)) {
                count++;
            }
        }
        return count;
    }
}
