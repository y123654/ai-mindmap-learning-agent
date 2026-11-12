package com.example.aimindmap.integration;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.example.aimindmap.AiMindMapApplication;
import com.example.aimindmap.mqsupport.MqTopology;
import com.example.aimindmap.mqsupport.TaskType;
import com.example.aimindmap.vectorindex.VectorResourceType;
import com.example.aimindmap.vectorindex.VectorIndexTaskService;
import com.example.aimindmap.vectorindex.VectorTaskOperation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.Mockito;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Properties;

/**
 * 阶段四 4-4 MQ 集成测试公共基类：
 * <ul>
 *   <li>Docker 门控 + 全 IT 共享一个 Testcontainers RabbitMQ；</li>
 *   <li>@SpringBootTest 子类通过 @DynamicPropertySource 指向该 broker，PG 仍走本机 5432；</li>
 *   <li>每轮开始前 purge 全部 aimindmap.* 队列，每轮结束清理 888 前缀测试数据；</li>
 *   <li>手工多上下文（H1-2/H1-5）共用同一套 broker/PG/mock 装配。</li>
 * </ul>
 */
@EnabledIfDockerAvailable
abstract class AbstractMqIntegrationTest {

    /** 本批 IT 专用资源前缀：resource_id 全部以 888 开头，清理严格限定。 */
    static final String RESOURCE_PREFIX = "888";

    /** 固定 1024 维测试向量（与 app.qdrant.vector-dimension 一致）。 */
    static final float[] TEST_VECTOR = new float[1024];

    /**
     * IT 专用 PostgreSQL schema（仍是本机 5432 的同一个 aimindmap 库）：
     * 全量 mvn test 时，既有 @SpringBootTest 全应用上下文（如 ExternalIdEndToEndWiringTest）
     * 会以缓存方式存活，其 OutboxRelay/消费者与本 IT 共享 public schema 的 outbox/task 表，
     * 会跨上下文抢投同一批 outbox 行造成干扰。IT 全部落到独立 schema，Flyway 在其中全量建表，
     * ddl-auto=validate 同样校验该 schema，与外部上下文完全隔离。
     */
    static final String IT_SCHEMA = "it888";

    private static JdbcTemplate cleanupJdbc;

    @BeforeAll
    static void startBroker() {
        MqTestBroker.start();
    }

    @org.springframework.test.context.DynamicPropertySource
    static void registerBroker(DynamicPropertyRegistry registry) {
        registry.add("spring.rabbitmq.host", MqTestBroker::host);
        registry.add("spring.rabbitmq.port", MqTestBroker::amqpPort);
        registry.add("spring.rabbitmq.username", () -> "guest");
        registry.add("spring.rabbitmq.password", () -> "guest");
        registry.add("spring.rabbitmq.virtual-host", () -> "/");
        registry.add("spring.datasource.url", AbstractMqIntegrationTest::itJdbcUrl);
        registry.add("spring.flyway.schemas", () -> IT_SCHEMA);
        registry.add("spring.flyway.default-schema", () -> IT_SCHEMA);
    }

    @BeforeEach
    void resetMocksAndPurgeQueues() {
        // QDRANT_CLIENT 的启动 stub 不能 reset（上下文随时可能重启）；只重置业务计数 mock
        Mockito.reset(MqIntegrationMocks.GATEWAY, MqIntegrationMocks.EMBEDDING_MODEL,
                MqIntegrationMocks.SYNC_ENGINE, MqIntegrationMocks.AI_GATEWAY,
                MqIntegrationMocks.CHAT_MODEL);
        when(MqIntegrationMocks.EMBEDDING_MODEL.embed(anyString())).thenReturn(TEST_VECTOR);
        for (String queue : allAimindmapQueues()) {
            MqTestBroker.purgeQuietly(queue);
        }
    }

    @AfterEach
    void cleanupDatabase() {
        JdbcTemplate jdbc = cleanupJdbc();
        // 顺序：先依赖 vector_index_tasks 的表，再任务行，最后业务夹具
        jdbc.update("DELETE FROM outbox_events WHERE task_id IN ("
                + "SELECT id FROM vector_index_tasks WHERE resource_id LIKE '888%')");
        jdbc.update("DELETE FROM idempotent_consumptions WHERE message_id IN ("
                + "SELECT 'VECTOR_UPSERT:' || id FROM vector_index_tasks WHERE resource_id LIKE '888%' "
                + "UNION SELECT 'VECTOR_DELETE:' || id FROM vector_index_tasks WHERE resource_id LIKE '888%')");
        jdbc.update("DELETE FROM vector_index_entries WHERE resource_id LIKE '888%'");
        jdbc.update("DELETE FROM vector_index_tasks WHERE resource_id LIKE '888%'");
        jdbc.update("DELETE FROM documents WHERE id >= 888000 AND id < 889000");
        jdbc.update("DELETE FROM users WHERE id >= 888000 AND id < 889000");
    }

    /** 所有工作队列 + 18 重试队列 + DLQ（拓扑常量派生，不手写名字）。 */
    static List<String> allAimindmapQueues() {
        List<String> queues = new ArrayList<>();
        for (TaskType type : TaskType.values()) {
            queues.add(MqTopology.queueName(type));
            for (MqTopology.RetryTier tier : MqTopology.RetryTier.values()) {
                queues.add(MqTopology.retryQueueName(type, tier));
            }
        }
        queues.add(MqTopology.DEAD_LETTER_QUEUE);
        return queues;
    }

    /**
     * 在 TransactionTemplate 内反射调用私有 addTask（CGLIB 代理先解包），
     * 返回新建任务行的 id。outbox 同事务写入逻辑由此被真实覆盖。
     */
    static long invokeAddTask(VectorIndexTaskService service,
                              org.springframework.transaction.PlatformTransactionManager txManager,
                              Long userId, VectorResourceType resourceType, String resourceId,
                              int chunkIndex, VectorTaskOperation operation) throws Exception {
        VectorIndexTaskService target = AopTestUtils.getTargetObject(service);
        Method addTask = VectorIndexTaskService.class.getDeclaredMethod(
                "addTask", Long.class, VectorResourceType.class, String.class,
                int.class, VectorTaskOperation.class);
        addTask.setAccessible(true);
        new TransactionTemplate(txManager).executeWithoutResult(status -> {
            try {
                addTask.invoke(target, userId, resourceType, resourceId, chunkIndex, operation);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        });
        Long id = cleanupJdbc().queryForObject(
                "SELECT id FROM vector_index_tasks WHERE resource_id = ? AND chunk_index = ? "
                        + "AND operation = ? ORDER BY id DESC LIMIT 1",
                Long.class, resourceId, chunkIndex, operation.name());
        if (id == null) {
            throw new IllegalStateException("addTask 后未找到任务行 resourceId=" + resourceId);
        }
        return id;
    }

    /**
     * 手工启动一个完整应用上下文（H1-2/H1-5）：含 mock 配置，连同一 broker 与本机 PG。
     * 应用的 SecurityConfig 依赖 servlet 容器，故统一用 SERVLET 环境；serverPort=0 随机端口，
     * 8081/8082 用于 H1-5 双实例。覆盖项以命令行参数传入（优先级高于 application.yml；
     * 注意 SpringApplicationBuilder.properties(...) 只是最低优先级默认属性，不能用于覆盖）。
     */
    static ConfigurableApplicationContext startContext(int serverPort, String... extraArgs) {
        List<String> args = new ArrayList<>(List.of(
                "--spring.rabbitmq.host=" + MqTestBroker.host(),
                "--spring.rabbitmq.port=" + MqTestBroker.amqpPort(),
                "--spring.rabbitmq.username=guest",
                "--spring.rabbitmq.password=guest",
                "--spring.rabbitmq.virtual-host=/",
                "--spring.datasource.url=" + itJdbcUrl(),
                "--spring.flyway.schemas=" + IT_SCHEMA,
                "--spring.flyway.default-schema=" + IT_SCHEMA,
                "--server.port=" + serverPort));
        args.addAll(List.of(extraArgs));
        ConfigurableApplicationContext context = new SpringApplicationBuilder(AiMindMapApplication.class, MqIntegrationMocks.class)
                .web(WebApplicationType.SERVLET)
                .run(args.toArray(String[]::new));
        return context;
    }

    static JdbcTemplate cleanupJdbc() {
        if (cleanupJdbc == null) {
            // 凭据复用 application.yml 同一套 ${DB_*} 占位解析（环境变量优先），
            // 测试代码不另存任何明文口令。
            // 2026-10-25 密钥整改：源码已去掉明文默认值，真实值只在项目密钥文件
            // config/application.yml（surefire 工作目录为 mindmap-backend，相对路径可取到）；
            // 放在 classpath 之后，同一属性以 config 的具体值为准。
            YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
            yaml.setResources(new ClassPathResource("application.yml"),
                    new FileSystemResource("config/application.yml"));
            Properties ymlProperties = yaml.getObject();
            ConfigurableEnvironment env = new StandardEnvironment();
            env.getPropertySources().addLast(
                    new PropertiesPropertySource("appYml", ymlProperties));
            String username = env.getProperty("spring.datasource.username");
            String password = env.getProperty("spring.datasource.password");
            DriverManagerDataSource dataSource =
                    new DriverManagerDataSource(itJdbcUrl(), username, password);
            cleanupJdbc = new JdbcTemplate(dataSource);
        }
        return cleanupJdbc;
    }

    /** IT 数据源 URL：复用 DB_URL 指向的同一个库，但 search_path 固定到 IT 专用 schema。 */
    static String itJdbcUrl() {
        String baseUrl = System.getenv().getOrDefault(
                "DB_URL", "jdbc:postgresql://localhost:5432/aimindmap");
        int query = baseUrl.indexOf('?');
        if (query >= 0) {
            baseUrl = baseUrl.substring(0, query);
        }
        return baseUrl + "?currentSchema=" + IT_SCHEMA;
    }
}
