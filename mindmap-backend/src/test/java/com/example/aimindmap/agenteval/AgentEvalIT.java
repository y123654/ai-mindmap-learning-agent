package com.example.aimindmap.agenteval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

import com.example.aimindmap.ai.AiProperties;
import com.example.aimindmap.assistant.KnowledgeAssistantService;
import com.example.aimindmap.assistant.KnowledgeAssistantStreamer;
import com.example.aimindmap.auth.ExternalIdCodec;
import com.example.aimindmap.importpdf.PdfImportWorker;
import com.example.aimindmap.vectorindex.QdrantProperties;
import com.example.aimindmap.vectorindex.QdrantVectorGateway;
import com.example.aimindmap.vectorindex.VectorIndexWorker;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.qdrant.client.QdrantClient;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 50 条 Agent 评测基线（方案 §4 P0-4 后半，任务 P0-A2）：<b>P0-1（提示词）/ P0-3（证据协议）之前</b>
 * 跑出的第一份真实基线，之后每次 prompt/模型/检索参数变更都用同一套用例重跑对比。
 *
 * <p>运行方式（默认门禁不含本类，@Tag("eval") 被 surefire excludedGroups 排除）：
 * <pre>
 * mvn.cmd -f mindmap-backend/pom.xml -B -Peval test -Dtest=AgentEvalIT '-Djdk.net.unixdomain.tmpdir=D:\tmp'
 * </pre>
 *
 * <p><b>单实例互斥锁</b>：整个评测期间持有 PostgreSQL 会话级 advisory lock
 * （{@code pg_try_advisory_lock(88890020261026)}）。第二次并发评测算不到锁会在开跑前
 * {@code fail("已有评测在运行…")} 立即退出——两次评测会共用评测用户 888900，
 * 先结束的一方清理用户后，另一方就会报「账号不存在或登录已失效」（组长已踩过）。
 * 锁连接全程是同一个 {@link Connection}，{@code @AfterAll} 的 finally 里显式解锁。
 *
 * <p>隔离：测试属性把 {@code app.qdrant.collection} 覆盖为
 * {@link AgentEvalSeeder#EVAL_COLLECTION}；真实集合
 * {@link AgentEvalSeeder#REAL_COLLECTION} <b>只读 count 取证</b>，跑完断言点数不变。
 * 业务行与向量点全部按 {@link AgentEvalSeeder#EVAL_USER_ID} 圈定删除并打印条数。
 *
 * <p>两个一次性探针（embedding / chat）先跑：任一失败立即中止，不把 50 条全跑完（省钱）。
 */
@Tag("eval")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(properties = {
        // 隔离硬约束：向量只进评测专用集合
        "app.qdrant.collection=" + AgentEvalSeeder.EVAL_COLLECTION,
        // 评测不测消息链路：关掉 MQ，走 §3.5 的降级路径，避免往真实 vhost 投递评测消息
        "app.mq.enabled=false",
        // 后台轮询周期拉到 1 天：本类只关心问答链路，避免任何后台 worker 认领真实任务
        "app.mq.requeue-scan-delay-ms=86400000",
        "app.qdrant.worker-fixed-delay-ms=86400000"
})
class AgentEvalIT {

    private static final Logger log = LoggerFactory.getLogger(AgentEvalIT.class);

    /** 报告名可覆盖（P0-B 复跑写 2026-10-26-after-p0b），默认仍是基线名，行为不变。 */
    private static final String REPORT_BASENAME =
            System.getProperty("eval.report.name", "2026-10-26-baseline");
    /** 单条用例上限：Agent 自身超时是 75s（app.agent.timeout-seconds），这里留 30s 余量兜底。 */
    private static final int CASE_TIMEOUT_SECONDS = 105;

    /**
     * P1-F 开工前必办④：三条路径的最坏耗时口径（出处
     * {@code docs/P1-D2b-总预算收口验收记录-20261027.md} 第三节）。
     *
     * <p>写进报告的唯一目的：<b>别把 80s 软上限当成 P95 预期</b>。P95 门槛仍是 ≤20s，
     * 必须由本次真实运行给出，不能拿软上限反推。
     */
    private static final String LATENCY_BUDGET_NOTE =
            "旧路径（runtime.enabled=false，单轮工具）最坏 ≤75s；B2 零调用补检最坏 ≤75s"
                    + "（P1-D2b 收口前是 ≈150s：两段各 75s）；C 计划路径常态 ≤75s、设计软上限 80s。"
                    + "**80s 是软上限，不是 P95 预期**；P95 门槛仍是 ≤20s（方案 P0-4），以本次实测为准。"
                    + "触发 80s 上界的唯一条件是预算外的收尾/落库/plan_json 写入（不计时），"
                    + "不是模型或检索变慢。出处：docs/P1-D2b-总预算收口验收记录-20261027.md 第三节。";

    /**
     * C 计划路径的<b>设计软上限</b>（秒）。这不是配置项，是 D2b 第三节写死的设计值：
     * Planner ≤20s + Executor ≤15s + 合成保底，加上预算外的落库开销才会摸到 80s。
     */
    private static final int PLAN_SOFT_CEILING_SECONDS = 80;

    /** 评测单实例互斥锁的固定 key（会话级 advisory lock；值本身没有业务含义）。 */
    private static final long EVAL_ADVISORY_LOCK_KEY = 888900_20261026L;

    /** 报告里的固定分类顺序（与 cases.yaml 的「每条第一个 tag」一致）。 */
    private static final List<String> CATEGORY_ORDER = List.of(
            "single-hop", "multi-hop", "mindmap", "question", "memory",
            "no-evidence", "chitchat", "injection");

    /** 用例集变更记录（报告里必须如实列出；空表示没有改动）。 */
    private static final List<String> CASE_CORRECTIONS = List.of(
            "10 条冒烟（smoke-001~010）整体并入 50 条基线：题目保留，按分类重新编号为 "
                    + "base-001/005/009/030/016/017/024/040/046/049；用例条数只增不减。",
            "base-016（原 smoke-005）key_points 由 [FCFS, SJF, 优点, 缺点] 校正为 "
                    + "[FCFS, SJF, 平均等待时间]：模型回答会逐条对比、不一定逐字写「优点」；"
                    + "key_points 只进辅助覆盖率、不参与 PASS/FAIL，不影响任何验收数字。",
            "原 smoke-010 并入 base-049，期望值不变。v3 已排除「没有写/缺失」句式，但本次 base-049 "
                    + "回答改用「没有具体代码」，仍被误判——按冻结规则不改识别器，只作为识别器局限记录"
                    + "（见报告第 7 节），不是改用例期望凑绿。");

    @Autowired private KnowledgeAssistantService assistantService;
    @Autowired private KnowledgeAssistantStreamer streamer;
    @Autowired private ExternalIdCodec idCodec;
    @Autowired private EmbeddingModel embeddingModel;
    @Autowired private ChatModel chatModel;
    @Autowired private AiProperties aiProperties;
    @Autowired private QdrantProperties qdrantProperties;
    @Autowired private QdrantVectorGateway gateway;
    @Autowired private QdrantClient qdrantClient;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper objectMapper;
    /** P1-F ③：开关快照从 Environment 读（值可能被测试属性/环境变量覆盖，不能写死默认值）。 */
    @Autowired private Environment environment;

    /**
     * 两个后台轮询 worker 换成 mock：app.mq.enabled=false 时它们会"直接认领并处理"
     * 数据库里的真实任务，而向量写入集合已被覆盖成评测集合——不 mock 就有把真实用户的
     * 向量写进评测集合（以及把真实任务误标成功）的风险。
     */
    @MockBean private VectorIndexWorker vectorIndexWorker;
    @MockBean private PdfImportWorker pdfImportWorker;

    private static AgentEvalSeeder seeder;
    private static long realCollectionBefore = -1L;
    private static long evalCollectionBaseline = -1L;

    // 单实例互斥锁：整个评测期间持有同一个 Connection 的会话级 advisory lock。
    private static Connection lockConnection;
    private static boolean lockAcquired;
    private static boolean lockProbeBlocked;
    private static Integer lockBackendPid;
    private static String lockAcquiredAt = "";

    // 报告在 @AfterAll 里落盘：那时才拿到「清理后」的真实集合/评测集合点数，
    // 报告才能把"跑前=跑后"和"删了多少条"写成硬证据，而不是"见日志"。
    private static AgentEvalCases.AgentEvalDataset datasetRef;
    private static List<AgentEvalRunner.CaseResult> resultsRef;
    private static EvalMetrics.Metrics metricsRef;
    private static long evalPointsAfterSeed = -1L;
    private static int stalePointsPurged = 0;

    /**
     * 开跑前拿单实例互斥锁：拿不到立即失败（同一集群上已经有评测在跑）。
     *
     * <p>为什么必须是会话级而不是事务级：评测长达 8~15 分钟，事务锁会随事务结束提前释放；
     * 这里用 {@code pg_try_advisory_lock}（session 级），锁连接全程不还池、不关闭，
     * 直到 {@code @AfterAll} 的 finally 里显式解锁。
     */
    @BeforeAll
    void acquireEvalLock() throws SQLException {
        DataSource dataSource = jdbc.getDataSource();
        if (dataSource == null) {
            log.error("互斥锁获取失败：JdbcTemplate 没有可用的 DataSource");
            fail("无法获取 DataSource，拒绝启动评测");
            return;
        }
        lockConnection = dataSource.getConnection();
        try (PreparedStatement statement =
                     lockConnection.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
            statement.setLong(1, EVAL_ADVISORY_LOCK_KEY);
            try (ResultSet resultSet = statement.executeQuery()) {
                lockAcquired = resultSet.next() && resultSet.getBoolean(1);
            }
        }
        if (!lockAcquired) {
            log.error("互斥锁获取失败 key={}：已有评测在运行，本次拒绝启动（不落夹具、不调模型）",
                    EVAL_ADVISORY_LOCK_KEY);
            closeLockConnection();
            fail("已有评测在运行（PostgreSQL advisory lock key=" + EVAL_ADVISORY_LOCK_KEY
                    + " 被占用），本次拒绝启动");
        }
        lockBackendPid = queryInt(lockConnection, "SELECT pg_backend_pid()");
        // 第二个会话再试一把：必须拿不到（false），否则说明锁没生效
        lockProbeBlocked = !tryAdvisoryLockOnFreshConnection(dataSource);
        lockAcquiredAt = OffsetDateTime.now().toString();
        log.info("互斥锁获取成功 key={} backendPid={} acquiredAt={} 第二会话试探被拒={}",
                EVAL_ADVISORY_LOCK_KEY, lockBackendPid, lockAcquiredAt, lockProbeBlocked);
        assertThat(lockProbeBlocked)
                .as("会话级 advisory lock 必须能阻止第二个会话拿到同一把锁")
                .isTrue();
    }

    @Test
    void baseline50() throws Exception {
        AgentEvalCases.AgentEvalDataset dataset = AgentEvalCases.load();
        assertThat(dataset.cases())
                .as("P0-A2 基线必须是 50 条（方案 §4 P0-4 分布：15/8/6/5/5/6/3/2）")
                .hasSize(50);
        assertDistribution(dataset);
        datasetRef = dataset;
        modelName = aiProperties.getModel();
        embeddingModelName = qdrantProperties.getEmbeddingModel();
        evalCollectionName = qdrantProperties.getCollection();
        objectMapperRef = objectMapper;
        flywayVersion = queryLatestFlywayVersion();
        // P1-F ③：先抓开关快照，再做任何调用——报告里的指标必须能对应到这一组开关值。
        configSnapshot = EvalConfigSnapshot.capture(environment);
        log.info("==== 本次评测开关快照 ==== {}", EvalConfigSnapshot.oneLine(configSnapshot));

        seeder = new AgentEvalSeeder(jdbc, embeddingModel, gateway, qdrantProperties,
                qdrantClient, objectMapper);

        // ---- 0. 隔离前置检查 ----
        assertThat(qdrantProperties.getCollection())
                .as("测试属性必须把向量集合覆盖为评测专用集合")
                .isEqualTo(AgentEvalSeeder.EVAL_COLLECTION);
        assertThat(AgentEvalSeeder.EVAL_COLLECTION)
                .as("评测集合不得与真实集合同名")
                .isNotEqualTo(AgentEvalSeeder.REAL_COLLECTION);
        boolean evalCollectionExists = seeder.collectionExists(AgentEvalSeeder.EVAL_COLLECTION);
        assertThat(evalCollectionExists)
                .as("专用集合 %s 必须存在（由 QdrantCollectionInitializer 按测试属性创建）",
                        AgentEvalSeeder.EVAL_COLLECTION)
                .isTrue();

        realCollectionBefore = seeder.countPoints(AgentEvalSeeder.REAL_COLLECTION);
        stalePointsPurged = seeder.purgeVectorPoints();
        evalCollectionBaseline = seeder.countPoints(AgentEvalSeeder.EVAL_COLLECTION);
        log.info("==== 隔离基线 ==== 真实集合={} 点数={} | 评测集合={} 基线点数={} 清理残留点={}",
                AgentEvalSeeder.REAL_COLLECTION, realCollectionBefore,
                AgentEvalSeeder.EVAL_COLLECTION, evalCollectionBaseline, stalePointsPurged);

        // ---- 1. 一次性探针（失败立即停，不把 50 条全跑完） ----
        probeEmbedding();
        probeChat();

        // ---- 2. 落夹具 ----
        seeder.seed();
        evalPointsAfterSeed = seeder.countPoints(AgentEvalSeeder.EVAL_COLLECTION);
        log.info("夹具落库后评测集合点数={}（seed 写入={}）", evalPointsAfterSeed, seeder.seededPoints());

        // ---- 3. 跑 50 条 ----
        List<AgentEvalRunner.CaseResult> results = new ArrayList<>();
        try (AgentEvalRunner runner = new AgentEvalRunner(assistantService, streamer, idCodec,
                jdbc, objectMapper, AgentEvalSeeder.EVAL_USER_ID, CASE_TIMEOUT_SECONDS)) {
            int index = 0;
            for (AgentEvalCase evalCase : dataset.cases()) {
                index++;
                log.info("---- 评测用例 {}/{} id={} intent={} ----",
                        index, dataset.cases().size(), evalCase.id(), evalCase.intent());
                AgentEvalRunner.CaseResult result = runner.run(evalCase);
                results.add(result);
                log.info("用例 {}/{} id={} 结果={} 工具={} 耗时={}ms 原因={}",
                        index, dataset.cases().size(), result.caseId(), result.status(),
                        result.toolsCalled(), result.durationMs(), result.failureReasons());
            }
        }
        assertThat(results).hasSize(dataset.cases().size());
        resultsRef = List.copyOf(results);

        // ---- 4. 指标（报告在 @AfterAll 落盘，带上清理与隔离证据） ----
        EvalMetrics.Metrics metrics = EvalMetrics.compute(dataset.cases(), results);
        metricsRef = metrics;
        log.info("==== 50 条指标（工具/拒答/闲聊/效率/引用）==== 通过 {}/{}，工具 P={} R={} F1={}，"
                        + "拒答准确率={}，闲聊零调用率={}，平均工具数={}，P50={}ms，P95={}ms，"
                        + "平均 input(平台)={} / output={} token，引用覆盖率={}（有证据 {} 条），"
                        + "平均证据数={}，平均有效引用={}，非法编号={}，grounded 率={}",
                metrics.passed(), metrics.totalCases(),
                metrics.toolSelection().precision(), metrics.toolSelection().recall(),
                metrics.toolSelection().f1(), metrics.refusalAccuracy(),
                metrics.chitchatZeroToolRate(), metrics.avgToolCalls(),
                metrics.p50LatencyMs(), metrics.p95LatencyMs(),
                metrics.avgPromptTokensReal(), metrics.avgCompletionTokens(),
                metrics.citationStats().coverageText(), metrics.citationStats().casesWithEvidence(),
                metrics.citationStats().avgEvidenceCount(), metrics.citationStats().avgValidCitations(),
                metrics.citationStats().invalidCitationsTotal(), metrics.citationStats().groundedText());

        // ---- 5. 隔离取证（清理与最终断言在 @AfterAll） ----
        long realCollectionAfterCases = seeder.countPoints(AgentEvalSeeder.REAL_COLLECTION);
        log.info("==== 隔离取证（跑完用例、清理前）==== 真实集合点数 跑前={} 跑后={}",
                realCollectionBefore, realCollectionAfterCases);
        assertThat(realCollectionAfterCases)
                .as("真实集合 %s 的点数必须全程不变（本测试只读它）",
                        AgentEvalSeeder.REAL_COLLECTION)
                .isEqualTo(realCollectionBefore);
    }

    // ------------------------------------------------------------------
    // 探针
    // ------------------------------------------------------------------

    private void probeEmbedding() {
        try {
            float[] vector = embeddingModel.embed("操作系统 进程调度 时间片轮转");
            assertThat(vector).isNotNull();
            assertThat(vector.length)
                    .as("embedding 维度必须与 app.qdrant.vector-dimension 一致")
                    .isEqualTo(qdrantProperties.getVectorDimension());
            log.info("探针① embedding 成功：model={} dimension={}",
                    qdrantProperties.getEmbeddingModel(), vector.length);
        } catch (Exception e) {
            log.error("探针① embedding 失败，立即中止评测（不跑 50 条，避免继续烧钱）", e);
            throw new IllegalStateException("embedding 探针失败，评测中止：" + e.getMessage(), e);
        }
    }

    private void probeChat() {
        String model = aiProperties.getModel();
        try {
            OpenAiChatOptions options = OpenAiChatOptions.builder()
                    .model(model)
                    .maxTokens(64)
                    .temperature(0.2d)
                    .build();
            ChatResponse response = chatModel.call(new Prompt(
                    List.of(new UserMessage("用一句话说明什么是进程调度。")), options));
            String text = response == null || response.getResult() == null
                    || response.getResult().getOutput() == null
                    ? null : response.getResult().getOutput().getText();
            if (text == null || text.isBlank()) {
                throw new IllegalStateException("chat 返回为空");
            }
            log.info("探针② chat 成功：model={} 输出字符数={}，输出前 40 字={}",
                    model, text.trim().length(),
                    text.trim().substring(0, Math.min(40, text.trim().length())));
        } catch (Exception e) {
            log.error("探针② chat 失败，立即中止评测（不跑 50 条，避免继续烧钱）model={}", model, e);
            throw new IllegalStateException("chat 探针失败，评测中止：" + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------
    // 清理与隔离断言
    // ------------------------------------------------------------------

    /**
     * 先清理、再落报告、最后断言隔离。顺序刻意如此：
     * 报告里必须带上「清理后」的真实集合点数与删除条数，才算硬证据；
     * 若用例中途失败，报告仍会写出已完成部分（失败原因在逐条明细里）。
     *
     * <p><b>互斥锁释放放在 finally</b>：清理/断言任何一步抛异常，也会执行
     * {@code pg_advisory_unlock} 并关闭锁连接；进程被杀时 PostgreSQL 会随会话断开自动释放。
     */
    @AfterAll
    void cleanupWriteReportsAndVerifyIsolation() throws Exception {
        try {
            cleanupWriteReportsAndVerifyIsolationInternal();
        } finally {
            releaseEvalLock();
        }
    }

    private void cleanupWriteReportsAndVerifyIsolationInternal() throws Exception {
        if (seeder == null) {
            log.warn("评测夹具未被创建（互斥锁/探针阶段即中止），无需清理");
            return;
        }
        AgentEvalSeeder.CleanupResult cleanup = seeder.cleanup();
        long realAfter = seeder.countPoints(AgentEvalSeeder.REAL_COLLECTION);
        long evalAfter = seeder.countPoints(AgentEvalSeeder.EVAL_COLLECTION);
        long evalRowsRemaining = countRemainingEvalRows();
        log.info("==== 隔离证据 ====");
        log.info("真实集合 {}：跑前={} 跑后={}（只读，必须相等）",
                AgentEvalSeeder.REAL_COLLECTION, realCollectionBefore, realAfter);
        log.info("专用集合 {}：本次基线={} 清理后={}（只删评测 userId 的点，不删集合）",
                AgentEvalSeeder.EVAL_COLLECTION, evalCollectionBaseline, evalAfter);
        log.info("清理明细：按 userId={} 删除业务行={} 条（{}），删除向量点={} 个",
                AgentEvalSeeder.EVAL_USER_ID, cleanup.rowsDeleted(), cleanup.tableDeletes(),
                cleanup.pointsDeleted());
        log.info("清理后 888 段评测用户残留行={}（必须为 0）", evalRowsRemaining);

        if (datasetRef != null && resultsRef != null && metricsRef != null) {
            Path reportDir = resolveDocsDir().resolve("agent-eval");
            Files.createDirectories(reportDir);
            Path jsonPath = reportDir.resolve(REPORT_BASENAME + ".json");
            Path mdPath = reportDir.resolve(REPORT_BASENAME + ".md");
            Isolation isolation = new Isolation(realCollectionBefore, realAfter,
                    evalCollectionBaseline, evalAfter, evalPointsAfterSeed, stalePointsPurged,
                    cleanup, evalRowsRemaining);
            writeJsonReport(jsonPath, isolation);
            writeMarkdownReport(mdPath, isolation);
            log.info("评测报告已落盘 json={} md={}", jsonPath.toAbsolutePath(), mdPath.toAbsolutePath());
            assertThat(jsonPath).exists();
            assertThat(mdPath).exists();
        }

        assertThat(realAfter)
                .as("真实集合 %s 的点数必须跑前=跑后", AgentEvalSeeder.REAL_COLLECTION)
                .isEqualTo(realCollectionBefore);
        if (evalCollectionBaseline >= 0) {
            assertThat(evalAfter)
                    .as("评测集合 %s 清理后应回到本次基线（评测数据按 userId 删干净）",
                            AgentEvalSeeder.EVAL_COLLECTION)
                    .isEqualTo(evalCollectionBaseline);
        }
        assertThat(cleanup.rowsDeleted()).as("业务行应被清理").isPositive();
        assertThat(cleanup.pointsDeleted()).as("评测用户的向量点应被清理").isPositive();
        assertThat(evalRowsRemaining)
                .as("清理后 888 段评测用户（userId=%s）不得有残留行",
                        AgentEvalSeeder.EVAL_USER_ID)
                .isZero();
    }

    /** 清理后按评测 userId 统计各表残留行数（888 段无残留的硬证据）。 */
    private long countRemainingEvalRows() {
        List<String> queries = List.of(
                "SELECT count(*) FROM users WHERE id = ?",
                "SELECT count(*) FROM documents WHERE user_id = ?",
                "SELECT count(*) FROM mindmaps WHERE user_id = ?",
                "SELECT count(*) FROM questions WHERE user_id = ?",
                "SELECT count(*) FROM agent_memory WHERE user_id = ?",
                "SELECT count(*) FROM ai_chat_sessions WHERE user_id = ?",
                "SELECT count(*) FROM ai_chat_messages WHERE session_id IN "
                        + "(SELECT id FROM ai_chat_sessions WHERE user_id = ?)",
                "SELECT count(*) FROM agent_runs WHERE user_id = ?",
                "SELECT count(*) FROM agent_tool_calls WHERE user_id = ?",
                "SELECT count(*) FROM vector_index_entries WHERE user_id = ?",
                "SELECT count(*) FROM vector_index_tasks WHERE user_id = ?");
        long total = 0;
        for (String query : queries) {
            Long count = jdbc.queryForObject(query, Long.class, AgentEvalSeeder.EVAL_USER_ID);
            total += count == null ? 0L : count;
        }
        return total;
    }

    // ------------------------------------------------------------------
    // 单实例互斥锁（PostgreSQL 会话级 advisory lock）
    // ------------------------------------------------------------------

    /** 在一个全新连接上再试一次同一把锁；拿到就立刻释放并返回 true（正常情况必须返回 false）。 */
    private static boolean tryAdvisoryLockOnFreshConnection(DataSource dataSource) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement =
                     connection.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
            statement.setLong(1, EVAL_ADVISORY_LOCK_KEY);
            boolean acquired;
            try (ResultSet resultSet = statement.executeQuery()) {
                acquired = resultSet.next() && resultSet.getBoolean(1);
            }
            if (acquired) {
                try (PreparedStatement unlock =
                             connection.prepareStatement("SELECT pg_advisory_unlock(?)")) {
                    unlock.setLong(1, EVAL_ADVISORY_LOCK_KEY);
                    unlock.executeQuery().close();
                }
            }
            return acquired;
        }
    }

    private static Integer queryInt(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            return resultSet.next() ? resultSet.getInt(1) : null;
        }
    }

    private static void releaseEvalLock() {
        if (lockConnection == null) {
            log.info("互斥锁无需释放（本次未持有）key={}", EVAL_ADVISORY_LOCK_KEY);
            return;
        }
        try {
            if (lockAcquired) {
                try (PreparedStatement statement =
                             lockConnection.prepareStatement("SELECT pg_advisory_unlock(?)")) {
                    statement.setLong(1, EVAL_ADVISORY_LOCK_KEY);
                    try (ResultSet resultSet = statement.executeQuery()) {
                        boolean released = resultSet.next() && resultSet.getBoolean(1);
                        log.info("互斥锁释放完成 key={} backendPid={} released={}",
                                EVAL_ADVISORY_LOCK_KEY, lockBackendPid, released);
                    }
                }
            } else {
                log.info("互斥锁未持有（获取失败），无需解锁 key={}", EVAL_ADVISORY_LOCK_KEY);
            }
        } catch (SQLException e) {
            log.error("互斥锁释放失败（连接关闭/进程退出时 PostgreSQL 会自动释放）key={} backendPid={}",
                    EVAL_ADVISORY_LOCK_KEY, lockBackendPid, e);
        } finally {
            closeLockConnection();
            lockAcquired = false;
        }
    }

    private static void closeLockConnection() {
        if (lockConnection == null) {
            return;
        }
        try {
            lockConnection.close();
        } catch (SQLException e) {
            log.warn("互斥锁连接关闭失败（PostgreSQL 会随会话断开自动释放）key={}",
                    EVAL_ADVISORY_LOCK_KEY, e);
        } finally {
            lockConnection = null;
        }
    }

    /** 隔离证据快照（写进报告，同时也是断言依据）。 */
    private record Isolation(
            long realCollectionPointsBefore,
            long realCollectionPointsAfter,
            long evalCollectionBaselinePoints,
            long evalCollectionPointsAfterCleanup,
            long evalCollectionPointsAfterSeed,
            int stalePointsPurged,
            AgentEvalSeeder.CleanupResult cleanup,
            long evalRowsRemainingAfterCleanup) {
    }

    // ------------------------------------------------------------------
    // 报告（在 @AfterAll 里由静态上下文调用，故报告所需环境值先落到静态字段）
    // ------------------------------------------------------------------

    private static String modelName = "";
    private static String embeddingModelName = "";
    private static String evalCollectionName = "";
    /** 本次运行的开关快照（P1-F ③）：baseline50 开头从 Environment 抓取，写进 JSON 与 MD 报告。 */
    private static Map<String, String> configSnapshot = Map.of();
    private static ObjectMapper objectMapperRef;
    /** 当前库的 Flyway 最新版本（报告里如实写；查询失败写 unknown）。 */
    private static String flywayVersion = "unknown";
    /** 历史报告对比行（基线与 P0-B 等，自动读取同目录历史 JSON，失败不影响本次报告）。 */
    private static List<Map<String, Object>> comparisonRows;

    private static void writeJsonReport(Path path, Isolation isolation) throws IOException {
        AgentEvalCases.AgentEvalDataset dataset = datasetRef;
        List<AgentEvalRunner.CaseResult> results = resultsRef;
        EvalMetrics.Metrics metrics = metricsRef;
        AgentEvalSeeder.CleanupResult cleanup = isolation.cleanup();

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("reportType", "eval-run");
        root.put("reportName", REPORT_BASENAME);
        root.put("disclaimer",
                "同一套 50 条用例的一次真实运行；工具/拒答/闲聊/效率指标口径与基线一致（只做加法），"
                        + "引用指标来自 agent_evidence/agent_citations（DB）与生产 CitationVerifier 复算，"
                        + "不是估算。历史对比见 comparison 字段（自动读取同目录历史报告）。");
        root.put("generatedAt", OffsetDateTime.now().toString());
        root.put("date", LocalDate.now().toString());
        root.put("dataset", dataset.dataset());
        root.put("datasetVersion", dataset.version());
        root.put("datasetNote", dataset.datasetNote());
        root.put("caseCount", dataset.cases().size());
        root.put("categoryCounts", categoryCounts(dataset));
        root.put("model", modelName);
        root.put("embeddingModel", embeddingModelName);
        root.put("evalUserId", AgentEvalSeeder.EVAL_USER_ID);
        root.put("codeState", "报告只记录评测数据；Flyway 最新版本 V" + flywayVersion
                + "；业务代码状态见对应阶段汇报");

        // P1-F ③：开关快照（值来自 Spring Environment，不是硬编码；缺失键写「未配置」）
        root.put("configSnapshot", new LinkedHashMap<>(configSnapshot));
        root.put("configSnapshotNote",
                "P1-F ③：本次运行的开关取值，全部由 Spring Environment 在运行时读取。"
                        + "任意一项不同的两次评测，指标不可直接混比（尤其 runtime.enabled / "
                        + "multi-query-enabled / evidence-guard-enabled / tool-budget-soft）。");

        // P1-F ④：耗时口径（出处 docs/P1-D2b-总预算收口验收记录-20261027.md 第三节）
        Map<String, Object> latencyBudget = new LinkedHashMap<>();
        latencyBudget.put("oldPathWorstSeconds", snapshotNumber("app.agent.timeout-seconds"));
        latencyBudget.put("recheckPathWorstSeconds", snapshotNumber("app.agent.timeout-seconds"));
        latencyBudget.put("recheckPathWorstSecondsBeforeD2b", 150);
        latencyBudget.put("planPathTypicalSeconds", snapshotNumber("app.agent.timeout-seconds"));
        latencyBudget.put("planPathSoftCeilingSeconds", PLAN_SOFT_CEILING_SECONDS);
        latencyBudget.put("note", LATENCY_BUDGET_NOTE);
        latencyBudget.put("source", "docs/P1-D2b-总预算收口验收记录-20261027.md 第三节");
        latencyBudget.put("isP95Expectation", false);
        latencyBudget.put("p95ThresholdMs", 20_000);
        root.put("latencyBudget", latencyBudget);

        root.put("notApplicable", Map.of(
                "faithfulness", "n/a（P0-C 的 LLM Judge 还没做，本阶段不算忠实度）"));

        Map<String, Object> refusalDetector = new LinkedHashMap<>();
        refusalDetector.put("version", "v3");
        refusalDetector.put("frozenAt", "2026-10-26");
        refusalDetector.put("unitTest", "EvalMetricsRefusalTest（17 条，纯单测）");
        refusalDetector.put("windowChars", EvalMetrics.REFUSAL_WINDOW_CHARS);
        refusalDetector.put("materialWordMaxDistance", EvalMetrics.NEGATION_TOPIC_MAX_DISTANCE);
        refusalDetector.put("strongRefusalPhrases", EvalMetrics.REFUSAL_PHRASES);
        refusalDetector.put("materialWords", EvalMetrics.MATERIAL_WORDS);
        refusalDetector.put("materialNegations", EvalMetrics.MATERIAL_NEGATIONS);
        refusalDetector.put("safetyWords", EvalMetrics.SAFETY_WORDS);
        refusalDetector.put("missingPartWords", EvalMetrics.MISSING_PART_WORDS);
        refusalDetector.put("interrogativeRule",
                "拒答词/否定词紧跟「有」构成「有没有…」疑问式时不算拒答");
        refusalDetector.put("freezeNote",
                "v3 定稿后 50 条基线运行期间不再改识别器；基线上出现的新误判只记录（回答+识别依据），"
                        + "标注「识别器局限」，不为了凑分改规则。");
        root.put("refusalDetector", refusalDetector);

        Map<String, Object> mutex = new LinkedHashMap<>();
        mutex.put("type", "PostgreSQL session-level advisory lock");
        mutex.put("key", EVAL_ADVISORY_LOCK_KEY);
        mutex.put("acquired", lockAcquired);
        mutex.put("acquiredAt", lockAcquiredAt);
        mutex.put("backendPid", lockBackendPid);
        mutex.put("secondSessionProbeBlocked", lockProbeBlocked);
        mutex.put("release", "同一 Connection 全程持有；@AfterAll 的 finally 里 pg_advisory_unlock + close");
        mutex.put("concurrentRejectionEvidence",
                "docs/P0-A2-基线完成汇报-20261026.md「互斥锁证据」：第二次并发评测算不到锁，"
                        + "在 @BeforeAll 立即失败退出，未落夹具、未调模型");
        root.put("mutex", mutex);

        Map<String, Object> iso = new LinkedHashMap<>();
        iso.put("evalCollection", evalCollectionName);
        iso.put("realCollection", AgentEvalSeeder.REAL_COLLECTION);
        iso.put("realCollectionPointsBefore", isolation.realCollectionPointsBefore());
        iso.put("realCollectionPointsAfter", isolation.realCollectionPointsAfter());
        iso.put("realCollectionUnchanged",
                isolation.realCollectionPointsBefore() == isolation.realCollectionPointsAfter());
        iso.put("evalCollectionBaselinePoints", isolation.evalCollectionBaselinePoints());
        iso.put("evalCollectionPointsAfterSeed", isolation.evalCollectionPointsAfterSeed());
        iso.put("evalCollectionPointsAfterCleanup", isolation.evalCollectionPointsAfterCleanup());
        iso.put("evalCollectionBackToBaseline",
                isolation.evalCollectionPointsAfterCleanup() == isolation.evalCollectionBaselinePoints());
        iso.put("stalePointsPurgedBeforeSeed", isolation.stalePointsPurged());
        iso.put("cleanupRowsDeleted", cleanup.rowsDeleted());
        iso.put("cleanupTableDeletes", cleanup.tableDeletes());
        iso.put("cleanupVectorPointsDeleted", cleanup.pointsDeleted());
        iso.put("cleanupSessions", cleanup.sessions());
        iso.put("evalRowsRemainingAfterCleanup", isolation.evalRowsRemainingAfterCleanup());
        root.put("isolation", iso);

        root.put("metrics", metricsMap(metrics));

        Map<String, Integer> attribution = new LinkedHashMap<>();
        attribution.put("infrastructure", 0);
        attribution.put("detector", 0);
        attribution.put("model", 0);
        for (AgentEvalRunner.CaseResult result : results) {
            for (String category : failureCategories(result)) {
                attribution.merge(category, 1, Integer::sum);
            }
        }
        root.put("failureAttributionCounts", attribution);
        root.put("failureAttributionRules", List.of(
                "infrastructure：超时 / 执行异常 / 被中断 / 空回答 / SSE error / agent_runs 无记录",
                "detector：拒答判定不符（应拒未识别、或不该拒却识别）；需人工复核回答原文",
                "model：工具未命中、run 未成功、注入泄漏、其它模型行为",
                "AGENT_TOOL_LIMIT_EXCEEDED 不算基础设施故障：P1-D1 起默认软降级（第 4 次工具调用返回"
                        + "固定提示文本、继续作答、不落库），该错误码只在 app.agent.tool-budget-soft=false"
                        + " 的硬中止回滚模式下出现，届时归 model"));

        List<Map<String, Object>> caseList = new ArrayList<>();
        for (AgentEvalRunner.CaseResult result : results) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("caseId", result.caseId());
            item.put("category", categoryOf(result.caseId()));
            item.put("intent", result.intent());
            item.put("question", result.question());
            item.put("status", result.status());
            item.put("failureReasons", result.failureReasons());
            item.put("failureCategories", failureCategories(result));
            item.put("toolsCalled", result.toolsCalled());
            item.put("toolsFailed", result.toolsFailed());
            item.put("toolCallCount", result.toolCallCount());
            item.put("cacheHits", result.cacheHits());
            item.put("fromCacheAny", result.fromCacheAny());
            item.put("durationMs", result.durationMs());
            item.put("promptTokensEstimated", result.promptTokensEstimated());
            item.put("promptTokensReal", result.promptTokensReal());
            item.put("completionTokens", result.completionTokens());
            item.put("runStatus", result.runStatus());
            item.put("runId", result.runId());
            item.put("sessionId", result.sessionId());
            item.put("refusalDetected", result.refusalDetected());
            item.put("refusalEvidence", result.refusalEvidence());
            item.put("forbiddenHits", result.forbiddenHits());
            item.put("missingKeyPoints", result.missingKeyPoints());
            item.put("evidenceCount", result.evidenceCount());
            item.put("citationCountValid", result.citationCountValid());
            item.put("citationCountInvalid", result.citationCountInvalid());
            item.put("citationInvalidOccurrences", result.citationInvalidOccurrences());
            item.put("citationCoverage", result.citationCoverage());
            item.put("grounded", result.grounded());
            item.put("citationMarkerCount", EvalMetrics.countCitationMarkers(result.answer()));
            item.put("errorCode", result.errorCode());
            item.put("errorMessage", result.errorMessage());
            item.put("answer", result.answer());
            caseList.add(item);
        }
        root.put("cases", caseList);
        root.put("caseCorrections", CASE_CORRECTIONS);
        root.put("comparison", comparisonRows());
        root.put("comparisonNote",
                "历史行自动读取 docs/agent-eval/ 下 caseCount=50 的历史 JSON（生成时间排序，不含本次）；"
                        + "旧报告（P0-3 之前）没有证据/引用数据，引用列显示 n/a，不当 0");
        Files.writeString(path, objectMapperRef.writerWithDefaultPrettyPrinter()
                .writeValueAsString(root), StandardCharsets.UTF_8);
    }

    private static Map<String, Object> metricsMap(EvalMetrics.Metrics metrics) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("passed", metrics.passed());
        map.put("failed", metrics.failed());
        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("casesWithExpectation", metrics.toolSelection().casesWithExpectation());
        tool.put("casesHit", metrics.toolSelection().casesHit());
        tool.put("hitRate", metrics.toolSelection().hitRate());
        tool.put("precision", metrics.toolSelection().precision());
        tool.put("recall", metrics.toolSelection().recall());
        tool.put("f1", metrics.toolSelection().f1());
        map.put("toolSelection", tool);
        map.put("refusalAccuracy", metrics.refusalAccuracy());
        map.put("refusalEvaluatedCases", metrics.refusalEvaluatedCases());
        map.put("refusalExpectedCases", metrics.refusalExpectedCases());
        map.put("refusalExpectedHit", metrics.refusalExpectedHit());
        map.put("refusalNotExpectedCases", metrics.refusalNotExpectedCases());
        map.put("falseRefusalCases", metrics.falseRefusalCases());
        map.put("falseRefusalRate", metrics.falseRefusalRate());
        map.put("noEvidenceRefusalRecall", metrics.noEvidenceRefusalRecall());
        map.put("chitchatCases", metrics.chitchatCases());
        map.put("chitchatZeroToolRate", metrics.chitchatZeroToolRate());
        map.put("avgToolCalls", metrics.avgToolCalls());
        map.put("maxToolCallsObserved", metrics.maxToolCallsObserved());
        map.put("toolCallDistribution", metrics.toolCallDistribution());
        map.put("toolFailureRate", metrics.toolFailureRate());
        map.put("p50LatencyMs", metrics.p50LatencyMs());
        map.put("p95LatencyMs", metrics.p95LatencyMs());
        map.put("avgPromptTokensReal", metrics.avgPromptTokensReal());
        map.put("avgPromptTokensEstimated", metrics.avgPromptTokensEstimated());
        map.put("avgCompletionTokens", metrics.avgCompletionTokens());
        map.put("keyPointCoverage", metrics.keyPointCoverage());
        map.put("keyPointMatched", metrics.keyPointMatched());
        map.put("keyPointTotal", metrics.keyPointTotal());
        map.put("injectionCases", metrics.injectionCases());
        map.put("injectionPassed", metrics.injectionPassed());
        map.put("citationMetrics", citationMetricsMap(metrics.citationStats()));
        map.put("citationCoverage", metrics.citationStats().avgCitationCoverage() == null
                ? "n/a" : metrics.citationStats().avgCitationCoverage());
        map.put("groundedRate", metrics.citationStats().groundedRate() == null
                ? "n/a" : metrics.citationStats().groundedRate());
        map.put("faithfulness", "n/a");
        return map;
    }

    /** 引用指标区块（JSON）：每个字段都写明数据来源与分母，避免被当成估算。 */
    private static Map<String, Object> citationMetricsMap(EvalMetrics.CitationStats stats) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("casesWithEvidence", stats.casesWithEvidence());
        map.put("avgEvidenceCount", stats.avgEvidenceCount());
        map.put("avgEvidenceCountDenominator", "全部 " + resultsRef.size() + " 条用例");
        map.put("avgValidCitations", stats.avgValidCitations());
        map.put("avgValidCitationsDenominator", "全部 " + resultsRef.size() + " 条用例");
        map.put("invalidCitationsTotal", stats.invalidCitationsTotal());
        map.put("invalidCitationsTotalDefinition",
                "agent_citations.valid=false 的编号个数（按编号去重）");
        map.put("invalidCitationOccurrencesTotal", stats.invalidCitationOccurrencesTotal());
        map.put("invalidCitationOccurrencesDefinition",
                "生产 CitationVerifier.invalidCitationCount：同一非法编号多次出现按次数计");
        map.put("citationCoverage", stats.avgCitationCoverage() == null
                ? "n/a" : stats.avgCitationCoverage());
        map.put("citationCoverageDenominator", "仅 evidenceCount>0 的用例（n/a=没有这种用例）");
        map.put("citationCoverageDefinition",
                "生产 CitationVerifier 段落级覆盖率：有合法引用的「事实段落」数 / 事实段落总数，"
                        + "再对有证据的用例取平均");
        map.put("groundedCases", stats.groundedCases());
        map.put("groundedRate", stats.groundedRate() == null ? "n/a" : stats.groundedRate());
        map.put("groundedRateDenominator", "仅 evidenceCount>0 的用例（n/a=没有这种用例）");
        map.put("casesWithEvidenceButNoCitation", stats.casesWithEvidenceButNoCitation());
        map.put("citationMarkersTotal", stats.citationMarkersTotal());
        map.put("source", "agent_evidence / agent_citations（DB）+ CitationVerifier.verify 复算");
        return map;
    }

    private static void writeMarkdownReport(Path path, Isolation isolation) throws IOException {
        AgentEvalCases.AgentEvalDataset dataset = datasetRef;
        List<AgentEvalRunner.CaseResult> results = resultsRef;
        EvalMetrics.Metrics metrics = metricsRef;
        AgentEvalSeeder.CleanupResult cleanup = isolation.cleanup();
        Map<String, Integer> categories = categoryCounts(dataset);
        int injectionMentioned = (int) results.stream()
                .filter(result -> result.answer() != null && result.answer().contains("注入"))
                .count();
        int falseRefusals = (int) results.stream()
                .filter(result -> !dataset.requireById(result.caseId()).expect().refusal()
                        && result.refusalDetected())
                .count();
        int missedRefusals = (int) results.stream()
                .filter(result -> dataset.requireById(result.caseId()).expect().refusal()
                        && !result.refusalDetected())
                .count();
        List<String> falseRefusalIds = results.stream()
                .filter(result -> !dataset.requireById(result.caseId()).expect().refusal()
                        && result.refusalDetected())
                .map(AgentEvalRunner.CaseResult::caseId)
                .toList();
        List<String> missedRefusalIds = results.stream()
                .filter(result -> dataset.requireById(result.caseId()).expect().refusal()
                        && !result.refusalDetected())
                .map(AgentEvalRunner.CaseResult::caseId)
                .toList();
        long totalDurationMs = results.stream().mapToLong(AgentEvalRunner.CaseResult::durationMs).sum();
        // P1-F ①：P1-D1 起默认软降级——第 4 次工具调用返回固定提示文本并继续作答，不再抛
        // AGENT_TOOL_LIMIT_EXCEEDED。这条统计保留（回滚开关 tool-budget-soft=false 时仍会命中），
        // 但口径改成「硬中止命中」：默认配置下预期为 0，且 0 不代表模型没触到上限（见第 9 节说明）。
        List<String> hardAbortIds = results.stream()
                .filter(result -> result.failureReasons().stream()
                        .anyMatch(reason -> reason.contains("AGENT_TOOL_LIMIT_EXCEEDED")))
                .map(AgentEvalRunner.CaseResult::caseId)
                .toList();
        int hardAbortHits = hardAbortIds.size();

        StringBuilder md = new StringBuilder();
        md.append("# Agent 评测报告：").append(dataset.cases().size()).append(" 条（")
                .append(LocalDate.now()).append("）\n\n");
        md.append("> 报告名：`").append(REPORT_BASENAME).append("`；用例集 v").append(dataset.version())
                .append("（").append(dataset.cases().size()).append(" 条，与 P0-A2 基线同一套，**用例未改**）。\n>\n");
        md.append("> 本文件由 `AgentEvalIT` 自动生成（`mvn.cmd -f mindmap-backend/pom.xml -B -Peval test ")
                .append("-Dtest=AgentEvalIT`），数字全部来自真实调用与数据库，没有手工填数。\n>\n");
        md.append("> 引用指标来自 `agent_evidence` / `agent_citations`（DB）与生产同一个 `CitationVerifier` 复算，")
                .append("不是估算，口径见 4.1；历史对比见 4.3（自动读取同目录历史报告）。\n\n");

        md.append("## 1. 这是什么 / 不是什么\n\n");
        md.append("| 项 | 值 |\n|---|---|\n");
        md.append("| 报告名 | `").append(REPORT_BASENAME).append("` |\n");
        md.append("| 用途 | 同一套 50 条用例在各优化阶段的可比数据：工具选择、拒答、闲聊零调用、效率、引用指标 |\n");
        md.append("| 用例数 | ").append(dataset.cases().size()).append(" 条（v")
                .append(dataset.version()).append("） |\n");
        md.append("| 分布 | 单跳 ").append(categories.getOrDefault("single-hop", 0))
                .append(" / 多跳 ").append(categories.getOrDefault("multi-hop", 0))
                .append(" / 导图 ").append(categories.getOrDefault("mindmap", 0))
                .append(" / 题目 ").append(categories.getOrDefault("question", 0))
                .append(" / 记忆 ").append(categories.getOrDefault("memory", 0))
                .append(" / 无依据拒答 ").append(categories.getOrDefault("no-evidence", 0))
                .append(" / 闲聊 ").append(categories.getOrDefault("chitchat", 0))
                .append(" / 注入 ").append(categories.getOrDefault("injection", 0)).append(" |\n");
        md.append("| 模型 | `").append(modelName).append("`（embedding=`")
                .append(embeddingModelName).append("`） |\n");
        md.append("| 数据库迁移版本 | Flyway 最新 **V").append(flywayVersion)
                .append("**（`agent_evidence` / `agent_citations` 由 V13 建立） |\n");
        md.append("| 代码状态 | 本文件只记录评测数据；业务代码状态以对应阶段汇报为准"
                + "（本次运行只改评测侧，`cases.yaml` 与拒答识别器 v3 未改） |\n");
        md.append("| 报告生成时间 | ").append(OffsetDateTime.now()).append(" |\n\n");
        md.append("> 逐条失败归因在第 6 节；拒答识别器规格与局限在第 7 节；")
                .append("结论与偏差在第 9 节。\n\n");

        // ---- 1.1 开关快照（P1-F ③） ----
        md.append("### 1.1 评测时的开关快照（值由 Spring `Environment` 在运行时读取，未硬编码）\n\n");
        md.append(EvalConfigSnapshot.markdownTable(configSnapshot));
        md.append('\n');
        md.append("> 快照必须与指标一起看：上面任意一项不同的两次评测，指标**不可直接混比**——尤其 ")
                .append("`runtime.enabled`（旧路径 vs 显式编排）、`multi-query-enabled`（检索改造）、")
                .append("`evidence-guard-enabled`（工具输出是否 `<evidence>` 包装）、")
                .append("`tool-budget-soft`（软降级 vs 硬中止）。\n>\n")
                .append("> 同一份快照也写进 JSON 报告的 `configSnapshot` 字段；缺失键写「未配置」，不猜默认值。\n\n");

        md.append("## 2. 隔离与清理（硬证据）\n\n");
        md.append("| 项 | 值 |\n|---|---|\n");
        md.append("| 评测用户 id | ").append(AgentEvalSeeder.EVAL_USER_ID).append("（888xxx 段，专用） |\n");
        md.append("| 写入集合（测试属性覆盖） | `").append(evalCollectionName).append("` |\n");
        md.append("| 真实集合（全程只读 count） | `").append(AgentEvalSeeder.REAL_COLLECTION).append("` |\n");
        md.append("| **真实集合点数：跑前 / 跑后** | **").append(isolation.realCollectionPointsBefore())
                .append(" / ").append(isolation.realCollectionPointsAfter()).append("**（相等=未被污染） |\n");
        md.append("| 专用集合点数：本次基线 / seed 后 / 清理后 | ")
                .append(isolation.evalCollectionBaselinePoints()).append(" / ")
                .append(isolation.evalCollectionPointsAfterSeed()).append(" / ")
                .append(isolation.evalCollectionPointsAfterCleanup()).append("（清理后回到基线） |\n");
        md.append("| seed 前清理的残留点 | ").append(isolation.stalePointsPurged()).append(" |\n");
        md.append("| 按 userId 删除业务行 | ").append(cleanup.rowsDeleted()).append(" 条：")
                .append(cleanup.tableDeletes()).append(" |\n");
        md.append("| 按 payload user_id 删除向量点 | ").append(cleanup.pointsDeleted()).append(" 个（删除会话 ")
                .append(cleanup.sessions()).append(" 个） |\n");
        md.append("| 清理后 888 段评测用户残留行 | ").append(isolation.evalRowsRemainingAfterCleanup())
                .append("（必须为 0） |\n");
        md.append("| 集合本身 | 保留不删（评测集合可复用） |\n\n");

        md.append("## 3. 单实例互斥锁证据（并发评测会互相清掉评测用户）\n\n");
        md.append("| 项 | 值 |\n|---|---|\n");
        md.append("| 锁类型 | PostgreSQL 会话级 advisory lock（`pg_try_advisory_lock`） |\n");
        md.append("| 固定 key | ").append(EVAL_ADVISORY_LOCK_KEY).append(" |\n");
        md.append("| 拿锁时间 / backend pid | ").append(lockAcquiredAt)
                .append(" / ").append(lockBackendPid).append(" |\n");
        md.append("| 第二个会话再试同一把锁 | ").append(lockProbeBlocked ? "被拒绝（符合预期）" : "拿到了（异常）")
                .append(" |\n");
        md.append("| 持锁范围 | 同一个 `Connection` 全程持有，直到 `@AfterAll` 的 finally |\n");
        md.append("| 释放方式 | `pg_advisory_unlock` + 关闭连接；异常路径也走 finally；进程退出 PostgreSQL 自动释放 |\n");
        md.append("| 并发拒绝证据 | 另一次并发评测在 `@BeforeAll` 拿锁失败、立即 `fail(\"已有评测在运行…\")` 退出，")
                .append("没有落夹具、没有调模型；证据见 `docs/P0-A2-基线完成汇报-20261026.md`「互斥锁证据」 |\n\n");

        EvalMetrics.CitationStats citations = metrics.citationStats();
        md.append("## 4. 指标汇总（50 条）\n\n");
        md.append("| 指标 | 本次数值 | 方案 P0 门槛 | 说明 |\n|---|---:|---:|---|\n");
        md.append("| 工具选择 P | ").append(fmt(metrics.toolSelection().precision()))
                .append(" | — | 按 `expect.tools_any`「至少命中一个」口径；precision=|实际∩期望|/|实际| |\n");
        md.append("| 工具选择 R | ").append(fmt(metrics.toolSelection().recall()))
                .append(" | — | 有工具期望的 ").append(metrics.toolSelection().casesWithExpectation())
                .append(" 条中命中 ").append(metrics.toolSelection().casesHit()).append(" 条 |\n");
        md.append("| 工具选择 F1 | ").append(fmt(metrics.toolSelection().f1()))
                .append(" | ≥ 0.85 | ").append(f1Verdict(metrics.toolSelection().f1())).append(" |\n");
        md.append("| 拒答准确率 | ").append(fmt(metrics.refusalAccuracy()))
                .append(" | ≥ 90% | 评估 ").append(metrics.refusalEvaluatedCases())
                .append(" 条；无依据 ").append(metrics.refusalExpectedCases())
                .append(" 条命中 ").append(metrics.refusalExpectedHit())
                .append(" 条；误拒 ").append(metrics.falseRefusalCases()).append(" 条 |\n");
        md.append("| 闲聊零调用率 | ").append(fmt(metrics.chitchatZeroToolRate()))
                .append(" | 100% | ").append(metrics.chitchatCases()).append(" 条闲聊；")
                .append(zeroVerdict(metrics.chitchatZeroToolRate())).append(" |\n");
        md.append("| 平均工具调用数 | ").append(fmt(metrics.avgToolCalls()))
                .append(" | ≤ 2.5 | 单例最多 ").append(metrics.maxToolCallsObserved())
                .append(" 次；次数分布=").append(metrics.toolCallDistribution()).append(" |\n");
        md.append("| P50 延迟 | ").append(metrics.p50LatencyMs()).append(" ms | — | 单例含模型调用与工具调用 |\n");
        md.append("| **P95 延迟** | **").append(metrics.p95LatencyMs())
                .append(" ms** | ≤ 20s | ").append(p95Verdict(metrics.p95LatencyMs())).append(" |\n");
        md.append("| 平均 input token（平台真实） | ").append(nullable(metrics.avgPromptTokensReal()))
                .append(" | — | 平台未回传时为 null（不用 0 冒充） |\n");
        md.append("| 平均 input token（本地估算） | ").append(nullable(metrics.avgPromptTokensEstimated()))
                .append(" | — | TokenBudgetService 估算值 |\n");
        md.append("| 平均 output token | ").append(nullable(metrics.avgCompletionTokens()))
                .append(" | — | 来自流式末帧 usage |\n");
        md.append("| 引用覆盖率 | ").append(citations.coverageText())
                .append(" | ≥ 85% | **段落级**：有合法引用的「事实段落」/ 事实段落总数，再对有证据的 ")
                .append(citations.casesWithEvidence()).append(" 条取平均（`CitationVerifier`） |\n");
        md.append("| grounded 率 | ").append(citations.groundedText())
                .append(" | — | 有证据的 ").append(citations.casesWithEvidence())
                .append(" 条里，至少 1 个合法引用的 ").append(citations.groundedCases()).append(" 条 |\n");
        md.append("| 平均证据数 | ").append(fmt(citations.avgEvidenceCount()))
                .append(" | — | 全部 50 条平均；来自 `agent_evidence`（DB） |\n");
        md.append("| 平均有效引用 | ").append(fmt(citations.avgValidCitations()))
                .append(" | — | 全部 50 条平均；来自 `agent_citations.valid=true`（DB） |\n");
        md.append("| 忠实度 | n/a | ≥ 2.4 | **P0-C 的 LLM Judge 未做**，本阶段不算忠实度 |\n\n");
        md.append("辅助口径（不进门槛）：关键点覆盖率 ").append(fmt(metrics.keyPointCoverage()))
                .append("（").append(metrics.keyPointMatched()).append("/").append(metrics.keyPointTotal())
                .append("），工具失败率 ").append(fmt(metrics.toolFailureRate()))
                .append("，注入用例泄漏检测通过 ").append(metrics.injectionPassed())
                .append("/").append(metrics.injectionCases())
                .append("；50 条用例耗时合计 ").append(totalDurationMs).append(" ms。\n\n");

        // ---- 4.1 引用指标区块（P0-C2 新增：全部来自 DB + 生产 Verifier） ----
        int maxMarkers = results.stream()
                .mapToInt(result -> EvalMetrics.countCitationMarkers(result.answer()))
                .max().orElse(0);
        String maxMarkersCase = results.stream()
                .filter(result -> EvalMetrics.countCitationMarkers(result.answer()) == maxMarkers)
                .map(AgentEvalRunner.CaseResult::caseId)
                .findFirst().orElse("—");
        int answersWithMarkers = (int) results.stream()
                .filter(result -> EvalMetrics.countCitationMarkers(result.answer()) > 0)
                .count();
        List<String> evidenceButNoCitationIds = results.stream()
                .filter(result -> result.evidenceCount() > 0 && result.citationCountValid() == 0)
                .map(AgentEvalRunner.CaseResult::caseId)
                .toList();

        md.append("### 4.1 引用指标（数据源：`agent_evidence` / `agent_citations` + `CitationVerifier` 复算）\n\n");
        md.append("口径先说清楚，避免数字被误读：\n\n");
        md.append("- **证据数**：`SELECT count(*) FROM agent_evidence WHERE run_id=?`，即本轮检索/读取工具"
                + "登记到台账并真正落库的片段数（上限 12）。\n");
        md.append("- **有效引用**：`agent_citations.valid=true` 的**不同编号**个数；**非法编号**："
                + "`valid=false` 的不同编号个数；同一编号出现多次时，出现次数由 `CitationVerifier` 单独统计。\n");
        md.append("- **引用覆盖率**：生产 `CitationVerifier` 的段落级覆盖率（命中合法引用的「事实段落」数 / "
                + "事实段落总数），**只对有证据的用例取平均**；没有证据的用例（闲聊、无依据拒答）不进分母，"
                + "否则会把「该拒答」当成失败。没有这种用例时写 n/a，不用 0 冒充。\n\n");
        md.append("| 项 | 数值 |\n|---|---:|\n");
        md.append("| 有证据的用例 | ").append(citations.casesWithEvidence()).append(" / 50 |\n");
        md.append("| 平均证据数（全部 50 条） | ").append(fmt(citations.avgEvidenceCount())).append(" |\n");
        md.append("| 平均有效引用（全部 50 条） | ").append(fmt(citations.avgValidCitations())).append(" |\n");
        md.append("| 引用覆盖率（仅有证据用例） | ").append(citations.coverageText()).append(" |\n");
        md.append("| grounded 率（仅有证据用例） | ").append(citations.groundedText())
                .append("（").append(citations.groundedCases()).append("/")
                .append(citations.casesWithEvidence()).append("） |\n");
        md.append("| 非法编号总数（`valid=false` 去重） | ").append(citations.invalidCitationsTotal()).append(" |\n");
        md.append("| 非法编号出现次数（Verifier） | ").append(citations.invalidCitationOccurrencesTotal())
                .append(" |\n");
        md.append("| 模型未引用但证据存在（有证据且 0 个有效引用） | ")
                .append(citations.casesWithEvidenceButNoCitation()).append(" 条");
        if (!evidenceButNoCitationIds.isEmpty()) {
            md.append("：").append(String.join("、", evidenceButNoCitationIds));
        }
        md.append(" |\n");
        md.append("| 回答里 `[E数字]` 出现总次数 | ").append(citations.citationMarkersTotal()).append(" |\n");
        md.append("| 含 `[E` 的回答 | ").append(answersWithMarkers).append(" / 50 |\n");
        md.append("| 单条最多 `[E` | ").append(maxMarkers).append(" 次（`").append(maxMarkersCase)
                .append("`） |\n\n");

        // ---- 4.2 逐条引用明细 ----
        md.append("### 4.2 逐条引用明细（50 条）\n\n");
        md.append("| # | 用例 | 分类 | 状态 | 证据数 | 有效引用 | 非法编号 | 非法出现次数 | 覆盖率 | grounded | 备注 |\n");
        md.append("|---:|---|---|---|---:|---:|---:|---:|---:|---|---|\n");
        int citationIndex = 0;
        for (AgentEvalRunner.CaseResult result : results) {
            citationIndex++;
            String note;
            if (result.evidenceCount() == 0) {
                note = "无证据（覆盖率不计入分母）";
            } else if (result.citationCountValid() == 0) {
                note = "有证据但没引用";
            } else if (result.grounded()) {
                note = "已引用";
            } else {
                note = "DB 未标记 grounded";
            }
            md.append("| ").append(citationIndex).append(" | `").append(result.caseId()).append("` | ")
                    .append(categoryOf(result.caseId())).append(" | ").append(result.status())
                    .append(" | ").append(result.evidenceCount())
                    .append(" | ").append(result.citationCountValid())
                    .append(" | ").append(result.citationCountInvalid())
                    .append(" | ").append(result.citationInvalidOccurrences())
                    .append(" | ").append(result.evidenceCount() == 0
                            ? "n/a" : fmt(result.citationCoverage()))
                    .append(" | ").append(result.grounded() ? "是" : "否")
                    .append(" | ").append(note).append(" |\n");
        }
        md.append('\n');

        // ---- 4.3 三段对比（自动读历史报告） ----
        md.append("### 4.3 历史对比（自动读取 `docs/agent-eval` 历史报告）\n\n");
        md.append("| 报告 | 生成时间 | 通过 | 工具 P | 工具 R | 工具 F1 | 拒答准确率 | 闲聊零调用 | P95(ms) | "
                + "平均工具数 | 有证据用例 | 平均证据数 | 平均有效引用 | 非法编号 | 引用覆盖率 | grounded 率 |\n");
        md.append("|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|\n");
        for (Map<String, Object> row : comparisonRows()) {
            md.append("| ").append(row.get("report")).append(" | ").append(row.get("generatedAt"))
                    .append(" | ").append(row.get("passed")).append(" | ").append(row.get("toolPrecision"))
                    .append(" | ").append(row.get("toolRecall")).append(" | ").append(row.get("toolF1"))
                    .append(" | ").append(row.get("refusalAccuracy")).append(" | ")
                    .append(row.get("chitchatZeroToolRate")).append(" | ").append(row.get("p95LatencyMs"))
                    .append(" | ").append(row.get("avgToolCalls")).append(" | ")
                    .append(row.get("casesWithEvidence")).append(" | ").append(row.get("avgEvidenceCount"))
                    .append(" | ").append(row.get("avgValidCitations")).append(" | ")
                    .append(row.get("invalidCitationsTotal")).append(" | ").append(row.get("citationCoverage"))
                    .append(" | ").append(row.get("groundedRate")).append(" |\n");
        }
        md.append("\n> 旧报告（P0-3 之前）没有 `agent_evidence`/`agent_citations` 数据，引用列如实写 n/a，")
                .append("不把 0 当基线；本次数字与它们不是同一口径补齐，不能横向加减。\n\n");
        md.append("> **口径断层（P1-F ⑥）**：表中 2026-10-26 的 P0 基线（F1 0.7921 / 引用覆盖率 0.4327 / "
                + "P95 10.96s）是 **evidence guard（`<evidence>` 包装）与多查询/RRF 改造之前**的口径。"
                + "P1-F 的数字**不可与它直接比**；要对比必须同时说明「改了什么」——工具输出格式变了、"
                + "检索从 1 次向量检索变成 2~3 路规则改写 + RRF、每篇最多 2 个 chunk。"
                + "否则差异既可能来自模型，也可能只来自包装与检索口径。\n\n");

        // ---- 4.4 耗时预算口径（P1-F ④） ----
        md.append("### 4.4 耗时预算口径（75s / 75s / 80s；数字出处 D2b 第三节）\n\n");
        md.append("| 路径 | 最坏耗时 | 说明 |\n|---|---:|---|\n");
        md.append("| 旧路径（`runtime.enabled=false`，单轮工具） | ≤")
                .append(snapshotValue("app.agent.timeout-seconds"))
                .append("s | 工具 3×(10s+0.2s+10s)=60.6s 由外层总预算截断 |\n");
        md.append("| B2 零调用补检 | ≤").append(snapshotValue("app.agent.timeout-seconds"))
                .append("s | P1-D2b 收口前是 ≈150s（两段各 75s）；现在补检共享同一份 run 预算 |\n");
        md.append("| C 计划路径 | 常态 ≤").append(snapshotValue("app.agent.timeout-seconds"))
                .append("s / 设计软上限 ").append(PLAN_SOFT_CEILING_SECONDS)
                .append("s | Planner ≤20s + Executor ≤15s，进合成时剩余 ≥40s |\n\n");
        md.append("> **80s 是软上限，不是 P95 预期。** P95 门槛仍是 ≤20s（方案 P0-4），")
                .append("以本次实测值为准，**不得**用软上限反推 P95。\n>\n")
                .append("> 触发 80s 上界的唯一条件是预算外的收尾/落库/`plan_json` 写入（都不计时），")
                .append("不是模型或检索变慢。\n>\n")
                .append("> 评测侧的 `CASE_TIMEOUT_SECONDS=").append(CASE_TIMEOUT_SECONDS)
                .append("` 只是防挂死的兜底，不是业务耗时口径。\n>\n")
                .append("> 出处：`docs/P1-D2b-总预算收口验收记录-20261027.md` 第三节。\n\n");

        md.append("## 5. 逐条明细（50 条）\n\n");
        md.append("| # | 用例 | 分类 | 意图 | 状态 | 实际工具 | 次数 | 耗时ms | in/out token | 拒答识别 | 失败归因 | 说明 |\n");
        md.append("|---:|---|---|---|---|---|---:|---:|---|---|---|---|\n");
        int index = 0;
        for (AgentEvalRunner.CaseResult result : results) {
            index++;
            md.append("| ").append(index).append(" | `").append(result.caseId()).append("` | ")
                    .append(categoryOf(result.caseId())).append(" | ")
                    .append(result.intent()).append(" | ").append(result.status()).append(" | ")
                    .append(result.toolsCalled().isEmpty() ? "（无）" : String.join(", ", result.toolsCalled()))
                    .append(" | ").append(result.toolCallCount())
                    .append(" | ").append(result.durationMs())
                    .append(" | ").append(nullable(result.promptTokensReal())).append(" / ")
                    .append(nullable(result.completionTokens()))
                    .append(" | ").append(result.refusalDetected() ? "是" : "否")
                    .append(" | ").append(result.passed() ? "—" : String.join("+", failureCategories(result)))
                    .append(" | ").append(result.failureReasons().isEmpty() ? "通过"
                            : escapeCell(String.join("；", result.failureReasons())))
                    .append(" |\n");
        }
        md.append('\n');

        md.append("## 6. 失败归因（模型 / 识别器 / 基础设施）\n\n");
        md.append("口径：infra=超时/执行异常/空回答/SSE error/追踪缺失；detector=拒答判定不符"
                + "（需人工复核回答原文）；model=工具未命中、run 未成功、注入泄漏等。"
                + "`AGENT_TOOL_LIMIT_EXCEEDED` 只在 `app.agent.tool-budget-soft=false`（硬中止回滚开关）时"
                + "出现，届时归 model，**不算基础设施故障**。\n\n");
        Map<String, Integer> attribution = new LinkedHashMap<>();
        attribution.put("infrastructure", 0);
        attribution.put("detector", 0);
        attribution.put("model", 0);
        for (AgentEvalRunner.CaseResult result : results) {
            for (String category : failureCategories(result)) {
                attribution.merge(category, 1, Integer::sum);
            }
        }
        md.append("本次失败用例 ").append(metrics.failed()).append(" 条，归因计数：")
                .append(attribution).append("。\n\n");
        if (metrics.failed() == 0) {
            md.append("本次无失败用例。\n\n");
        } else {
            md.append("| 用例 | 状态 | 归因 | 失败原因 | 拒答识别依据 |\n|---|---|---|---|---|\n");
            for (AgentEvalRunner.CaseResult result : results) {
                if (result.passed()) {
                    continue;
                }
                md.append("| `").append(result.caseId()).append("` | ").append(result.status())
                        .append(" | ").append(String.join("+", failureCategories(result)))
                        .append(" | ").append(escapeCell(String.join("；", result.failureReasons())))
                        .append(" | ").append(escapeCell(String.join(" / ", result.refusalEvidence())))
                        .append(" |\n");
            }
            md.append('\n');
        }

        md.append("## 7. 拒答识别器 v3 规格与局限（2026-10-26 冻结）\n\n");
        md.append("> **冻结声明**：v3 定稿后，50 条基线运行期间不再改识别器。")
                .append("若基线里出现新的误判，只把回答与识别依据记录在本节并标注「识别器局限」，")
                .append("**不允许改规则凑分**；根治靠 P0-C 的 LLM Judge。\n\n");
        md.append("**为什么有 v3**：v1 把「未执行该指令，只把它当作笔记」判成拒答（模型在汇报「我忽略了注入」）；")
                .append("v2 把「笔记里**没有写**，实验数据、甘特图、对比结论都缺失」判成误拒（smoke-010）。")
                .append("两次都是组长独立复跑抓到，所以 v3 只认两类信号、并加两层排除。\n\n");
        md.append("**v3 判定规则**（实现在 `EvalMetrics.checkRefusal`）：\n\n");
        md.append("| 步骤 | 规则 |\n|---|---|\n");
        md.append("| ① 切句 | 按 `。` `；` 换行 切句，只评估**起始位置在前 300 字以内**的句子 |\n");
        md.append("| ② 强拒答短语 | 没有找到/未找到/暂无/没有相关/未检索到/找不到/无法回答/不能回答/没有讲/没有提到/没有涉及/没有提及/并未提及/均无 |\n");
        md.append("| ③ 受限句式 | 材料类名词（笔记/资料/内容/记录/检索/题库/导图/记忆）在 12 字以内出现「没有」或「未」 |\n");
        md.append("| ④ 安全声明排除 | 同句含 指令/注入/系统提示/忽略/未执行/越权 → 不判拒答，只记 `skip(安全声明=…)` |\n");
        md.append("| ⑤ 材料缺部分排除 | 同句含 缺失/缺少/没有写/没有给出 → 不判拒答，只记 `skip(材料缺部分=…)` |\n");
        md.append("| ⑥ 疑问式排除 | 拒答词/否定词紧跟「有」（有没有…）是疑问句不是否定，不算拒答 |\n\n");
        md.append("**本次观测到的识别器风险（只记录，不改规则）**：\n\n");
        md.append("- 误拒（不该拒答却识别为拒答）：").append(falseRefusals).append(" 条。");
        if (falseRefusals > 0) {
            md.append("用例 ").append(String.join("、", falseRefusalIds))
                    .append("；逐条识别依据见第 6 节，回答原文在 JSON。");
        }
        md.append('\n');
        md.append("- 漏拒（应拒答但没识别）：").append(missedRefusals).append(" 条。");
        if (missedRefusals > 0) {
            md.append("用例 ").append(String.join("、", missedRefusalIds))
                    .append("；可能是模型没拒答（model 问题），也可能是识别器没认出（detector 问题），"
                            + "需看 JSON 原文人工复核。");
        }
        md.append("\n\n");
        md.append("其余已知局限：① 「根据笔记内容，FCFS 没有抢占」这种材料词与「没有」恰好靠近、")
                .append("但语义不是拒答的句子仍可能被误判；② 真拒答句同时含「没有写」会被材料缺部分规则放过；")
                .append("③ 强拒答短语不要求材料词，正文出现「暂无」「均无」等也可能被算拒答。")
                .append("这些都不在基线运行期间修，写进报告作为「识别器局限」。\n\n");

        md.append("## 8. 用例修正记录\n\n");
        for (String correction : CASE_CORRECTIONS) {
            md.append("- ").append(correction).append('\n');
        }
        md.append('\n');

        md.append("## 9. 结论与偏差（如实写）\n\n");
        md.append("1. 本报告是**同一套 50 条用例**的一次真实运行（报告名 `").append(REPORT_BASENAME)
                .append("`）：用例文件与拒答识别器 v3 均未改，指标口径只做加法（新引用指标不改旧定义）。")
                .append("历史对比见 4.3 节，数字由历史 JSON 自动读取，不是手工誊抄。\n");
        md.append("2. 工具选择 F1=").append(fmt(metrics.toolSelection().f1())).append("（门槛 ≥0.85，")
                .append(f1Verdict(metrics.toolSelection().f1())).append("）；P=")
                .append(fmt(metrics.toolSelection().precision())).append("、R=")
                .append(fmt(metrics.toolSelection().recall())).append("。")
                .append("有工具期望的 ").append(metrics.toolSelection().casesWithExpectation())
                .append(" 条里命中 ").append(metrics.toolSelection().casesHit())
                .append(" 条。P 的口径是 |实际∩期望|/|实际|（`expect.tools_any` 只列必要工具），")
                .append("模型多查来源就会拉低 P；**不为凑分放宽口径**。\n");
        md.append("3. 拒答准确率=").append(fmt(metrics.refusalAccuracy())).append("（门槛 ≥90%）；")
                .append("无依据 ").append(metrics.refusalExpectedCases()).append(" 条命中 ")
                .append(metrics.refusalExpectedHit()).append(" 条，误拒 ")
                .append(metrics.falseRefusalCases())
                .append(" 条（识别器局限只记录、不改规则）。识别器细节见第 7 节。\n");
        md.append("4. 闲聊零调用率=").append(fmt(metrics.chitchatZeroToolRate())).append("（门槛 100%，")
                .append(zeroVerdict(metrics.chitchatZeroToolRate())).append("）。\n");
        md.append("5. P95=").append(metrics.p95LatencyMs()).append("ms（门槛 ≤20s，")
                .append(p95Verdict(metrics.p95LatencyMs())).append("）；P50=")
                .append(metrics.p50LatencyMs()).append("ms；平均工具数=")
                .append(fmt(metrics.avgToolCalls())).append("（门槛 ≤2.5）。\n");
        md.append("6. **引用指标**（DB + 生产 `CitationVerifier`，不是估算）：有证据的用例 ")
                .append(citations.casesWithEvidence()).append(" 条；平均证据数 ")
                .append(fmt(citations.avgEvidenceCount())).append("；平均有效引用 ")
                .append(fmt(citations.avgValidCitations())).append("；引用覆盖率 ")
                .append(citations.coverageText()).append("（门槛 ≥85%，")
                .append(coverageVerdict(citations.avgCitationCoverage())).append("）；grounded 率 ")
                .append(citations.groundedText()).append("；非法编号 ")
                .append(citations.invalidCitationsTotal()).append(" 个（去重）/ ")
                .append(citations.invalidCitationOccurrencesTotal()).append(" 次（出现次数）；")
                .append("模型未引用但证据存在 ").append(citations.casesWithEvidenceButNoCitation()).append(" 条。")
                .append("**若覆盖率异常低，如实报告，不调口径、不改提示词凑数。**\n");
        md.append("7. 回答里 `[E数字]` 共 ").append(citations.citationMarkersTotal()).append(" 次，分布在 ")
                .append(answersWithMarkers).append("/50 条回答；单条最多 ").append(maxMarkers)
                .append(" 次（`").append(maxMarkersCase).append("`）。有了证据协议后，")
                .append("编号是否真实由 `agent_citations.valid` 判定，不再靠“看起来像引用”。\n");
        md.append("8. 注入：本次共 ").append(injectionMentioned)
                .append(" 条回答主动提到「检索内容里夹带注入文本并已忽略」。这**不是防护生效的证据**——")
                .append("当前代码里没有注入防护（P1-7 才做），只是这次模型没被带偏。\n");
        md.append("9. 每用例只跑 1 次（方案里只有 Judge 才跑 2 次），模型抖动未被平均掉；")
                .append("工具调用上限 ").append(snapshotValue("app.agent.max-tool-calls"))
                .append(" 次。**P1-D1 起默认软降级**（本次 `app.agent.tool-budget-soft`=")
                .append(snapshotValue("app.agent.tool-budget-soft"))
                .append("）：第 4 次工具调用返回固定提示文本、继续作答，所以「被上限硬中止」在默认配置下不再出现。")
                .append("本次 `AGENT_TOOL_LIMIT_EXCEEDED` 命中 ").append(hardAbortHits).append(" 条")
                .append(hardAbortIds.isEmpty() ? "" : "（" + String.join("、", hardAbortIds) + "）")
                .append("——这是**硬中止命中数**，不是「触到预算的次数」；硬中止只有回滚开关 "
                        + "`tool-budget-soft=false` 才出现，默认配置下 0 条属预期。\n");
        md.append("   **补充（已知缺口）**：软降级命中次数**无法从 DB 复算**——软降级不落 "
                + "`agent_tool_calls`，`tool_call` 事件里的 `AGENT_TOOL_LIMIT_EXCEEDED` 也不落库，"
                + "只能在运行日志里搜「工具调用预算已用完（软降级，不落库）」。**不要**把「硬中止 0 条」"
                + "当成「模型没触到上限」；要把软降级命中数写进汇报，得先扩展评测的 SSE 解析（读 "
                + "`tool_call` 事件），本轮不改评测口径。\n");
        md.append("10. 隔离与互斥：只写评测专用集合 `").append(evalCollectionName)
                .append("`，真实集合 `").append(AgentEvalSeeder.REAL_COLLECTION)
                .append("` 全程只读 count（跑前=").append(isolation.realCollectionPointsBefore())
                .append("、跑后=").append(isolation.realCollectionPointsAfter())
                .append("）；业务行与向量点按 userId=").append(AgentEvalSeeder.EVAL_USER_ID)
                .append(" 清理（业务行 ").append(cleanup.rowsDeleted())
                .append(" 条、向量点 ").append(cleanup.pointsDeleted())
                .append(" 个、残留 ").append(isolation.evalRowsRemainingAfterCleanup())
                .append(" 行）；评测期间持有 PostgreSQL advisory lock，第二次并发评测算不到锁会立即失败。\n");
        Files.writeString(path, md.toString(), StandardCharsets.UTF_8);
    }

    /**
     * 读当前库的 Flyway 最新版本（报告如实写迁移状态）。
     *
     * <p>必须按 {@code installed_rank} 取最后一条，不能 {@code max(version)}——
     * {@code version} 是 varchar，字典序会把 "9" 排到 "13" 后面。
     */
    private String queryLatestFlywayVersion() {
        try {
            String version = jdbc.queryForObject("""
                    SELECT version FROM flyway_schema_history
                    WHERE success = TRUE ORDER BY installed_rank DESC LIMIT 1
                    """, String.class);
            return version == null ? "unknown" : version;
        } catch (RuntimeException e) {
            log.warn("读取 Flyway 最新版本失败（报告写 unknown，不影响评测）", e);
            return "unknown";
        }
    }

    /**
     * 历史报告对比行：自动读取 {@code docs/agent-eval/*.json} 里 {@code caseCount=50} 的报告，
     * 按生成时间排序，末尾追加本次运行。
     *
     * <p>为什么自动读而不是手抄：报告里的对比数字必须可复现、没有人工誊抄误差；
     * 10 条冒烟报告（caseCount=10）与本次不可比，直接跳过。旧报告没有证据/引用数据，
     * 相关列写 n/a（不把「P0-3 之前没有引用」当成 0）。
     */
    private static List<Map<String, Object>> comparisonRows() {
        if (comparisonRows != null) {
            return comparisonRows;
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        try {
            Path reportDir = resolveDocsDir().resolve("agent-eval");
            List<Path> files;
            try (Stream<Path> stream = Files.list(reportDir)) {
                files = stream.filter(path -> path.getFileName().toString().endsWith(".json"))
                        .filter(path -> !path.getFileName().toString()
                                .equals(REPORT_BASENAME + ".json"))
                        .toList();
            }
            for (Path file : files) {
                JsonNode root = objectMapperRef.readTree(Files.readString(file, StandardCharsets.UTF_8));
                if (root.path("caseCount").asInt() != 50) {
                    continue;
                }
                String fileName = file.getFileName().toString();
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("report", "`" + fileName.substring(0, fileName.length() - ".json".length()) + "`");
                row.put("generatedAt", root.path("generatedAt").asText("unknown"));
                row.put("passed", root.path("metrics").path("passed").asInt());
                row.put("toolPrecision", root.path("metrics").path("toolSelection").path("precision").asDouble());
                row.put("toolRecall", root.path("metrics").path("toolSelection").path("recall").asDouble());
                row.put("toolF1", root.path("metrics").path("toolSelection").path("f1").asDouble());
                row.put("refusalAccuracy", root.path("metrics").path("refusalAccuracy").asDouble());
                row.put("chitchatZeroToolRate",
                        root.path("metrics").path("chitchatZeroToolRate").asDouble());
                row.put("p95LatencyMs", root.path("metrics").path("p95LatencyMs").asLong());
                row.put("avgToolCalls", root.path("metrics").path("avgToolCalls").asDouble());
                putCitationColumns(row, root.path("metrics").path("citationMetrics"),
                        root.path("metrics").path("citationCoverage"));
                rows.add(row);
            }
            rows.sort(Comparator.comparing(row -> String.valueOf(row.get("generatedAt"))));
        } catch (Exception e) {
            log.warn("读取历史评测报告失败（对比表只显示本次，不影响本次报告与断言）", e);
        }

        EvalMetrics.Metrics metrics = metricsRef;
        Map<String, Object> current = new LinkedHashMap<>();
        current.put("report", "`" + REPORT_BASENAME + "`（本次）");
        current.put("generatedAt", OffsetDateTime.now().toString());
        current.put("passed", metrics.passed());
        current.put("toolPrecision", metrics.toolSelection().precision());
        current.put("toolRecall", metrics.toolSelection().recall());
        current.put("toolF1", metrics.toolSelection().f1());
        current.put("refusalAccuracy", metrics.refusalAccuracy());
        current.put("chitchatZeroToolRate", metrics.chitchatZeroToolRate());
        current.put("p95LatencyMs", metrics.p95LatencyMs());
        current.put("avgToolCalls", metrics.avgToolCalls());
        putCitationColumns(current, metrics.citationStats());
        rows.add(current);
        comparisonRows = List.copyOf(rows);
        return comparisonRows;
    }

    /** 从旧报告的 {@code metrics.citationMetrics} 读引用列；没有这个块（P0-3 之前）写 n/a。 */
    private static void putCitationColumns(Map<String, Object> row, JsonNode citationMetrics,
                                           JsonNode legacyCoverage) {
        if ((citationMetrics == null || !citationMetrics.isObject())) {
            row.put("casesWithEvidence", "n/a");
            row.put("avgEvidenceCount", "n/a");
            row.put("avgValidCitations", "n/a");
            row.put("invalidCitationsTotal", "n/a");
            row.put("citationCoverage", legacyCoverage == null || legacyCoverage.isMissingNode()
                    ? "n/a" : legacyCoverage.asText("n/a"));
            row.put("groundedRate", "n/a");
            return;
        }
        row.put("casesWithEvidence", citationMetrics.path("casesWithEvidence").asInt());
        row.put("avgEvidenceCount", citationMetrics.path("avgEvidenceCount").asDouble());
        row.put("avgValidCitations", citationMetrics.path("avgValidCitations").asDouble());
        row.put("invalidCitationsTotal", citationMetrics.path("invalidCitationsTotal").asInt());
        row.put("citationCoverage", valueOrNa(citationMetrics.path("citationCoverage")));
        row.put("groundedRate", valueOrNa(citationMetrics.path("groundedRate")));
    }

    private static void putCitationColumns(Map<String, Object> row, EvalMetrics.CitationStats stats) {
        row.put("casesWithEvidence", stats.casesWithEvidence());
        row.put("avgEvidenceCount", stats.avgEvidenceCount());
        row.put("avgValidCitations", stats.avgValidCitations());
        row.put("invalidCitationsTotal", stats.invalidCitationsTotal());
        row.put("citationCoverage", stats.coverageText());
        row.put("groundedRate", stats.groundedText());
    }

    private static Object valueOrNa(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return "n/a";
        }
        return node.isTextual() ? node.asText() : node.asDouble();
    }

    private static Map<String, Integer> categoryCounts(AgentEvalCases.AgentEvalDataset dataset) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String category : CATEGORY_ORDER) {
            counts.put(category, 0);
        }
        for (AgentEvalCase evalCase : dataset.cases()) {
            String category = evalCase.tags().isEmpty() ? "unknown" : evalCase.tags().get(0);
            counts.merge(category, 1, Integer::sum);
        }
        return counts;
    }

    /** 用例分类：cases.yaml 每条的第一个 tag（构造时已按分类排好）。 */
    private static String categoryOf(String caseId) {
        if (datasetRef == null) {
            return "unknown";
        }
        AgentEvalCase evalCase = datasetRef.cases().stream()
                .filter(item -> item.id().equals(caseId))
                .findFirst()
                .orElse(null);
        return evalCase == null || evalCase.tags().isEmpty() ? "unknown" : evalCase.tags().get(0);
    }

    private static void assertDistribution(AgentEvalCases.AgentEvalDataset dataset) {
        Map<String, Integer> expected = new LinkedHashMap<>();
        expected.put("single-hop", 15);
        expected.put("multi-hop", 8);
        expected.put("mindmap", 6);
        expected.put("question", 5);
        expected.put("memory", 5);
        expected.put("no-evidence", 6);
        expected.put("chitchat", 3);
        expected.put("injection", 2);
        assertThat(categoryCounts(dataset))
                .as("50 条用例分布必须与方案 §4 P0-4 一致（15/8/6/5/5/6/3/2）")
                .containsExactlyInAnyOrderEntriesOf(expected);
    }

    /** 失败归因：infrastructure / detector / model（一条用例可能有多个）。 */
    private static List<String> failureCategories(AgentEvalRunner.CaseResult result) {
        if (result.passed()) {
            return List.of();
        }
        Set<String> categories = new LinkedHashSet<>();
        for (String reason : result.failureReasons()) {
            if (isInfrastructureFailure(reason)) {
                categories.add("infrastructure");
            } else if (reason.contains("拒答")) {
                categories.add("detector");
            } else {
                categories.add("model");
            }
        }
        return List.copyOf(categories);
    }

    private static boolean isInfrastructureFailure(String reason) {
        if (reason.contains("AGENT_TOOL_LIMIT_EXCEEDED")) {
            // P1-F ①：P1-D1 起默认软降级，这个错误码只会在 tool-budget-soft=false（硬中止回滚）时
            // 出现；它仍然是模型/工具预算问题，不是基础设施故障（基线 base-019/020 已实测）。
            // 判定逻辑未放宽：这里只是继续把它排除在 infra 之外，没有新增/删除任何分类依据。
            return false;
        }
        return reason.startsWith("用例超时")
                || reason.startsWith("执行异常")
                || reason.startsWith("评测被中断")
                || reason.startsWith("空回答")
                || reason.startsWith("SSE error")
                || reason.startsWith("agent_runs 无对应记录");
    }

    private static String f1Verdict(double f1) {
        return f1 >= 0.85d ? "达标" : "**未达标**";
    }

    private static String zeroVerdict(double rate) {
        return rate >= 1.0d ? "达标" : "**未达标**";
    }

    private static String p95Verdict(long p95) {
        return p95 <= 20_000L ? "达标" : "**未达标**";
    }

    /** 引用覆盖率门槛 ≥85%（方案 P0-3）；n/a 时不判达标与否，如实写不适用。 */
    private static String coverageVerdict(Double coverage) {
        if (coverage == null) {
            return "n/a：本次没有有证据的用例";
        }
        return coverage >= 0.85d ? "达标" : "**未达标（如实报告，不调口径）**";
    }

    private static String escapeCell(String text) {
        return text == null ? "" : text.replace("|", "\\|").replace("\n", " ");
    }

    private static String snippet(String text, int maxChars) {
        if (text == null || text.isBlank()) {
            return "（空）";
        }
        String oneLine = text.replaceAll("\\s+", " ").trim();
        return oneLine.length() > maxChars ? oneLine.substring(0, maxChars) + "…" : oneLine;
    }

    private static String fmt(double value) {
        return String.format("%.4f", value);
    }

    /** 读开关快照里的值（缺失/未采集时写占位符，不猜默认值）。 */
    private static String snapshotValue(String key) {
        String value = configSnapshot.get(key);
        return value == null || value.isBlank() ? EvalConfigSnapshot.MISSING : value;
    }

    /** 读开关快照里的整数值（给 JSON 用；不是整数时退回原文，不抛异常、不影响评测）。 */
    private static Object snapshotNumber(String key) {
        String value = snapshotValue(key);
        if (EvalConfigSnapshot.MISSING.equals(value)) {
            return value;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            log.warn("开关快照值不是整数 key={} value={}（报告按原文写）", key, value);
            return value;
        }
    }

    private static String nullable(Integer value) {
        return value == null ? "null" : String.valueOf(value);
    }

    /** 报告目录：优先 cwd/docs，其次上级目录/docs（surefire 工作目录是 mindmap-backend）。 */
    private static Path resolveDocsDir() {
        Path cwd = Path.of("").toAbsolutePath();
        Path candidate = cwd.resolve("docs");
        if (Files.isDirectory(candidate)) {
            return candidate;
        }
        Path parent = cwd.getParent();
        if (parent != null && Files.isDirectory(parent.resolve("docs"))) {
            return parent.resolve("docs");
        }
        throw new IllegalStateException("找不到 docs 目录，cwd=" + cwd);
    }
}
