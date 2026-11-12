package com.example.aimindmap.integration;

import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;

/**
 * 测试环境默认装配（仅 src/test 生效，不进入打包应用）：
 *
 * <p>阶段七-1 引入 Redis Pub/Sub 订阅型 Bean 后，任何全量 {@code @SpringBootTest} 或手工多上下文
 * 启动都会真实连接 Redis。为保证测试绝不触碰开发机 Redis（127.0.0.1:6379），此处把
 * {@code app.cache.redis-enabled} 默认压成 {@code false}（InMemory 降级实现）。
 *
 * <p><b>阶段七-3 追加：把三个限流阈值默认压成 0（= 该作用域不限流）。</b>
 * 原因是限流拦截器挂在全局 MVC 拦截器链上，既有集成测试常在短时间内对同一用户多次调用受限接口
 * （例如 PDF 多次上传、同步任务反复触发），若按生产默认阈值（AI 20 / PDF 5 / 同步 10 每分钟）
 * 会被误杀成 429。阈值 &le; 0 即不限流是生产同样成立的语义（也是「一键关闭限流」的落点），
 * 所以测试默认关闭、限流专属测试用命令行参数或 {@code @TestPropertySource} 显式设回真阈值即可。
 *
 * <p>优先级：紧跟在命令行参数之后（无命令行时 addFirst），因此高于 application.yml 占位默认值、
 * 系统环境变量；需要真实 Redis 的跨实例 SSE 集成测试用命令行参数
 * {@code --app.cache.redis-enabled=true} 配合 Testcontainers 一次性 Redis 覆盖，
 * 需要真阈值的限流测试用 {@code --app.ratelimit.*=N} 覆盖。
 * SpringBootTest 的 @DynamicPropertySource 在更晚阶段 addFirst，同样高于本默认值。
 *
 * <p>不改变生产语义：{@code app.cache.redis-enabled} 在 application.yml 中仍默认 true、
 * 三个限流阈值仍默认 20/5/10；关闭时 SSE 内存直推、限流走单机内存窗口的降级语义与方案 §3.5 完全一致。
 */
public class TestRedisDisableEnvironmentPostProcessor implements EnvironmentPostProcessor {

    static final String PROPERTY_SOURCE_NAME = "testRedisDefaultDisabled";
    private static final String COMMAND_LINE_SOURCE_NAME = "commandLineArgs";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment,
                                       SpringApplication application) {
        PropertySource<?> propertySource = new MapPropertySource(PROPERTY_SOURCE_NAME, Map.of(
                "app.cache.redis-enabled", "false",
                "app.ratelimit.ai-per-min", "0",
                "app.ratelimit.pdf-per-min", "0",
                "app.ratelimit.sync-per-min", "0"));
        MutablePropertySources sources = environment.getPropertySources();
        if (sources.contains(COMMAND_LINE_SOURCE_NAME)) {
            sources.addAfter(COMMAND_LINE_SOURCE_NAME, propertySource);
        } else {
            sources.addFirst(propertySource);
        }
    }
}