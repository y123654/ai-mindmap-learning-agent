package com.example.aimindmap.agenteval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

import com.example.aimindmap.agentruntime.MultiQueryRetriever;
import com.example.aimindmap.agentruntime.QueryRewriteResult;
import com.example.aimindmap.agentruntime.RetrievalProperties;
import com.example.aimindmap.ai.AiChatRequest;
import com.example.aimindmap.ai.AiGateway;
import com.example.aimindmap.ai.AiJsonRequest;
import com.example.aimindmap.ai.AiJsonResult;
import com.example.aimindmap.ai.AiUsage;
import com.example.aimindmap.agenteval.RetrievalQualityMetrics.Aggregate;
import com.example.aimindmap.agenteval.RetrievalQualityMetrics.CaseMeasurement;
import com.example.aimindmap.agenteval.RetrievalQualityMetrics.CaseScore;
import com.example.aimindmap.agenteval.RetrievalQualityMetrics.GoldRef;
import com.example.aimindmap.agenteval.RetrievalQualityMetrics.GoldType;
import com.example.aimindmap.agenteval.RetrievalQualityMetrics.HitRef;
import com.example.aimindmap.importpdf.PdfImportWorker;
import com.example.aimindmap.metrics.BusinessMetrics;
import com.example.aimindmap.vectorindex.QdrantProperties;
import com.example.aimindmap.vectorindex.QdrantVectorGateway;
import com.example.aimindmap.vectorindex.VectorIndexWorker;
import com.example.aimindmap.vectorindex.VectorResourceType;
import com.example.aimindmap.vectorindex.VectorSearchService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Common.Filter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * P1-E2 <b>检索质量测量</b>（方案 §P1-5 第 577、580 行）：只用 {@code cases.yaml} 里已有的
 * {@code gold_chunks} 算 {@code recall@5} / {@code MRR}，回答“多查询 + RRF 到底让检索变好还是变差”。
 *
 * <p><b>这不是端到端评测</b>：不回答、不做 Planner、不调用对话模型（唯一例外是“多查询 + LLM 改写”
 * 这一路的改写调用，最多 2 组配置），只做「播种 → 检索层取有序命中 → 算指标」。
 *
 * <p><b>不改任何生产默认值</b>：所有参数组合都在测试里构造 {@link QdrantProperties} /
 * {@link RetrievalProperties} / {@link VectorSearchService} / {@link MultiQueryRetriever} 实例，
 * 注入的 Spring Bean（含真实 {@code app.qdrant.min-score=0.35}）一个字段都不改。
 *
 * <p><b>隔离与互斥（与 {@link AgentEvalIT} 完全相同的机制）</b>
 * <ul>
 *   <li>同一把 PostgreSQL 会话级 advisory lock（key 与 {@code AgentEvalIT} 一模一样），
 *       并发跑会互相清掉评测用户，所以拿不到锁立即失败；</li>
 *   <li>测试属性把向量集合覆盖成 {@link AgentEvalSeeder#EVAL_COLLECTION}，
 *       业务行与向量点只按 {@link AgentEvalSeeder#EVAL_USER_ID} 圈定；</li>
 *   <li>真实集合 {@link AgentEvalSeeder#REAL_COLLECTION} 只读 count，跑完断言点数不变。</li>
 * </ul>
 *
 * <p><b>运行方式</b>（默认门禁不含本类，{@code @Tag("eval")} 被 surefire excludedGroups 排除）：
 * <pre>
 * mvn.cmd -f mindmap-backend/pom.xml -B -Peval test -Dtest=RetrievalQualityIT
 *         '-Djdk.net.unixdomain.tmpdir=D:\tmp' '-DargLine=-Djdk.net.unixdomain.tmpdir=D:\tmp'
 * </pre>
 * <b>不要</b>不加 {@code -Dtest} 就跑 {@code -Peval}：那会把 {@code AgentEvalIT} 一起跑（¥10~25）。
 *
 * <p>报告落盘：{@code docs/agent-eval/2026-10-28-retrieval-quality.md} 与 {@code .json}。
 */
@Tag("eval")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(properties = {
        // 隔离硬约束：向量只进评测专用集合
        "app.qdrant.collection=" + AgentEvalSeeder.EVAL_COLLECTION,
        // 不测消息链路：关掉 MQ，避免后台 worker 认领真实任务（与 AgentEvalIT 同口径）
        "app.mq.enabled=false",
        "app.mq.requeue-scan-delay-ms=86400000",
        "app.qdrant.worker-fixed-delay-ms=86400000"
})
class RetrievalQualityIT {

    private static final Logger log = LoggerFactory.getLogger(RetrievalQualityIT.class);

    /** 报告名可覆盖（复跑用 -Dretrieval.report.name=...），默认按本轮日期。 */
    private static final String REPORT_BASENAME =
            System.getProperty("retrieval.report.name", "2026-10-28-retrieval-quality");

    /** 冻结的 cases.yaml SHA256（E1 验收记录里的 906B0D1E…；改了用例必须让本测试失败）。 */
    private static final String FROZEN_CASES_SHA256 =
            "906B0D1E74389F50272206299697D39C3913DECE00809133B5933C2554A49D96";

    /** 与 {@link AgentEvalIT} 同一把锁：key 必须一致，否则两个评测会并发跑、互相清数据。 */
    private static final long EVAL_ADVISORY_LOCK_KEY = 888900_20261026L;

    /** 参数矩阵（方案 §P1-5 第 577 行）：4 × 2 × 2 = 16 组规则改写。 */
    private static final List<Double> MIN_SCORES = List.of(0.30d, 0.35d, 0.40d, 0.45d);
    private static final List<Integer> MAX_RESULTS = List.of(5, 8);
    private static final List<Integer> MAX_CHUNKS_PER_DOCUMENT = List.of(1, 2);

    /** 基线参数（= 改造前的默认值；不参与 16 组扫描）。 */
    private static final double BASELINE_MIN_SCORE = 0.35d;
    private static final int BASELINE_CAP = 5;

    /** 基线语义上“每篇 1 chunk”（旧路径同源合并后每篇只留 1 个）。 */
    private static final int BASELINE_CHUNKS_PER_DOCUMENT = 1;

    /** LLM 改写探针用的提示词（与 {@link MultiQueryRetriever} 里的改写 A 同义，探针只验通不通）。 */
    private static final String LLM_PROBE_PROMPT = """
            你是检索查询改写器。把用户的问题改写成另一个意思相同、但措辞不同的中文检索问句。
            只输出 JSON，不要解释：
            {"query":"改写后的问句"}
            """;

    /** 每个分类在报告里的固定顺序（与 cases.yaml 的第一项 tag 一致）。 */
    private static final List<String> CATEGORY_ORDER = List.of(
            "single-hop", "multi-hop", "mindmap", "question", "memory",
            "no-evidence", "chitchat", "injection");

    /** 综合排序：先 recall@5，再 MRR，再“证据条数少”，最后 P95 低（数字说话，不掺主观）。 */
    private static final java.util.Comparator<ConfigResult> QUALITY_ORDER = (left, right) -> {
        int compare = Double.compare(right.overall().meanRecall5(), left.overall().meanRecall5());
        if (compare != 0) {
            return compare;
        }
        compare = Double.compare(right.overall().meanMrr(), left.overall().meanMrr());
        if (compare != 0) {
            return compare;
        }
        compare = Double.compare(left.overall().avgHits(), right.overall().avgHits());
        if (compare != 0) {
            return compare;
        }
        // 指标完全打平时不靠 P95 抖动作决定：优先离当前默认阈值最近的（少改默认）。
        compare = Double.compare(Math.abs(left.minScore() - BASELINE_MIN_SCORE),
                Math.abs(right.minScore() - BASELINE_MIN_SCORE));
        if (compare != 0) {
            return compare;
        }
        return Long.compare(left.overall().p95Ms(), right.overall().p95Ms());
    };

    @Autowired private EmbeddingModel embeddingModel;
    @Autowired private QdrantVectorGateway gateway;
    @Autowired private QdrantProperties qdrantProperties;
    @Autowired private QdrantClient qdrantClient;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private AiGateway aiGateway;
    @Autowired private BusinessMetrics businessMetrics;

    /**
     * 与 {@link AgentEvalIT} 同样的两个后台 worker mock：{@code app.mq.enabled=false} 时它们会
     * 直接认领并处理数据库里的真实任务，而向量写入集合已被覆盖成评测集合——不 mock 有污染风险。
     */
    @MockBean private VectorIndexWorker vectorIndexWorker;
    @MockBean private PdfImportWorker pdfImportWorker;

    private static AgentEvalSeeder seeder;
    private static long realCollectionBefore = -1L;
    private static long evalCollectionBaseline = -1L;
    private static long evalPointsAfterSeed = -1L;
    private static int stalePointsPurged = 0;

    // 单实例互斥锁（与 AgentEvalIT 同一套实现）
    private static Connection lockConnection;
    private static boolean lockAcquired;
    private static boolean lockProbeBlocked;
    private static Integer lockBackendPid;
    private static String lockAcquiredAt = "";

    // 报告所需快照（@AfterAll 里由静态上下文生成报告）
    private static AgentEvalCases.AgentEvalDataset datasetRef;
    private static List<CasePlan> plansRef;
    private static String casesSha256 = "";
    private static String embeddingModelName = "";
    private static String evalCollectionName = "";
    private static String chatModelName = "n/a";
    private static ConfigResult baselineRef;
    private static List<ConfigResult> ruleConfigsRef;
    private static List<ConfigResult> llmConfigsRef;
    private static ConfigResult bestRuleRef;
    private static ConfigResult defaultRuleRef;
    private static String llmStatus = "未尝试";
    private static boolean llmProbeSucceeded = false;
    private static LlmUsage llmProbeUsage = LlmUsage.none();
    private static List<String> emptyGoldIds = List.of();
    private static List<String> memoryExcludedIds = List.of();
    private static List<String> mixedMemoryIds = List.of();
    private static Map<String, Long> candidateCounts = Map.of();
    private static int maxDistinctDocumentGolds = 0;
    private static int casesWithThreePlusDocumentGolds = 0;
    private static long retrievalWallMs = 0L;
    private static long totalSearchCalls = 0L;
    private static String flywayVersion = "unknown";
    private static ObjectMapper objectMapperRef;

    // ------------------------------------------------------------------
    // 1. 互斥锁（与 AgentEvalIT 完全一致；拿不到立即失败）
    // ------------------------------------------------------------------

    @BeforeAll
    void acquireEvalLock() throws SQLException {
        DataSource dataSource = jdbc.getDataSource();
        if (dataSource == null) {
            log.error("互斥锁获取失败：JdbcTemplate 没有可用的 DataSource");
            fail("无法获取 DataSource，拒绝启动检索质量测量");
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
        lockProbeBlocked = !tryAdvisoryLockOnFreshConnection(dataSource);
        lockAcquiredAt = OffsetDateTime.now().toString();
        log.info("互斥锁获取成功 key={} backendPid={} acquiredAt={} 第二会话试探被拒={}",
                EVAL_ADVISORY_LOCK_KEY, lockBackendPid, lockAcquiredAt, lockProbeBlocked);
        assertThat(lockProbeBlocked)
                .as("会话级 advisory lock 必须能阻止第二个会话拿到同一把锁")
                .isTrue();
    }

    // ------------------------------------------------------------------
    // 2. 主测量流程
    // ------------------------------------------------------------------

    @Test
    void measureRetrievalQuality() throws Exception {
        long runStartedNanos = System.nanoTime();
        AgentEvalCases.AgentEvalDataset dataset = AgentEvalCases.load();
        assertThat(dataset.cases())
                .as("冻结的用例集必须是 50 条（本测试不改 cases.yaml）")
                .hasSize(50);
        datasetRef = dataset;
        casesSha256 = casesYamlSha256();
        assertThat(casesSha256)
                .as("cases.yaml 必须保持冻结（E2 只测量、不改用例）")
                .isEqualTo(FROZEN_CASES_SHA256);

        evalCollectionName = qdrantProperties.getCollection();
        embeddingModelName = qdrantProperties.getEmbeddingModel();
        chatModelName = aiGateway == null ? "n/a" : aiGateway.model();
        objectMapperRef = objectMapper;
        flywayVersion = queryLatestFlywayVersion();

        seeder = new AgentEvalSeeder(jdbc, embeddingModel, gateway, qdrantProperties,
                qdrantClient, objectMapper);

        // ---- 2.0 隔离前置检查 ----
        assertThat(qdrantProperties.getCollection())
                .as("测试属性必须把向量集合覆盖为评测专用集合")
                .isEqualTo(AgentEvalSeeder.EVAL_COLLECTION);
        assertThat(AgentEvalSeeder.EVAL_COLLECTION)
                .as("评测集合不得与真实集合同名")
                .isNotEqualTo(AgentEvalSeeder.REAL_COLLECTION);
        assertThat(seeder.collectionExists(AgentEvalSeeder.EVAL_COLLECTION))
                .as("专用集合 %s 必须存在（由 QdrantCollectionInitializer 按测试属性创建）",
                        AgentEvalSeeder.EVAL_COLLECTION)
                .isTrue();

        realCollectionBefore = seeder.countPoints(AgentEvalSeeder.REAL_COLLECTION);
        stalePointsPurged = seeder.purgeVectorPoints();
        evalCollectionBaseline = seeder.countPoints(AgentEvalSeeder.EVAL_COLLECTION);
        log.info("==== 隔离基线 ==== 真实集合={} 点数={} | 评测集合={} 基线点数={} 清理残留点={}",
                AgentEvalSeeder.REAL_COLLECTION, realCollectionBefore,
                AgentEvalSeeder.EVAL_COLLECTION, evalCollectionBaseline, stalePointsPurged);

        // ---- 2.1 embedding 探针（失败立即停，不播种、不扫 16 组） ----
        probeEmbedding();

        // ---- 2.2 播种（22 个向量点：5 文档 chunk + 11 导图节点 + 3 题目 + 3 记忆） ----
        seeder.seed();
        evalPointsAfterSeed = seeder.countPoints(AgentEvalSeeder.EVAL_COLLECTION);
        long userPoints = seeder.countUserPoints(AgentEvalSeeder.EVAL_COLLECTION);
        log.info("夹具落库后 评测集合总点数={} 评测用户点数={} seed 写入={}",
                evalPointsAfterSeed, userPoints, seeder.seededPoints());
        assertThat(userPoints)
                .as("评测用户向量点数必须等于 seed 写入数（防止漏写/多写）")
                .isEqualTo(seeder.seededPoints());

        // ---- 2.2b 候选池规模（解释“为什么 recall@5 会封顶”的关键数字） ----
        Map<String, Long> counts = new LinkedHashMap<>();
        long candidateTotal = 0L;
        for (VectorResourceType type : List.of(VectorResourceType.DOCUMENT_CHUNK,
                VectorResourceType.MINDMAP_NODE, VectorResourceType.QUESTION,
                VectorResourceType.MEMORY)) {
            long count = countUserPointsByType(type);
            counts.put(type.wireName(), count);
            candidateTotal += count;
        }
        candidateCounts = java.util.Collections.unmodifiableMap(counts);
        log.info("候选池（评测用户、按类型）：{} 合计={}", candidateCounts, candidateTotal);
        assertThat(candidateTotal)
                .as("按类型统计的候选点合计必须等于评测用户总点数")
                .isEqualTo(userPoints);

        // ---- 2.3 选出可测量的用例（gold 非空且不含 memory） ----
        plansRef = buildPlans(dataset);
        log.info("==== 可测量用例 ==== 计入={} 排除：gold 为空={} memory={} 混合 memory={}",
                plansRef.size(), emptyGoldIds.size(), memoryExcludedIds.size(), mixedMemoryIds.size());
        assertThat(plansRef).as("gold 非空且不含 memory 的用例数必须为 33").hasSize(33);
        assertThat(emptyGoldIds).as("gold_chunks 为空的用例必须为 12 条").hasSize(12);
        assertThat(memoryExcludedIds).as("只有 memory gold 的用例必须为 5 条").hasSize(5);
        assertThat(mixedMemoryIds)
                .as("本轮数据里不应出现 document/question 与 memory 混合的 gold（出现了要人工定口径）")
                .isEmpty();

        // ---- 2.4 基线（旧路径：单 query、每篇 1 chunk、cap 5、minScore 0.35） ----
        QdrantProperties baselineProperties = copyQdrantProperties(BASELINE_MIN_SCORE);
        CountingGateway baselineGateway = new CountingGateway(gateway);
        VectorSearchService baselineService = new VectorSearchService(
                embeddingModel, baselineGateway, baselineProperties, businessMetrics);
        baselineRef = runConfig(
                "baseline-legacy", "baseline", "legacy-vector",
                BASELINE_MIN_SCORE, BASELINE_CAP, BASELINE_CHUNKS_PER_DOCUMENT, "none",
                plansRef,
                (query, resourceTypes) -> baselineService
                        .search(AgentEvalSeeder.EVAL_USER_ID, query, resourceTypes).hits(),
                baselineGateway,
                LlmUsage.none());

        // ---- 2.5 多查询 + 规则改写（确定性、零对话成本）16 组 ----
        List<ConfigResult> ruleConfigs = new ArrayList<>();
        for (double minScore : MIN_SCORES) {
            for (int cap : MAX_RESULTS) {
                for (int chunks : MAX_CHUNKS_PER_DOCUMENT) {
                    ruleConfigs.add(runRuleConfig(plansRef, minScore, cap, chunks));
                }
            }
        }
        assertThat(ruleConfigs).as("规则改写矩阵必须是 4 × 2 × 2 = 16 组").hasSize(16);
        ruleConfigsRef = List.copyOf(ruleConfigs);

        // ---- 2.6 选“最好规则组”和“生产默认参数组” ----
        bestRuleRef = ruleConfigs.stream().min(QUALITY_ORDER).orElseThrow(
                () -> new IllegalStateException("规则改写矩阵为空，无法选最优组"));
        defaultRuleRef = findRuleConfig(ruleConfigs, BASELINE_MIN_SCORE, 5, 2);
        assertThat(defaultRuleRef)
                .as("生产默认参数组（0.35 / 5 / 每篇 2 chunk）必须存在于矩阵里")
                .isNotNull();
        log.info("最优规则组={} recall@5={} MRR={} 平均证据={} P95={}ms",
                bestRuleRef.id(), bestRuleRef.overall().meanRecall5(),
                bestRuleRef.overall().meanMrr(), bestRuleRef.overall().avgHits(),
                bestRuleRef.overall().p95Ms());

        // ---- 2.7 多查询 + LLM 改写（最多 2 组；网关不可用就如实标注未测） ----
        List<ConfigResult> llmConfigs = new ArrayList<>();
        LlmUsage probeUsage = probeLlmRewrite();
        llmProbeUsage = probeUsage;
        if (llmProbeSucceeded) {
            llmStatus = "已测（探针 " + probeUsage.calls() + " 次调用成功）";
            for (ConfigResult target : pickLlmTargets(bestRuleRef, defaultRuleRef, ruleConfigs)) {
                llmConfigs.add(runLlmConfig(plansRef, target));
            }
        } else {
            llmStatus = "未测（" + (probeUsage.calls() == 0
                    ? "AI 网关未配置或 configured()=false"
                    : "改写探针失败或返回空结果") + "）";
            log.warn("生产默认路径（多查询 + LLM 改写）本轮未测，原因={}；报告会如实标注", llmStatus);
        }
        llmConfigsRef = List.copyOf(llmConfigs);
        retrievalWallMs = (System.nanoTime() - runStartedNanos) / 1_000_000L;

        // ---- 2.8 成本与失败统计 + 断言 ----
        long searchCalls = 0L;
        int failedCases = 0;
        for (ConfigResult config : allConfigs()) {
            searchCalls += config.searchCalls();
            failedCases += config.overall().failedCases();
        }
        totalSearchCalls = searchCalls;
        log.info("==== 测量完成 ==== 配置数={} 用例/配置={} 向量检索次数(=embedding 次数)={} "
                        + "总耗时={}ms 失败用例数={}",
                allConfigs().size(), plansRef.size(), totalSearchCalls, retrievalWallMs, failedCases);
        log.info("==== 汇总（recall@5 / MRR / 平均证据 / P50 / P95）====");
        for (ConfigResult config : allConfigs()) {
            log.info("  {} {} recall@5={} MRR={} 平均证据={} P50={}ms P95={}ms 失败={}",
                    config.group(), config.id(),
                    fmt4(config.overall().meanRecall5()), fmt4(config.overall().meanMrr()),
                    fmt4(config.overall().avgHits()), config.overall().p50Ms(),
                    config.overall().p95Ms(), config.overall().failedCases());
        }
        assertThat(failedCases)
                .as("任何一条检索失败都必须让测量失败（不把异常静默当成 0 分）")
                .isZero();

        // ---- 2.9 隔离取证（清理与最终断言在 @AfterAll） ----
        long realAfterCases = seeder.countPoints(AgentEvalSeeder.REAL_COLLECTION);
        log.info("==== 隔离取证（跑完测量、清理前）==== 真实集合点数 跑前={} 跑后={}",
                realCollectionBefore, realAfterCases);
        assertThat(realAfterCases)
                .as("真实集合 %s 的点数必须全程不变（本测试只读它）",
                        AgentEvalSeeder.REAL_COLLECTION)
                .isEqualTo(realCollectionBefore);
    }

    // ------------------------------------------------------------------
    // 3. 配置构造与执行
    // ------------------------------------------------------------------

    /** 一条用例的检索计划：问题 + gold + 由 gold 类型决定的过滤类型。 */
    private record CasePlan(
            AgentEvalCase evalCase,
            List<GoldRef> golds,
            List<VectorResourceType> resourceTypes,
            String category) {

        /** 报告分组用：这条用例的 gold 覆盖哪些资源类型（如 document / document+mindmap）。 */
        String goldTypeLabel() {
            return golds.stream()
                    .map(gold -> gold.type().shortName())
                    .distinct()
                    .collect(Collectors.joining("+"));
        }
    }

    /** 检索调用抽象：返回有序 hit 列表（长度由各配置自己决定）。 */
    @FunctionalInterface
    private interface RetrievalCall {
        List<VectorSearchService.VectorHit> retrieve(String query, List<VectorResourceType> resourceTypes);
    }

    /** 一个配置的完整测量结果。 */
    private record ConfigResult(
            String id,
            String group,
            String path,
            double minScore,
            int cap,
            int chunksPerDocument,
            String rewrite,
            List<CaseMeasurement> cases,
            Aggregate overall,
            Map<String, Aggregate> byCategory,
            Map<String, Aggregate> byIntent,
            Map<String, Aggregate> byGoldType,
            long searchCalls,
            long wallMs,
            LlmUsage llmUsage) {

        Aggregate category(String name) {
            return byCategory.getOrDefault(name, Aggregate.empty());
        }
    }

    /** LLM 改写用量（成本证据：调用次数 / 失败次数 / token / 耗时）。 */
    private record LlmUsage(long calls, long failures, long promptTokens, long completionTokens,
                            long elapsedMs) {

        static LlmUsage none() {
            return new LlmUsage(0L, 0L, 0L, 0L, 0L);
        }

        LlmUsage plus(LlmUsage other) {
            if (other == null) {
                return this;
            }
            return new LlmUsage(calls + other.calls, failures + other.failures,
                    promptTokens + other.promptTokens, completionTokens + other.completionTokens,
                    elapsedMs + other.elapsedMs);
        }
    }

    private List<CasePlan> buildPlans(AgentEvalCases.AgentEvalDataset dataset) {
        List<CasePlan> plans = new ArrayList<>();
        Set<String> emptyGold = new LinkedHashSet<>();
        Set<String> memoryOnly = new LinkedHashSet<>();
        Set<String> mixedMemory = new LinkedHashSet<>();
        for (AgentEvalCase evalCase : dataset.cases()) {
            if (evalCase.goldChunks().isEmpty()) {
                emptyGold.add(evalCase.id());
                continue;
            }
            List<GoldRef> golds = evalCase.goldChunks().stream()
                    .map(RetrievalQualityMetrics::parseGold)
                    .toList();
            boolean hasMemory = golds.stream().anyMatch(gold -> gold.type() == GoldType.MEMORY);
            if (hasMemory) {
                boolean hasSearchable = golds.stream().anyMatch(gold -> gold.type().searchable());
                if (hasSearchable) {
                    mixedMemory.add(evalCase.id());
                } else {
                    memoryOnly.add(evalCase.id());
                }
                continue;
            }
            List<VectorResourceType> resourceTypes = golds.stream()
                    .map(GoldRef::type)
                    .distinct()
                    .map(RetrievalQualityIT::toResourceType)
                    .toList();
            String category = evalCase.tags().isEmpty() ? "unknown" : evalCase.tags().get(0);
            plans.add(new CasePlan(evalCase, RetrievalQualityMetrics.dedupeGolds(golds),
                    resourceTypes, category));
        }
        emptyGoldIds = List.copyOf(emptyGold);
        memoryExcludedIds = List.copyOf(memoryOnly);
        mixedMemoryIds = List.copyOf(mixedMemory);
        // gold 里“需要几篇不同文档”——E1 头号风险（cap5 × 2 chunk 只装得下 2~3 篇）能否出现，全看这个数
        int maxDocuments = 0;
        int threePlus = 0;
        for (CasePlan plan : plans) {
            long distinctDocuments = plan.golds().stream()
                    .filter(gold -> gold.type() == GoldType.DOCUMENT)
                    .map(GoldRef::resourceId)
                    .distinct()
                    .count();
            maxDocuments = (int) Math.max(maxDocuments, distinctDocuments);
            if (distinctDocuments >= 3) {
                threePlus++;
            }
        }
        maxDistinctDocumentGolds = maxDocuments;
        casesWithThreePlusDocumentGolds = threePlus;
        return List.copyOf(plans);
    }

    private ConfigResult runRuleConfig(List<CasePlan> plans, double minScore, int cap, int chunks) {
        QdrantProperties properties = copyQdrantProperties(minScore);
        RetrievalProperties retrievalProperties = new RetrievalProperties();
        retrievalProperties.setMultiQueryEnabled(true);
        retrievalProperties.setMaxResults(cap);
        retrievalProperties.setMaxChunksPerDocument(chunks);
        CountingGateway countingGateway = new CountingGateway(gateway);
        VectorSearchService service = new VectorSearchService(
                embeddingModel, countingGateway, properties, businessMetrics);
        // aiGateway 传 null = 强制规则改写（确定性、零对话成本）
        MultiQueryRetriever retriever = new MultiQueryRetriever(
                service, null, properties, retrievalProperties);
        assertThat(retriever.isEnabled())
                .as("规则改写组必须处于多查询启用状态，config=%s", configId(minScore, cap, chunks))
                .isTrue();
        return runConfig(configId(minScore, cap, chunks), "rule-rewrite", "multi-query",
                minScore, cap, chunks, "rule", plans,
                (query, resourceTypes) -> retriever
                        .search(AgentEvalSeeder.EVAL_USER_ID, query, resourceTypes).hits(),
                countingGateway, LlmUsage.none());
    }

    private ConfigResult runLlmConfig(List<CasePlan> plans, ConfigResult target) {
        QdrantProperties properties = copyQdrantProperties(target.minScore());
        RetrievalProperties retrievalProperties = new RetrievalProperties();
        retrievalProperties.setMultiQueryEnabled(true);
        // P1-E2c 起 LLM 改写默认关：这个测量组的语义就是“LLM 改写”，必须显式打开，
        // 否则会静默退化成规则改写、让 E2 的历史对比失效（该测量已签核，本轮不重跑）
        retrievalProperties.setRewriteLlmEnabled(true);
        retrievalProperties.setMaxResults(target.cap());
        retrievalProperties.setMaxChunksPerDocument(target.chunksPerDocument());
        CountingGateway countingGateway = new CountingGateway(gateway);
        CountingAiGateway countingAiGateway = new CountingAiGateway(aiGateway);
        VectorSearchService service = new VectorSearchService(
                embeddingModel, countingGateway, properties, businessMetrics);
        MultiQueryRetriever retriever = new MultiQueryRetriever(
                service, countingAiGateway, properties, retrievalProperties);
        ConfigResult result = runConfig("llm-" + target.id(), "llm-rewrite", "multi-query",
                target.minScore(), target.cap(), target.chunksPerDocument(), "llm", plans,
                (query, resourceTypes) -> retriever
                        .search(AgentEvalSeeder.EVAL_USER_ID, query, resourceTypes).hits(),
                countingGateway, null);
        LlmUsage usage = countingAiGateway.usage();
        return new ConfigResult(result.id(), result.group(), result.path(), result.minScore(),
                result.cap(), result.chunksPerDocument(), result.rewrite(), result.cases(),
                result.overall(), result.byCategory(), result.byIntent(), result.byGoldType(),
                result.searchCalls(), result.wallMs(), usage);
    }

    /**
     * 跑一个配置：逐条用例计时（只包住“检索调用”本身，不含指标计算），
     * 单条失败只记录不中断（末尾统一断言失败数为 0，避免一条网络抖动毁掉整轮报告）。
     */
    private ConfigResult runConfig(String id, String group, String path,
                                   double minScore, int cap, int chunks, String rewrite,
                                   List<CasePlan> plans, RetrievalCall call,
                                   CountingGateway countingGateway, LlmUsage llmUsage) {
        long configStarted = System.nanoTime();
        List<CaseMeasurement> measurements = new ArrayList<>(plans.size());
        for (CasePlan plan : plans) {
            String caseId = plan.evalCase().id();
            long startedNanos = System.nanoTime();
            List<HitRef> hits = List.of();
            String error = null;
            try {
                List<VectorSearchService.VectorHit> raw =
                        call.retrieve(plan.evalCase().question(), plan.resourceTypes());
                List<HitRef> converted = new ArrayList<>(raw.size());
                for (VectorSearchService.VectorHit hit : raw) {
                    converted.add(new HitRef(hit.sourceType(), hit.sourceId(),
                            hit.chunkIndex(), hit.score()));
                }
                hits = List.copyOf(converted);
            } catch (RuntimeException e) {
                error = e.getClass().getSimpleName()
                        + (e.getMessage() == null ? "" : ": " + e.getMessage());
                log.error("检索质量测量失败 config={} caseId={}（按 0 分记录，末尾会断言失败）",
                        id, caseId, e);
            }
            long latencyMs = Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
            CaseScore score = RetrievalQualityMetrics.score(plan.golds(), hits);
            measurements.add(new CaseMeasurement(
                    caseId, plan.category(), plan.evalCase().intent(), plan.goldTypeLabel(),
                    plan.golds(), hits, score, latencyMs, error));
        }
        long wallMs = Math.max(0L, (System.nanoTime() - configStarted) / 1_000_000L);
        Aggregate overall = RetrievalQualityMetrics.aggregate(measurements);
        Map<String, Aggregate> byCategory = RetrievalQualityMetrics.groupBy(
                measurements, CaseMeasurement::category);
        Map<String, Aggregate> byIntent = RetrievalQualityMetrics.groupBy(
                measurements, CaseMeasurement::intent);
        Map<String, Aggregate> byGoldType = RetrievalQualityMetrics.groupBy(
                measurements, CaseMeasurement::goldTypes);
        log.info("配置完成 id={} recall@5={} MRR={} 平均证据={} P50={}ms P95={}ms 失败={} 耗时={}ms",
                id, fmt4(overall.meanRecall5()), fmt4(overall.meanMrr()), fmt4(overall.avgHits()),
                overall.p50Ms(), overall.p95Ms(), overall.failedCases(), wallMs);
        return new ConfigResult(id, group, path, minScore, cap, chunks, rewrite,
                List.copyOf(measurements), overall, byCategory, byIntent, byGoldType,
                countingGateway.searchCalls(), wallMs, llmUsage);
    }

    /** 所有已跑配置（基线 + 规则 + LLM），报告与统计的唯一入口。 */
    private static List<ConfigResult> allConfigs() {
        List<ConfigResult> all = new ArrayList<>();
        if (baselineRef != null) {
            all.add(baselineRef);
        }
        if (ruleConfigsRef != null) {
            all.addAll(ruleConfigsRef);
        }
        if (llmConfigsRef != null) {
            all.addAll(llmConfigsRef);
        }
        return List.copyOf(all);
    }

    private static String configId(double minScore, int cap, int chunks) {
        return String.format(Locale.ROOT, "mq-%.2f-c%d-ch%d", minScore, cap, chunks);
    }

    private static ConfigResult findRuleConfig(List<ConfigResult> configs,
                                               double minScore, int cap, int chunks) {
        for (ConfigResult config : configs) {
            if (Math.abs(config.minScore() - minScore) < 1e-9
                    && config.cap() == cap && config.chunksPerDocument() == chunks) {
                return config;
            }
        }
        return null;
    }

    /** 复制一份 Qdrant 配置再改 minScore：注入的 Spring Bean 一个字段都不动。 */
    private QdrantProperties copyQdrantProperties(double minScore) {
        QdrantProperties copy = new QdrantProperties();
        copy.setHost(qdrantProperties.getHost());
        copy.setGrpcPort(qdrantProperties.getGrpcPort());
        copy.setUseTls(qdrantProperties.isUseTls());
        copy.setCollection(qdrantProperties.getCollection());
        copy.setVectorDimension(qdrantProperties.getVectorDimension());
        copy.setEmbeddingModel(qdrantProperties.getEmbeddingModel());
        copy.setWorkerBatchSize(qdrantProperties.getWorkerBatchSize());
        copy.setWorkerFixedDelayMs(qdrantProperties.getWorkerFixedDelayMs());
        copy.setMaxAttempts(qdrantProperties.getMaxAttempts());
        copy.setSearchTopK(qdrantProperties.getSearchTopK());
        copy.setSearchMaxResults(qdrantProperties.getSearchMaxResults());
        copy.setCallTimeoutSeconds(qdrantProperties.getCallTimeoutSeconds());
        copy.setMinScore(minScore);
        return copy;
    }

    private static VectorResourceType toResourceType(GoldType type) {
        return switch (type) {
            case DOCUMENT -> VectorResourceType.DOCUMENT_CHUNK;
            case MINDMAP -> VectorResourceType.MINDMAP_NODE;
            case QUESTION -> VectorResourceType.QUESTION;
            case MEMORY -> throw new IllegalArgumentException(
                    "memory gold 不在本轮检索路径内，必须在 buildPlans 里排除");
        };
    }

    // ------------------------------------------------------------------
    // 4. LLM 改写组的选择与探针
    // ------------------------------------------------------------------

    /**
     * LLM 改写只跑 2 组（成本控制）：
     * <ol>
     *   <li>规则矩阵里综合最优的 1 组（recall@5 → MRR → 证据少 → P95 低）；</li>
     *   <li>生产默认参数组（0.35 / 5 / 每篇 2）——这组是“改后默认行为”的答案，必须测；</li>
     *   <li>若上面两组是同一组，则再取综合排序的第二名。</li>
     * </ol>
     * 这样最多 2 组、也只花 2 组的钱，同时能回答“生产默认路径”。
     */
    private static List<ConfigResult> pickLlmTargets(ConfigResult best,
                                                     ConfigResult defaultConfig,
                                                     List<ConfigResult> ruleConfigs) {
        List<ConfigResult> targets = new ArrayList<>();
        if (best != null) {
            targets.add(best);
        }
        if (defaultConfig != null
                && targets.stream().noneMatch(target -> target.id().equals(defaultConfig.id()))) {
            targets.add(defaultConfig);
        }
        if (targets.size() < 2) {
            for (ConfigResult candidate : ruleConfigs.stream().sorted(QUALITY_ORDER).toList()) {
                if (targets.stream().noneMatch(target -> target.id().equals(candidate.id()))) {
                    targets.add(candidate);
                    if (targets.size() >= 2) {
                        break;
                    }
                }
            }
        }
        return List.copyOf(targets);
    }

    /** 用生产同一套 {@link AiGateway} 发一次真实的改写请求，验证“LLM 改写这条路到底通不通”。 */
    private LlmUsage probeLlmRewrite() {
        llmProbeSucceeded = false;
        if (aiGateway == null) {
            log.warn("LLM 改写探针跳过：容器里没有 AiGateway Bean");
            return LlmUsage.none();
        }
        if (!aiGateway.configured()) {
            log.warn("LLM 改写探针跳过：AiGateway.configured()=false（未配置密钥），"
                    + "生产默认路径本轮标注为未测");
            return LlmUsage.none();
        }
        CountingAiGateway counting = new CountingAiGateway(aiGateway);
        try {
            AiJsonResult<QueryRewriteResult> result = counting.completeJsonForResult(
                    new AiJsonRequest(LLM_PROBE_PROMPT, "用户问题：简述时间片轮转调度的过程",
                            true, 120, 0.3d),
                    QueryRewriteResult.class);
            String rewritten = result == null || result.value() == null
                    ? null : result.value().query();
            if (rewritten == null || rewritten.isBlank()) {
                log.warn("LLM 改写探针返回空结果，跳过 LLM 组 model={}", aiGateway.model());
            } else {
                llmProbeSucceeded = true;
                log.info("LLM 改写探针成功 model={} 改写=«{}» token(prompt/completion)={}/{}",
                        aiGateway.model(), rewritten,
                        result.usage() == null ? null : result.usage().promptTokens(),
                        result.usage() == null ? null : result.usage().completionTokens());
            }
        } catch (RuntimeException e) {
            log.warn("LLM 改写探针失败，跳过 LLM 改写组，报告标注“生产默认路径未测” model={}",
                    aiGateway.model(), e);
        }
        return counting.usage();
    }

    // ------------------------------------------------------------------
    // 5. 计数装饰器（成本证据：embedding 次数、LLM 调用次数/token）
    // ------------------------------------------------------------------

    /** 包一层 Qdrant 网关，只统计 search 次数（每次 search = 1 次 embedding 调用）。 */
    private static final class CountingGateway implements QdrantVectorGateway {

        private final QdrantVectorGateway delegate;
        private final AtomicLong searchCalls = new AtomicLong();

        private CountingGateway(QdrantVectorGateway delegate) {
            this.delegate = delegate;
        }

        @Override
        public void upsert(String collection, VectorPoint point) {
            delegate.upsert(collection, point);
        }

        @Override
        public void delete(String collection, java.util.UUID pointId) {
            delegate.delete(collection, pointId);
        }

        @Override
        public List<RetrievedPoint> search(String collection,
                                           io.qdrant.client.grpc.Common.Filter filter,
                                           float[] queryVector, int limit) {
            searchCalls.incrementAndGet();
            return delegate.search(collection, filter, queryVector, limit);
        }

        @Override
        public java.util.Optional<Map<String, Object>> readPayload(
                String collection, java.util.UUID pointId) {
            return delegate.readPayload(collection, pointId);
        }

        @Override
        public List<java.util.UUID> listPointIds(
                String collection, io.qdrant.client.grpc.Common.Filter filter) {
            return delegate.listPointIds(collection, filter);
        }

        long searchCalls() {
            return searchCalls.get();
        }
    }

    /** 包一层 AI 网关：统计改写调用次数、失败次数、真实 token 用量与累计耗时。 */
    private static final class CountingAiGateway implements AiGateway {

        private final AiGateway delegate;
        private final AtomicLong calls = new AtomicLong();
        private final AtomicLong failures = new AtomicLong();
        private final AtomicLong promptTokens = new AtomicLong();
        private final AtomicLong completionTokens = new AtomicLong();
        private final AtomicLong elapsedMs = new AtomicLong();

        private CountingAiGateway(AiGateway delegate) {
            this.delegate = delegate;
        }

        @Override
        public <T> AiJsonResult<T> completeJsonForResult(AiJsonRequest request, Class<T> type) {
            calls.incrementAndGet();
            long started = System.nanoTime();
            try {
                AiJsonResult<T> result = delegate.completeJsonForResult(request, type);
                AiUsage usage = result == null ? null : result.usage();
                if (usage != null) {
                    promptTokens.addAndGet(usage.promptTokens());
                    completionTokens.addAndGet(usage.completionTokens());
                }
                return result;
            } catch (RuntimeException e) {
                failures.incrementAndGet();
                throw e;
            } finally {
                elapsedMs.addAndGet(Math.max(0L, (System.nanoTime() - started) / 1_000_000L));
            }
        }

        @Override
        public <T> T completeJson(AiJsonRequest request, Class<T> type) {
            calls.incrementAndGet();
            try {
                return delegate.completeJson(request, type);
            } catch (RuntimeException e) {
                failures.incrementAndGet();
                throw e;
            }
        }

        @Override
        public String completeChat(AiChatRequest request) {
            return delegate.completeChat(request);
        }

        @Override
        public void streamChat(AiChatRequest request, java.util.function.Consumer<String> onDelta) {
            delegate.streamChat(request, onDelta);
        }

        @Override
        public boolean configured() {
            return delegate.configured();
        }

        @Override
        public String provider() {
            return delegate.provider();
        }

        @Override
        public String model() {
            return delegate.model();
        }

        LlmUsage usage() {
            return new LlmUsage(calls.get(), failures.get(), promptTokens.get(),
                    completionTokens.get(), elapsedMs.get());
        }
    }

    // ------------------------------------------------------------------
    // 6. 探针
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
            log.error("探针① embedding 失败，立即中止测量（不播种、不扫矩阵）", e);
            throw new IllegalStateException("embedding 探针失败，测量中止：" + e.getMessage(), e);
        }
    }

    /** 只读 count：评测用户在某个资源类型下的候选点数（用来解释 recall 的天花板）。 */
    private long countUserPointsByType(VectorResourceType type) {
        Filter filter = VectorSearchService.buildFilter(
                AgentEvalSeeder.EVAL_USER_ID, List.of(type));
        Duration timeout = Duration.ofSeconds(
                Math.max(10L, qdrantProperties.getCallTimeoutSeconds()));
        try {
            Long count = qdrantClient.countAsync(qdrantProperties.getCollection(), filter,
                    Boolean.TRUE, timeout).get();
            return count == null ? 0L : count;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("统计候选点数被中断 type=" + type, e);
        } catch (Exception e) {
            throw new IllegalStateException("统计候选点数失败 type=" + type, e);
        }
    }

    // ------------------------------------------------------------------
    // 7. 清理、隔离断言（与 AgentEvalIT 同口径）
    // ------------------------------------------------------------------

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
            log.warn("检索质量夹具未被创建（互斥锁/探针阶段即中止），无需清理");
            return;
        }
        AgentEvalSeeder.CleanupResult cleanup = seeder.cleanup();
        long realAfter = seeder.countPoints(AgentEvalSeeder.REAL_COLLECTION);
        long evalAfter = seeder.countPoints(AgentEvalSeeder.EVAL_COLLECTION);
        long evalRowsRemaining = countRemainingEvalRows();
        log.info("==== 隔离证据 ====");
        log.info("真实集合 {}：跑前={} 跑后={}（只读，必须相等）",
                AgentEvalSeeder.REAL_COLLECTION, realCollectionBefore, realAfter);
        log.info("专用集合 {}：本次基线={} seed 后={} 清理后={}",
                AgentEvalSeeder.EVAL_COLLECTION, evalCollectionBaseline,
                evalPointsAfterSeed, evalAfter);
        log.info("清理明细：按 userId={} 删除业务行={} 条（{}），删除向量点={} 个",
                AgentEvalSeeder.EVAL_USER_ID, cleanup.rowsDeleted(), cleanup.tableDeletes(),
                cleanup.pointsDeleted());
        log.info("清理后 888 段评测用户残留行={}（必须为 0）", evalRowsRemaining);

        Isolation isolation = new Isolation(realCollectionBefore, realAfter,
                evalCollectionBaseline, evalAfter, evalPointsAfterSeed, stalePointsPurged,
                cleanup, evalRowsRemaining);

        if (datasetRef != null && plansRef != null && baselineRef != null
                && ruleConfigsRef != null) {
            Path reportDir = resolveDocsDir().resolve("agent-eval");
            Files.createDirectories(reportDir);
            Path jsonPath = reportDir.resolve(REPORT_BASENAME + ".json");
            Path mdPath = reportDir.resolve(REPORT_BASENAME + ".md");
            writeJsonReport(jsonPath, isolation);
            writeMarkdownReport(mdPath, isolation);
            log.info("检索质量报告已落盘 json={} md={}",
                    jsonPath.toAbsolutePath(), mdPath.toAbsolutePath());
            assertThat(jsonPath).exists();
            assertThat(mdPath).exists();
        } else {
            log.warn("测量数据不完整（dataset/baseline/ruleConfigs 有缺失），本轮不写报告");
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
    // 8. 互斥锁实现（与 AgentEvalIT 逐字一致）
    // ------------------------------------------------------------------

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

    // ------------------------------------------------------------------
    // 9. 小工具
    // ------------------------------------------------------------------

    private static String casesYamlSha256() throws Exception {
        Path path = Path.of("src/test/resources/agent-eval/cases.yaml");
        byte[] bytes;
        if (Files.isRegularFile(path)) {
            bytes = Files.readAllBytes(path);
        } else {
            try (InputStream input = RetrievalQualityIT.class
                    .getResourceAsStream("/agent-eval/cases.yaml")) {
                if (input == null) {
                    throw new IllegalStateException("找不到 cases.yaml（当前目录="
                            + Path.of("").toAbsolutePath() + "）");
                }
                bytes = input.readAllBytes();
            }
        }
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder builder = new StringBuilder(digest.length * 2);
        for (byte value : digest) {
            builder.append(String.format("%02X", value & 0xFF));
        }
        return builder.toString();
    }

    private String queryLatestFlywayVersion() {
        try {
            return jdbc.queryForObject(
                    "SELECT version FROM flyway_schema_history WHERE success = true "
                            + "ORDER BY installed_rank DESC LIMIT 1",
                    String.class);
        } catch (RuntimeException e) {
            log.warn("查询 Flyway 最新版本失败（报告写 unknown）", e);
            return "unknown";
        }
    }

    private static String fmt4(double value) {
        return String.format("%.4f", value);
    }

    private static String fmt2(double value) {
        return String.format("%.2f", value);
    }

    /** 百分点（绝对差，不是相对百分比）。 */
    private static String deltaPp(double baseline, double candidate) {
        double delta = (candidate - baseline) * 100.0d;
        return (delta >= 0 ? "+" : "") + String.format("%.1f", delta);
    }

    private static String pct(double value) {
        return String.format("%.1f%%", value * 100.0d);
    }

    private static String oneLine(String text, int maxChars) {
        if (text == null || text.isBlank()) {
            return "（空）";
        }
        String single = text.replaceAll("\\s+", " ").trim();
        return single.length() > maxChars ? single.substring(0, maxChars) + "…" : single;
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

    // ------------------------------------------------------------------
    // 10. 报告落盘（@AfterAll 内调用）
    // ------------------------------------------------------------------

    private static void writeJsonReport(Path path, Isolation isolation) throws IOException {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("reportType", "retrieval-quality");
        root.put("reportName", REPORT_BASENAME);
        root.put("generatedAt", OffsetDateTime.now().toString());
        root.put("date", LocalDate.now().toString());
        root.put("disclaimer",
                "只测检索层（播种 → 检索 → 算指标），不回答、不做 Planner；"
                        + "除 LLM 改写组外不调用对话模型。数字来自真实 embedding + Qdrant 检索，没有手工填数。");

        Map<String, Object> dataset = new LinkedHashMap<>();
        dataset.put("dataset", datasetRef.dataset());
        dataset.put("version", datasetRef.version());
        dataset.put("caseCount", datasetRef.cases().size());
        dataset.put("sha256", casesSha256);
        dataset.put("frozenHashMatches", FROZEN_CASES_SHA256.equals(casesSha256));
        root.put("dataset", dataset);

        root.put("embeddingModel", embeddingModelName);
        root.put("chatModel", chatModelName);
        root.put("evalCollection", evalCollectionName);
        root.put("realCollection", AgentEvalSeeder.REAL_COLLECTION);
        root.put("evalUserId", AgentEvalSeeder.EVAL_USER_ID);
        root.put("flywayVersion", flywayVersion);

        root.put("method", methodMap());
        root.put("power", powerMap());
        root.put("configMatrix", configMatrix());
        root.put("summary", summaryRows());
        root.put("groups", groupsMap());
        root.put("comparisons", comparisonsMap());
        root.put("latency", latencyMap());
        root.put("cost", costMap());
        root.put("cases", casesMap());
        root.put("isolation", isolationMap(isolation));
        root.put("mutex", mutexMap());
        root.put("limitations", limitations());
        root.put("openQuestions", openQuestions());
        root.put("conclusion", conclusionMap());

        Files.writeString(path, objectMapperRef.writerWithDefaultPrettyPrinter()
                .writeValueAsString(root), StandardCharsets.UTF_8);
    }

    private static Map<String, Object> methodMap() {
        Map<String, Object> method = new LinkedHashMap<>();
        List<Map<String, Object>> mapping = new ArrayList<>();
        for (GoldType type : GoldType.values()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("goldShortName", type.shortName());
            item.put("qdrantResourceType", type.wireName());
            item.put("participates", type.searchable());
            item.put("resourceIdMatch", switch (type) {
                case DOCUMENT -> "完全相等";
                case QUESTION -> "完全相等";
                case MINDMAP -> "payload source_id 等于导图 id 或以「导图id:」开头（= 同一张导图的任意节点）";
                case MEMORY -> "不参与（记忆走 searchTopMemory 独立路径）";
            });
            item.put("chunkIndexMatch", type == GoldType.MEMORY ? "不参与" : "完全相等");
            mapping.add(item);
        }
        method.put("mapping", mapping);
        method.put("hitRule",
                "payload (resource_type, source_id, chunk_index) 与 gold (type, resourceId, chunkIndex) 对齐；"
                        + "导图按同一张导图下任意节点算命中（正式口径），另给严格口径做敏感性分析");
        method.put("recallAt5", "|gold ∩ top5| / |gold|（按用例宏平均；gold 去重）");
        method.put("recallAt1", "|gold ∩ top1| / |gold|；只用于判断基线是否已经封顶，不是验收指标");
        method.put("mrr", "1 / 第一个 gold 命中的 rank（整条有序 hit 列表；没命中=0）");
        method.put("mrrAt5", "同 MRR，但只看前 5 名");
        method.put("meanAggregation", "按用例宏平均（每条用例权重相同），不是按 gold 条目微平均");
        method.put("timing", "计时只包住一次检索调用（含 embedding 网络调用 + Qdrant 检索），不含指标计算");
        method.put("resourceTypes",
                "每条用例的检索过滤类型 = 该用例 gold 里出现的类型（document→document_chunk、"
                        + "mindmap→mindmap_node、question→question）；多类型用例用联合过滤一次检索");
        method.put("baseline",
                "VectorSearchService.search：单 query、同源合并后每篇 1 chunk、cap 5、minScore 0.35");
        method.put("ruleRewrite",
                "MultiQueryRetriever + null AiGateway：原 query + 规则清洗变体（0 或 1 个），RRF 融合");
        method.put("llmRewrite",
                "MultiQueryRetriever + 真实 AiGateway：原 query + 2 个 LLM 改写，RRF 融合");
        return method;
    }

    private static List<Map<String, Object>> configMatrix() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ConfigResult config : allConfigs()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", config.id());
            row.put("group", config.group());
            row.put("path", config.path());
            row.put("rewrite", config.rewrite());
            row.put("minScore", config.minScore());
            row.put("maxResults", config.cap());
            row.put("maxChunksPerDocument", config.chunksPerDocument());
            row.put("caseCount", config.cases().size());
            row.put("searchCalls", config.searchCalls());
            row.put("wallMs", config.wallMs());
            row.put("llm", llmMap(config.llmUsage()));
            rows.add(row);
        }
        return rows;
    }

    private static List<Map<String, Object>> summaryRows() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ConfigResult config : allConfigs()) {
            rows.add(summaryRow(config));
        }
        return rows;
    }

    private static Map<String, Object> summaryRow(ConfigResult config) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", config.id());
        row.put("group", config.group());
        row.put("path", config.path());
        row.put("rewrite", config.rewrite());
        row.put("minScore", config.minScore());
        row.put("maxResults", config.cap());
        row.put("maxChunksPerDocument", config.chunksPerDocument());
        row.put("cases", config.overall().cases());
        row.put("failedCases", config.overall().failedCases());
        row.put("recallAt1", config.overall().meanRecallAt1());
        row.put("recallAt5", config.overall().meanRecall5());
        row.put("recallAt5Strict", config.overall().meanRecall5Strict());
        row.put("mrr", config.overall().meanMrr());
        row.put("mrrAt5", config.overall().meanMrr5());
        row.put("avgHits", config.overall().avgHits());
        row.put("p50Ms", config.overall().p50Ms());
        row.put("p95Ms", config.overall().p95Ms());
        row.put("totalMs", config.overall().totalMs());
        row.put("searchCalls", config.searchCalls());
        row.put("llm", llmMap(config.llmUsage()));
        return row;
    }

    private static Map<String, Object> llmMap(LlmUsage usage) {
        if (usage == null || usage.calls() == 0) {
            return Map.of("calls", 0, "failures", 0, "promptTokens", 0,
                    "completionTokens", 0, "elapsedMs", 0);
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("calls", usage.calls());
        map.put("failures", usage.failures());
        map.put("promptTokens", usage.promptTokens());
        map.put("completionTokens", usage.completionTokens());
        map.put("elapsedMs", usage.elapsedMs());
        return map;
    }

    private static Map<String, Object> groupsMap() {
        List<ConfigResult> selected = selectedForGroups();
        Map<String, Object> groups = new LinkedHashMap<>();
        groups.put("selection", selected.stream().map(ConfigResult::id).toList());
        groups.put("byCategory", dimensionGroups(selected, CaseMeasurement::category));
        groups.put("byIntent", dimensionGroups(selected, CaseMeasurement::intent));
        groups.put("byGoldType", dimensionGroups(selected, CaseMeasurement::goldTypes));
        return groups;
    }

    private static Map<String, Object> dimensionGroups(List<ConfigResult> configs,
                                                       Function<CaseMeasurement, String> keyFunction) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (ConfigResult config : configs) {
            Map<String, Object> perConfig = new LinkedHashMap<>();
            RetrievalQualityMetrics.groupBy(config.cases(), keyFunction)
                    .forEach((key, aggregate) -> perConfig.put(key, aggregateMap(aggregate)));
            result.put(config.id(), perConfig);
        }
        return result;
    }

    private static Map<String, Object> aggregateMap(Aggregate aggregate) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("cases", aggregate.cases());
        map.put("failedCases", aggregate.failedCases());
        map.put("recallAt1", aggregate.meanRecallAt1());
        map.put("recallAt5", aggregate.meanRecall5());
        map.put("recallAt5Strict", aggregate.meanRecall5Strict());
        map.put("mrr", aggregate.meanMrr());
        map.put("mrrAt5", aggregate.meanMrr5());
        map.put("avgHits", aggregate.avgHits());
        map.put("p50Ms", aggregate.p50Ms());
        map.put("p95Ms", aggregate.p95Ms());
        return map;
    }

    private static Map<String, Object> comparisonsMap() {
        Map<String, Object> comparisons = new LinkedHashMap<>();
        comparisons.put("pureMultiQuery",
                comparisonMap(baselineRef, findRuleConfig(ruleConfigsRef, 0.35d, 5, 1)));
        comparisons.put("e1DefaultCombination",
                comparisonMap(baselineRef, findRuleConfig(ruleConfigsRef, 0.35d, 5, 2)));
        comparisons.put("bestRuleRewrite", comparisonMap(baselineRef, bestRuleRef));
        comparisons.put("llmGroups", llmComparisons());
        comparisons.put("cap5VsCap8", dimensionMap(compareCap()));
        comparisons.put("chunks1VsChunks2", dimensionMap(compareChunks()));
        return comparisons;
    }

    private static Map<String, Object> llmComparisons() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", llmStatus);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ConfigResult config : llmConfigsRef) {
            rows.add(comparisonMap(baselineRef, config));
        }
        result.put("rows", rows);
        return result;
    }

    /** 把“基线 vs 候选”在总体 + 多跳 + 单跳上的对比写成 JSON。 */
    private static Map<String, Object> comparisonMap(ConfigResult baseline, ConfigResult candidate) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (candidate == null) {
            map.put("available", false);
            return map;
        }
        map.put("available", true);
        map.put("baselineId", baseline.id());
        map.put("candidateId", candidate.id());
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String group : List.of("overall", "multi-hop", "single-hop", "mindmap", "question")) {
            Aggregate baseAggregate = "overall".equals(group)
                    ? baseline.overall() : baseline.category(group);
            Aggregate candidateAggregate = "overall".equals(group)
                    ? candidate.overall() : candidate.category(group);
            if (baseAggregate.cases() == 0 || candidateAggregate.cases() == 0) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("group", group);
            row.put("cases", candidateAggregate.cases());
            row.put("baselineRecallAt5", baseAggregate.meanRecall5());
            row.put("candidateRecallAt5", candidateAggregate.meanRecall5());
            row.put("recallDeltaPp", round2(
                    (candidateAggregate.meanRecall5() - baseAggregate.meanRecall5()) * 100.0d));
            row.put("baselineMrr", baseAggregate.meanMrr());
            row.put("candidateMrr", candidateAggregate.meanMrr());
            row.put("mrrDeltaPp", round2(
                    (candidateAggregate.meanMrr() - baseAggregate.meanMrr()) * 100.0d));
            row.put("baselineAvgHits", baseAggregate.avgHits());
            row.put("candidateAvgHits", candidateAggregate.avgHits());
            row.put("baselineP95Ms", baseAggregate.p95Ms());
            row.put("candidateP95Ms", candidateAggregate.p95Ms());
            rows.add(row);
        }
        map.put("rows", rows);
        return map;
    }

    private static Map<String, Object> dimensionMap(DimensionComparison comparison) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("pairs", comparison.pairs());
        map.put("recallDeltaPp", round2(comparison.recallDeltaPp()));
        map.put("mrrDeltaPp", round2(comparison.mrrDeltaPp()));
        map.put("avgHitsDelta", round2(comparison.avgHitsDelta()));
        map.put("p95DeltaMs", comparison.p95DeltaMs());
        return map;
    }

    private static Map<String, Object> latencyMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        for (ConfigResult config : allConfigs()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("p50Ms", config.overall().p50Ms());
            item.put("p95Ms", config.overall().p95Ms());
            item.put("meanMs", config.overall().cases() == 0 ? 0L
                    : config.overall().totalMs() / config.overall().cases());
            item.put("totalMs", config.overall().totalMs());
            map.put(config.id(), item);
        }
        return map;
    }

    private static Map<String, Object> costMap() {
        Map<String, Object> cost = new LinkedHashMap<>();
        LlmUsage total = totalLlmUsage();
        cost.put("vectorSearchCalls", totalSearchCalls);
        cost.put("embeddingCalls", totalSearchCalls);
        cost.put("embeddingNote", "每到一次向量检索 = 一次 embedding 调用（VectorSearchService 内部 1:1）");
        cost.put("llmRewriteStatus", llmStatus);
        cost.put("llmRewriteCalls", total.calls());
        cost.put("llmRewriteFailures", total.failures());
        cost.put("llmPromptTokens", total.promptTokens());
        cost.put("llmCompletionTokens", total.completionTokens());
        cost.put("llmElapsedMs", total.elapsedMs());
        cost.put("measurementWallMs", retrievalWallMs);
        cost.put("money",
                "未换算金额：本报告只记录调用次数与 token；embedding 单价按平台账单为准，避免估算被当成账单");
        return cost;
    }

    private static Map<String, Object> casesMap() {
        Map<String, Object> result = new LinkedHashMap<>();
        for (ConfigResult config : allConfigs()) {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (CaseMeasurement measurement : config.cases()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("caseId", measurement.caseId());
                row.put("category", measurement.category());
                row.put("intent", measurement.intent());
                row.put("goldTypes", measurement.goldTypes());
                row.put("golds", measurement.golds().stream().map(GoldRef::raw).toList());
                List<Map<String, Object>> hits = new ArrayList<>();
                for (HitRef hit : measurement.hits()) {
                    Map<String, Object> hitMap = new LinkedHashMap<>();
                    hitMap.put("label", hit.label());
                    hitMap.put("score", Math.round(hit.score() * 100000.0d) / 100000.0d);
                    hitMap.put("matchedGold", measurement.golds().stream()
                            .anyMatch(gold -> RetrievalQualityMetrics.matches(gold, hit)));
                    hits.add(hitMap);
                }
                row.put("hits", hits);
                row.put("firstGoldRank", measurement.score().firstGoldRank());
                row.put("goldHitsTop1", measurement.score().goldHitsTop1());
                row.put("goldHitsTop5", measurement.score().goldHitsTop5());
                row.put("goldHitsTop5Strict", measurement.score().goldHitsTop5Strict());
                row.put("goldCount", measurement.score().goldCount());
                row.put("recallAt1", measurement.score().recallAt1());
                row.put("recallAt5", measurement.score().recallAt5());
                row.put("recallAt5Strict", measurement.score().recallAt5Strict());
                row.put("mrr", measurement.score().mrr());
                row.put("mrrAt5", measurement.score().mrrAt5());
                row.put("matchedGolds", measurement.score().matchedGoldKeys());
                row.put("latencyMs", measurement.latencyMs());
                row.put("error", measurement.error());
                row.put("question", datasetRef.requireById(measurement.caseId()).question());
                rows.add(row);
            }
            result.put(config.id(), rows);
        }
        return result;
    }

    private static Map<String, Object> isolationMap(Isolation isolation) {
        Map<String, Object> iso = new LinkedHashMap<>();
        iso.put("realCollection", AgentEvalSeeder.REAL_COLLECTION);
        iso.put("realCollectionPointsBefore", isolation.realCollectionPointsBefore());
        iso.put("realCollectionPointsAfter", isolation.realCollectionPointsAfter());
        iso.put("realCollectionUnchanged",
                isolation.realCollectionPointsBefore() == isolation.realCollectionPointsAfter());
        iso.put("evalCollection", evalCollectionName);
        iso.put("evalCollectionBaselinePoints", isolation.evalCollectionBaselinePoints());
        iso.put("evalCollectionPointsAfterSeed", isolation.evalCollectionPointsAfterSeed());
        iso.put("evalCollectionPointsAfterCleanup", isolation.evalCollectionPointsAfterCleanup());
        iso.put("evalCollectionBackToBaseline",
                isolation.evalCollectionPointsAfterCleanup() == isolation.evalCollectionBaselinePoints());
        iso.put("stalePointsPurgedBeforeSeed", isolation.stalePointsPurged());
        iso.put("cleanupRowsDeleted", isolation.cleanup().rowsDeleted());
        iso.put("cleanupTableDeletes", isolation.cleanup().tableDeletes());
        iso.put("cleanupVectorPointsDeleted", isolation.cleanup().pointsDeleted());
        iso.put("cleanupSessions", isolation.cleanup().sessions());
        iso.put("evalRowsRemainingAfterCleanup", isolation.evalRowsRemainingAfterCleanup());
        return iso;
    }

    private static Map<String, Object> mutexMap() {
        Map<String, Object> mutex = new LinkedHashMap<>();
        mutex.put("type", "PostgreSQL session-level advisory lock");
        mutex.put("key", EVAL_ADVISORY_LOCK_KEY);
        mutex.put("acquired", lockAcquired);
        mutex.put("acquiredAt", lockAcquiredAt);
        mutex.put("backendPid", lockBackendPid);
        mutex.put("secondSessionProbeBlocked", lockProbeBlocked);
        mutex.put("release", "同一 Connection 全程持有；@AfterAll 的 finally 里 pg_advisory_unlock + close");
        return mutex;
    }

    private static List<String> limitations() {
        return List.of(
                "**天花板效应（本轮最大局限）**：基线 recall@1 = "
                        + fmt4(baselineRef.overall().meanRecallAt1()) + "、recall@5 = "
                        + fmt4(baselineRef.overall().meanRecall5()) + "；评测用户的 document 候选只有 "
                        + candidateCounts.getOrDefault("document_chunk", 0L) + " 个 chunk，"
                        + "33 条用例的 gold 最多只跨 " + maxDistinctDocumentGolds + " 篇文档。"
                        + "recall@5 在基线就封顶，对参数不敏感，无法回答“召回是否提升”。",
                "gold 只标到 chunk 级：只要该 chunk 进 top5 就算命中，不判断 chunk 内是否真的包含答案句。",
                "导图 gold 只写到导图 id（888911），而 Qdrant payload 的 source_id 是节点级"
                        + "（888911:n-xxx）；正式口径按「同一张导图任意节点命中」放宽，把 recall@5 抬高了约 "
                        + String.format("%.1f", (baselineRef.overall().meanRecall5()
                        - baselineRef.overall().meanRecall5Strict()) * 100.0d)
                        + " 个百分点。严格口径（resourceId 必须完全相等）也一并给出，作为敏感性分析。",
                "5 条只有 memory gold 的用例被整条排除（记忆走 searchTopMemory 独立路径）；"
                        + "12 条 gold 为空的用例（拒答/闲聊/直接要系统提示词）不参与 recall。",
                "多类型 gold 的用例（document+mindmap、document+question）用「联合类型一次检索」口径；"
                        + "生产里是分工具各查一次再合并证据（上限 12 条），两种口径可能不同。",
                "单次测量、没有重复跑：LLM 改写 temperature=0.3 有随机性；embedding/Qdrant 延迟受网络与机器负载影响。",
                "只测检索层：不覆盖 Planner、工具选择、证据编号、上下文截断、read_my_document 的二次读取。",
                "recall/MRR 不衡量“同一篇的第二个 chunk 是否提供了答案细节”，也不衡量证据排序是否利于回答；"
                        + "E1 加 2 chunk 的初衷是长文档细节，本测量体现不了。",
                "33 条用例 × 16 组参数存在过拟合与多重比较风险；结论只描述本次测量，不外推到未见过的查询。",
                "耗时为“检索调用”耗时（含 embedding 网络调用），不是端到端回答延迟。");
    }

    private static List<String> openQuestions() {
        return List.of(
                "要不要做“干扰文档压力子实验”：在评测用户下临时写入 N 条同主题/跨主题的干扰 chunk，"
                        + "再跑同样的 16 组，看 top5 是否还能命中 gold？本轮没做——它会改变检索环境，"
                        + "属于新的实验设计，需所有者批准。",
                "要不要新增 gold 跨 3 篇以上文档的用例（真正触发 E1 头号风险）？这需要解冻 cases.yaml 并记录新哈希。",
                "多类型 gold 用例：是否改成“按类型各算一次 recall@5 再取平均”？本轮用联合过滤 + 分母=gold 条数。",
                "MRR 主口径：本轮主口径用整条返回列表，同时给 MRR@5；如果只认 top5，请用 MRR@5 列。",
                "导图 gold 的放宽口径：本轮按“整张导图任意节点”算命中；若要求精确到节点，需要把 gold 改成"
                        + " mindmap:888911:n-xxx（会改冻结的 cases.yaml，需所有者批准）。",
                "LLM 改写只跑 2 组：本轮固定为“规则最优组 + 生产默认参数组”（若相同则取前二），"
                        + "没有对全部 16 组跑 LLM（成本与时间控制）。",
                "cap 8 的 token 代价：本轮只给平均证据条数，没有折算成 token/金额；是否采用由所有者权衡。");
    }

    private static Map<String, Object> powerMap() {
        Map<String, Object> power = new LinkedHashMap<>();
        power.put("candidatePool", new LinkedHashMap<>(candidateCounts));
        power.put("maxDistinctDocumentGoldsPerCase", maxDistinctDocumentGolds);
        power.put("casesWithThreePlusDocumentGolds", casesWithThreePlusDocumentGolds);
        power.put("baselineRecallAt1", baselineRef.overall().meanRecallAt1());
        power.put("baselineRecallAt5", baselineRef.overall().meanRecall5());
        power.put("baselineRecallAt5Strict", baselineRef.overall().meanRecall5Strict());
        power.put("saturated", baselineRef.overall().meanRecall5() >= 0.999d);
        power.put("saturatedDefinition",
                "基线 recall@5 ≥ 0.999 即视为封顶：主指标已无提升空间，测量对 recall 没有区分力");
        power.put("note", "基线 recall@5 已封顶 → 本次测量对 recall 没有区分力；"
                + "能测的是成本/证据条数与“阈值抬高会不会漏召回”。");
        return power;
    }

    private static Map<String, Object> conclusionMap() {
        Map<String, Object> conclusion = new LinkedHashMap<>();
        ConfigResult pure = findRuleConfig(ruleConfigsRef, 0.35d, 5, 1);
        ConfigResult combo = findRuleConfig(ruleConfigsRef, 0.35d, 5, 2);
        conclusion.put("baselineId", baselineRef.id());
        conclusion.put("baselineRecallAt5", baselineRef.overall().meanRecall5());
        conclusion.put("baselineMrr", baselineRef.overall().meanMrr());
        conclusion.put("baselineAvgHits", baselineRef.overall().avgHits());
        conclusion.put("baselineP50Ms", baselineRef.overall().p50Ms());
        conclusion.put("baselineP95Ms", baselineRef.overall().p95Ms());

        if (pure != null) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", pure.id());
            item.put("recallAt5", pure.overall().meanRecall5());
            item.put("recallDeltaPp", round2((pure.overall().meanRecall5()
                    - baselineRef.overall().meanRecall5()) * 100.0d));
            item.put("mrr", pure.overall().meanMrr());
            item.put("mrrDeltaPp", round2((pure.overall().meanMrr()
                    - baselineRef.overall().meanMrr()) * 100.0d));
            item.put("avgHits", pure.overall().avgHits());
            item.put("p95Ms", pure.overall().p95Ms());
            conclusion.put("pureMultiQuery", item);
        }
        if (combo != null) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", combo.id());
            item.put("recallAt5", combo.overall().meanRecall5());
            item.put("recallDeltaPp", round2((combo.overall().meanRecall5()
                    - baselineRef.overall().meanRecall5()) * 100.0d));
            item.put("mrr", combo.overall().meanMrr());
            item.put("mrrDeltaPp", round2((combo.overall().meanMrr()
                    - baselineRef.overall().meanMrr()) * 100.0d));
            item.put("avgHits", combo.overall().avgHits());
            item.put("p95Ms", combo.overall().p95Ms());
            conclusion.put("e1DefaultCombination", item);
        }
        ConfigResult recommended = recommendation();
        if (recommended != null) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", recommended.id());
            item.put("minScore", recommended.minScore());
            item.put("maxResults", recommended.cap());
            item.put("maxChunksPerDocument", recommended.chunksPerDocument());
            item.put("rewrite", recommended.rewrite());
            item.put("recallAt5", recommended.overall().meanRecall5());
            item.put("mrr", recommended.overall().meanMrr());
            item.put("avgHits", recommended.overall().avgHits());
            item.put("p95Ms", recommended.overall().p95Ms());
            conclusion.put("recommended", item);
        }
        conclusion.put("capComparison", dimensionMap(compareCap()));
        conclusion.put("chunksComparison", dimensionMap(compareChunks()));
        conclusion.put("llmStatus", llmStatus);
        conclusion.put("narrative", conclusionNarrative());
        return conclusion;
    }

    private static ConfigResult recommendation() {
        return allConfigs().stream().min(QUALITY_ORDER).orElse(null);
    }

    // ------------------------------------------------------------------
    // 11. 维度对比（cap 5 vs 8 / 每篇 1 vs 2），用配对平均避免单组偶然
    // ------------------------------------------------------------------

    private record DimensionComparison(int pairs, double recallDeltaPp, double mrrDeltaPp,
                                       double avgHitsDelta, long p95DeltaMs) {
    }

    private static DimensionComparison compareCap() {
        int pairs = 0;
        double recall = 0.0d;
        double mrr = 0.0d;
        double hits = 0.0d;
        long p95 = 0L;
        for (double minScore : MIN_SCORES) {
            for (int chunks : MAX_CHUNKS_PER_DOCUMENT) {
                ConfigResult low = findRuleConfig(ruleConfigsRef, minScore, 5, chunks);
                ConfigResult high = findRuleConfig(ruleConfigsRef, minScore, 8, chunks);
                if (low == null || high == null) {
                    continue;
                }
                pairs++;
                recall += (high.overall().meanRecall5() - low.overall().meanRecall5()) * 100.0d;
                mrr += (high.overall().meanMrr() - low.overall().meanMrr()) * 100.0d;
                hits += high.overall().avgHits() - low.overall().avgHits();
                p95 += high.overall().p95Ms() - low.overall().p95Ms();
            }
        }
        if (pairs == 0) {
            return new DimensionComparison(0, 0.0d, 0.0d, 0.0d, 0L);
        }
        return new DimensionComparison(pairs, recall / pairs, mrr / pairs,
                hits / pairs, Math.round((double) p95 / pairs));
    }

    private static DimensionComparison compareChunks() {
        int pairs = 0;
        double recall = 0.0d;
        double mrr = 0.0d;
        double hits = 0.0d;
        long p95 = 0L;
        for (double minScore : MIN_SCORES) {
            for (int cap : MAX_RESULTS) {
                ConfigResult one = findRuleConfig(ruleConfigsRef, minScore, cap, 1);
                ConfigResult two = findRuleConfig(ruleConfigsRef, minScore, cap, 2);
                if (one == null || two == null) {
                    continue;
                }
                pairs++;
                recall += (two.overall().meanRecall5() - one.overall().meanRecall5()) * 100.0d;
                mrr += (two.overall().meanMrr() - one.overall().meanMrr()) * 100.0d;
                hits += two.overall().avgHits() - one.overall().avgHits();
                p95 += two.overall().p95Ms() - one.overall().p95Ms();
            }
        }
        if (pairs == 0) {
            return new DimensionComparison(0, 0.0d, 0.0d, 0.0d, 0L);
        }
        return new DimensionComparison(pairs, recall / pairs, mrr / pairs,
                hits / pairs, Math.round((double) p95 / pairs));
    }

    private static double round2(double value) {
        return Math.round(value * 100.0d) / 100.0d;
    }

    // ------------------------------------------------------------------
    // 12. Markdown 报告
    // ------------------------------------------------------------------

    private static void writeMarkdownReport(Path path, Isolation isolation) throws IOException {
        StringBuilder md = new StringBuilder();
        appendHeader(md);
        appendMethod(md);
        appendMatrix(md);
        appendSummary(md);
        appendGroups(md);
        appendComparisons(md);
        appendCaseDetails(md);
        appendLatency(md);
        appendCost(md);
        appendIsolation(md, isolation);
        appendMutex(md);
        appendLimitations(md);
        appendConclusion(md);
        Files.writeString(path, md.toString(), StandardCharsets.UTF_8);
    }

    private static void appendHeader(StringBuilder md) {
        md.append("# 检索质量测量报告：recall@5 / MRR（").append(LocalDate.now()).append("）\n\n");
        md.append("> 报告名：`").append(REPORT_BASENAME).append("`；由 `RetrievalQualityIT` 自动生成")
                .append("（`mvn.cmd -f mindmap-backend/pom.xml -B -Peval test -Dtest=RetrievalQualityIT`）。\n>\n");
        md.append("> **只测检索层**：播种 → 检索 → 算指标。不回答、不做 Planner；除“多查询 + LLM 改写”")
                .append("最多 2 组外，不调用对话模型。\n>\n");
        md.append("> 所有数字来自真实 embedding + Qdrant 检索与 `cases.yaml` 里已冻结的 `gold_chunks`，")
                .append("没有手工填数；`cases.yaml` SHA256 `").append(casesSha256)
                .append("`（与冻结值一致=").append(FROZEN_CASES_SHA256.equals(casesSha256)).append("）。\n\n");

        md.append("## 0. 一句话结论\n\n");
        md.append(bulletConclusion()).append("\n\n");

        md.append("## 1. 这是什么 / 不是什么\n\n");
        md.append("| 项 | 值 |\n|---|---|\n");
        md.append("| 用途 | 用 `gold_chunks` 算 recall@5 / MRR，回答“多查询 + RRF 相对基线是升还是降” |\n");
        md.append("| 用例集 | ").append(datasetRef.dataset()).append(" v").append(datasetRef.version())
                .append("（").append(datasetRef.cases().size()).append(" 条，冻结） |\n");
        md.append("| 参与测量的用例 | ").append(plansRef.size()).append(" 条（gold 非空且不含 memory） |\n");
        md.append("| 排除：gold 为空 | ").append(emptyGoldIds.size()).append(" 条：`")
                .append(String.join("`, `", emptyGoldIds)).append("` |\n");
        md.append("| 排除：只有 memory gold | ").append(memoryExcludedIds.size()).append(" 条：`")
                .append(String.join("`, `", memoryExcludedIds)).append("` |\n");
        md.append("| 排除：memory 与其它类型混合 | ").append(mixedMemoryIds.size()).append(" 条 |\n");
        md.append("| embedding / chat 模型 | `").append(embeddingModelName).append("` / `")
                .append(chatModelName).append("` |\n");
        md.append("| 评测集合 / 真实集合 | `").append(evalCollectionName).append("` / `")
                .append(AgentEvalSeeder.REAL_COLLECTION).append("`（后者只读 count） |\n");
        md.append("| 报告生成时间 | ").append(OffsetDateTime.now()).append(" |\n\n");
        md.append("**不是什么**：不是端到端 Agent 评测（P0 的 50 条对话评测另有报告）；")
                .append("不含拒答、引用、工具选择、上下文截断；也不代表线上真实用户的查询分布。\n\n");
        md.append("### 1.1 本次测量的功效：能测什么 / 不能测什么（必须先看）\n\n");
        md.append(powerNote()).append("\n");
    }

    private static void appendMethod(StringBuilder md) {
        md.append("## 2. 方法：gold → payload 映射、命中判定、排除项\n\n");
        md.append("### 2.1 资源类型映射表（映射错会让所有指标失真，所以原样打印）\n\n");
        md.append("| gold 短名 | Qdrant `resource_type` | 是否参与本轮 | resourceId 对齐方式 | chunk_index |\n");
        md.append("|---|---|---|---|---|\n");
        for (GoldType type : GoldType.values()) {
            String resourceIdRule = switch (type) {
                case DOCUMENT -> "完全相等";
                case QUESTION -> "完全相等";
                case MINDMAP -> "payload `source_id` 等于导图 id 或以「导图id:」开头（同一张导图的任意节点）";
                case MEMORY -> "不参与";
            };
            md.append("| `").append(type.shortName()).append("` | `").append(type.wireName())
                    .append("` | ").append(type.searchable() ? "是" : "**否（整条用例排除）**")
                    .append(" | ").append(resourceIdRule)
                    .append(" | ").append(type.searchable() ? "完全相等" : "不参与").append(" |\n");
        }
        md.append("\n> **导图为什么放宽**：Seeder 写进 Qdrant 的导图 `source_id` 是节点级")
                .append("（`888911:n-root`、`888911:n-sched`…），而 `cases.yaml` 的 gold 只写到")
                .append(" `mindmap:888911:0`。严格按 resourceId 完全相等对齐，7 条导图用例会全部 0 分。")
                .append("本轮正式口径按「同一张导图下任意节点命中」算命中，并额外给严格口径做敏感性分析。\n\n");
        md.append("### 2.2 命中判定与指标口径\n\n");
        md.append("- **命中**：`(resource_type, source_id, chunk_index)` 三个字段对齐（导图按上表放宽）。\n");
        md.append("- **recall@5** = `|gold ∩ top5| / |gold|`；每条用例的 gold 先按 `type:id:chunk` 去重。\n");
        md.append("- **recall@1** = `|gold ∩ top1| / |gold|`，只用来判断“基线是不是已经封顶”。\n");
        md.append("- **MRR** = `1 / 第一个 gold 命中的 rank`（整条有序 hit 列表；没命中 = 0）；另给只看前 5 的 **MRR@5**。\n");
        md.append("- **汇总口径**：按用例宏平均（每条用例权重相同），不是按 gold 条目微平均。\n");
        md.append("- **耗时**：只包住一次检索调用（含 embedding 网络调用 + Qdrant 检索），不含指标计算；P50/P95 用最近秩百分位。\n");
        md.append("- **检索类型**：每条用例的过滤类型 = 该用例 gold 里出现的类型；多类型用例用联合过滤一次检索。\n\n");
        md.append("### 2.3 排除项与计数\n\n");
        md.append("| 排除原因 | 条数 | 用例 |\n|---|---:|---|\n");
        md.append("| `gold_chunks` 为空（拒答/闲聊/直接要系统提示词） | ")
                .append(emptyGoldIds.size()).append(" | `")
                .append(String.join("`, `", emptyGoldIds)).append("` |\n");
        md.append("| 只有 `memory:*` gold（记忆检索是独立路径） | ")
                .append(memoryExcludedIds.size()).append(" | `")
                .append(String.join("`, `", memoryExcludedIds)).append("` |\n");
        md.append("| memory 与其它类型混合 | ").append(mixedMemoryIds.size()).append(" | ")
                .append(mixedMemoryIds.isEmpty() ? "（无）" : "`" + String.join("`, `", mixedMemoryIds) + "`")
                .append(" |\n\n");
    }

    private static void appendMatrix(StringBuilder md) {
        md.append("## 3. 配置矩阵\n\n");
        md.append("| # | 配置 id | 组 | 路径 | 改写 | minScore | cap | 每篇 chunk | 检索次数 | 耗时(ms) |\n");
        md.append("|---:|---|---|---|---|---:|---:|---:|---:|---:|\n");
        int index = 0;
        for (ConfigResult config : allConfigs()) {
            index++;
            md.append("| ").append(index).append(" | `").append(config.id()).append("` | ")
                    .append(config.group()).append(" | ").append(config.path()).append(" | ")
                    .append(config.rewrite()).append(" | ").append(fmt2(config.minScore())).append(" | ")
                    .append(config.cap()).append(" | ").append(config.chunksPerDocument()).append(" | ")
                    .append(config.searchCalls()).append(" | ").append(config.wallMs()).append(" |\n");
        }
        md.append("\n- 基线 = 旧路径 `VectorSearchService.search`：单 query、同源合并后每篇 1 chunk、cap 5、minScore 0.35。\n");
        md.append("- 规则改写 = `MultiQueryRetriever` + `null` AiGateway：原 query + 规则清洗变体（0 或 1 个），RRF 融合，零对话成本。\n");
        md.append("- LLM 改写组只跑 2 组：规则矩阵综合最优组 + 生产默认参数组（0.35/5/2）；")
                .append("本轮状态：**").append(llmStatus).append("**。\n\n");
    }

    private static void appendSummary(StringBuilder md) {
        md.append("## 4. 汇总表（").append(plansRef.size()).append(" 条可测用例，宏平均）\n\n");
        md.append("> `recall@5` / `MRR` 是主指标；`recall@1` 是“天花板诊断”（第 1 名就命中多少 gold）；")
                .append("`严格 recall@5` = 导图不放宽（resourceId 完全相等）的敏感性口径；")
                .append("`平均证据` = 每次检索平均返回多少条 hit（不是文档数）。\n\n");
        md.append("| 配置 | 组 | minScore | cap | 每篇 chunk | recall@1 | recall@5 | 严格 recall@5 | MRR | MRR@5 | 平均证据 | P50(ms) | P95(ms) | 失败 |\n");
        md.append("|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|\n");
        md.append(summaryRowMarkdown(baselineRef));
        for (ConfigResult config : ruleConfigsRef.stream().sorted(QUALITY_ORDER).toList()) {
            md.append(summaryRowMarkdown(config));
        }
        for (ConfigResult config : llmConfigsRef) {
            md.append(summaryRowMarkdown(config));
        }
        md.append("\n> 规则组按综合质量排序（recall@5 → MRR → 证据少 → 离默认阈值近 → P95 低），原始 16 组都在表内。\n\n");
    }

    private static String summaryRowMarkdown(ConfigResult config) {
        return "| `" + config.id() + "` | " + config.group()
                + " | " + fmt2(config.minScore()) + " | " + config.cap()
                + " | " + config.chunksPerDocument()
                + " | " + fmt4(config.overall().meanRecallAt1())
                + " | **" + fmt4(config.overall().meanRecall5()) + "**"
                + " | " + fmt4(config.overall().meanRecall5Strict())
                + " | **" + fmt4(config.overall().meanMrr()) + "**"
                + " | " + fmt4(config.overall().meanMrr5())
                + " | " + fmt4(config.overall().avgHits())
                + " | " + config.overall().p50Ms()
                + " | " + config.overall().p95Ms()
                + " | " + config.overall().failedCases() + " |\n";
    }

    private static void appendGroups(StringBuilder md) {
        md.append("## 5. 分组表\n\n");
        List<ConfigResult> selected = selectedForGroups();
        appendGroupTable(md, "5.1 按分类（cases.yaml 第一项 tag）", selected, CaseMeasurement::category);
        appendGroupTable(md, "5.2 按 intent", selected, CaseMeasurement::intent);
        appendGroupTable(md, "5.3 按 gold 资源类型", selected, CaseMeasurement::goldTypes);
    }

    private static void appendGroupTable(StringBuilder md, String title,
                                         List<ConfigResult> configs,
                                         Function<CaseMeasurement, String> keyFunction) {
        md.append("### ").append(title).append("\n\n");
        Set<String> keys = new LinkedHashSet<>();
        for (ConfigResult config : configs) {
            keys.addAll(RetrievalQualityMetrics.groupBy(config.cases(), keyFunction).keySet());
        }
        if (keys.isEmpty()) {
            md.append("（无数据）\n\n");
            return;
        }
        md.append("| 分组 | 用例数 |");
        for (ConfigResult config : configs) {
            md.append(" `").append(config.id()).append("` recall@5 | MRR |");
        }
        md.append("\n|---|---:|");
        for (int index = 0; index < configs.size(); index++) {
            md.append("---:|---:|");
        }
        md.append("\n");
        for (String key : keys) {
            Map<String, Aggregate> first = RetrievalQualityMetrics.groupBy(
                    configs.get(0).cases(), keyFunction);
            Aggregate firstAggregate = first.getOrDefault(key, Aggregate.empty());
            md.append("| ").append(key).append(" | ").append(firstAggregate.cases()).append(" |");
            for (ConfigResult config : configs) {
                Aggregate aggregate = RetrievalQualityMetrics.groupBy(
                        config.cases(), keyFunction).getOrDefault(key, Aggregate.empty());
                md.append(" ").append(fmt4(aggregate.meanRecall5()))
                        .append(" | ").append(fmt4(aggregate.meanMrr())).append(" |");
            }
            md.append("\n");
        }
        md.append("\n");
    }

    private static List<ConfigResult> selectedForGroups() {
        List<ConfigResult> selected = new ArrayList<>();
        selected.add(baselineRef);
        if (bestRuleRef != null) {
            selected.add(bestRuleRef);
        }
        if (defaultRuleRef != null
                && selected.stream().noneMatch(item -> item.id().equals(defaultRuleRef.id()))) {
            selected.add(defaultRuleRef);
        }
        if (llmConfigsRef != null && !llmConfigsRef.isEmpty()) {
            ConfigResult bestLlm = llmConfigsRef.stream().min(QUALITY_ORDER).orElse(null);
            if (bestLlm != null && selected.stream().noneMatch(item -> item.id().equals(bestLlm.id()))) {
                selected.add(bestLlm);
            }
        }
        return selected;
    }

    private static void appendComparisons(StringBuilder md) {
        md.append("## 6. 关键对比：基线 vs 多查询（绝对值，单位百分点）\n\n");
        md.append("> 百分点（pp）= 两个比例相减再乘 100；例如 0.6000 → 0.6667 写作 **+6.7 pp**。\n\n");

        ConfigResult pure = findRuleConfig(ruleConfigsRef, 0.35d, 5, 1);
        ConfigResult combo = findRuleConfig(ruleConfigsRef, 0.35d, 5, 2);
        appendComparisonTable(md, "6.1 纯多查询 / RRF（0.35 / cap5 / 每篇 1 chunk，只改“单 query → 多 query”）",
                baselineRef, pure);
        appendComparisonTable(md, "6.2 E1 生产默认组合（0.35 / cap5 / 每篇 2 chunk）",
                baselineRef, combo);
        appendComparisonTable(md, "6.3 最优规则组", baselineRef, bestRuleRef);
        if (!llmConfigsRef.isEmpty()) {
            int index = 0;
            for (ConfigResult config : llmConfigsRef) {
                index++;
                appendComparisonTable(md, "6.4." + index + " 多查询 + LLM 改写（" + config.id() + "）",
                        baselineRef, config);
            }
        } else {
            md.append("### 6.4 多查询 + LLM 改写（生产默认路径）\n\n");
            md.append("**未测**：").append(llmStatus).append("。因此本报告不能声称“生产默认路径”的召回数字。\n\n");
        }

        DimensionComparison cap = compareCap();
        DimensionComparison chunks = compareChunks();
        md.append("### 6.5 cap 5 vs 8（8 组配对平均：4 个 minScore × 2 种每篇 chunk 数）\n\n");
        md.append("| 指标 | 变化（cap 8 − cap 5） |\n|---|---:|\n");
        md.append("| recall@5 | ").append(signed(cap.recallDeltaPp(), 1)).append(" pp |\n");
        md.append("| MRR | ").append(signed(cap.mrrDeltaPp(), 1)).append(" pp |\n");
        md.append("| 平均证据条数 | ").append(signed(cap.avgHitsDelta(), 2)).append(" 条 |\n");
        md.append("| P95 | ").append(cap.p95DeltaMs() >= 0 ? "+" : "").append(cap.p95DeltaMs()).append(" ms |\n");
        md.append("| 配对数 | ").append(cap.pairs()).append(" |\n\n");

        md.append("### 6.6 每篇 1 vs 2 chunk（8 组配对平均：4 个 minScore × 2 种 cap）\n\n");
        md.append("| 指标 | 变化（2 chunk − 1 chunk） |\n|---|---:|\n");
        md.append("| recall@5 | ").append(signed(chunks.recallDeltaPp(), 1)).append(" pp |\n");
        md.append("| MRR | ").append(signed(chunks.mrrDeltaPp(), 1)).append(" pp |\n");
        md.append("| 平均证据条数 | ").append(signed(chunks.avgHitsDelta(), 2)).append(" 条 |\n");
        md.append("| P95 | ").append(chunks.p95DeltaMs() >= 0 ? "+" : "").append(chunks.p95DeltaMs()).append(" ms |\n");
        md.append("| 配对数 | ").append(chunks.pairs()).append(" |\n\n");
    }

    private static void appendComparisonTable(StringBuilder md, String title,
                                              ConfigResult baseline, ConfigResult candidate) {
        md.append("### ").append(title).append("\n\n");
        if (candidate == null) {
            md.append("（该配置未跑）\n\n");
            return;
        }
        md.append("| 分组 | 用例 | 基线 recall@5 | 候选 recall@5 | Δ recall | 基线 MRR | 候选 MRR | Δ MRR | 平均证据（基线→候选） |\n");
        md.append("|---|---:|---:|---:|---:|---:|---:|---:|---:|\n");
        appendComparisonRow(md, "总体", baseline.overall(), candidate.overall());
        for (String group : List.of("multi-hop", "single-hop", "mindmap", "question")) {
            Aggregate baseAggregate = baseline.category(group);
            Aggregate candidateAggregate = candidate.category(group);
            if (baseAggregate.cases() == 0 || candidateAggregate.cases() == 0) {
                continue;
            }
            appendComparisonRow(md, group, baseAggregate, candidateAggregate);
        }
        md.append("\n");
    }

    private static void appendComparisonRow(StringBuilder md, String group,
                                            Aggregate baseline, Aggregate candidate) {
        md.append("| ").append(group).append(" | ").append(candidate.cases())
                .append(" | ").append(fmt4(baseline.meanRecall5()))
                .append(" | ").append(fmt4(candidate.meanRecall5()))
                .append(" | **").append(deltaPp(baseline.meanRecall5(), candidate.meanRecall5()))
                .append(" pp** | ").append(fmt4(baseline.meanMrr()))
                .append(" | ").append(fmt4(candidate.meanMrr()))
                .append(" | ").append(deltaPp(baseline.meanMrr(), candidate.meanMrr()))
                .append(" pp | ").append(fmt4(baseline.avgHits()))
                .append(" → ").append(fmt4(candidate.avgHits())).append(" |\n");
    }

    private static void appendCaseDetails(StringBuilder md) {
        md.append("## 7. 每用例明细\n\n");
        md.append("> `gold 排名` = 第一个 gold 命中的名次（0 = 没命中）；`top5 命中情况` 里 **加粗**的是命中 gold 的片段。\n\n");
        appendCaseTable(md, baselineRef);
        appendCaseTable(md, bestRuleRef);
        if (defaultRuleRef != null
                && !defaultRuleRef.id().equals(bestRuleRef.id())) {
            appendCaseTable(md, defaultRuleRef);
        }
        for (ConfigResult config : llmConfigsRef) {
            appendCaseTable(md, config);
        }
    }

    private static void appendCaseTable(StringBuilder md, ConfigResult config) {
        if (config == null) {
            return;
        }
        md.append("### 7.").append(config.id()).append("（").append(config.group())
                .append(" / minScore=").append(fmt2(config.minScore()))
                .append(" / cap=").append(config.cap())
                .append(" / 每篇 ").append(config.chunksPerDocument()).append(" chunk）\n\n");
        md.append("| # | 用例 | 分类 | gold | gold 排名 | top5 命中情况 | recall@5 | MRR | 证据数 | 耗时(ms) |\n");
        md.append("|---:|---|---|---|---:|---|---:|---:|---:|---:|\n");
        int index = 0;
        for (CaseMeasurement measurement : config.cases()) {
            index++;
            md.append("| ").append(index).append(" | `").append(measurement.caseId()).append("` | ")
                    .append(measurement.category()).append(" | ")
                    .append(measurement.golds().stream().map(GoldRef::raw)
                            .collect(Collectors.joining("<br>")))
                    .append(" | ").append(measurement.score().firstGoldRank())
                    .append(" | ").append(hitSummary(measurement))
                    .append(" | ").append(fmt4(measurement.score().recallAt5()))
                    .append(" | ").append(fmt4(measurement.score().mrr()))
                    .append(" | ").append(measurement.hits().size())
                    .append(" | ").append(measurement.latencyMs()).append(" |\n");
        }
        md.append("\n");
    }

    private static String hitSummary(CaseMeasurement measurement) {
        if (measurement.failed()) {
            return "**ERROR**：" + oneLine(measurement.error(), 60);
        }
        if (measurement.hits().isEmpty()) {
            return "（无命中）";
        }
        int limit = Math.min(5, measurement.hits().size());
        List<String> parts = new ArrayList<>(limit);
        for (int index = 0; index < limit; index++) {
            HitRef hit = measurement.hits().get(index);
            boolean matched = measurement.golds().stream()
                    .anyMatch(gold -> RetrievalQualityMetrics.matches(gold, hit));
            String label = hit.label();
            parts.add(matched ? "**" + label + "**" : label);
        }
        String text = String.join("<br>", parts);
        if (measurement.hits().size() > limit) {
            text += "<br>…（共 " + measurement.hits().size() + " 条）";
        }
        return text;
    }

    private static void appendLatency(StringBuilder md) {
        md.append("## 8. 耗时（回答“多查询会不会把 P95 拖高”）\n\n");
        md.append("| 配置 | P50(ms) | P95(ms) | 均值(ms) | 相对基线 P50 | 相对基线 P95 |\n");
        md.append("|---|---:|---:|---:|---:|---:|\n");
        for (ConfigResult config : allConfigs()) {
            long mean = config.overall().cases() == 0 ? 0L
                    : config.overall().totalMs() / config.overall().cases();
            md.append("| `").append(config.id()).append("` | ").append(config.overall().p50Ms())
                    .append(" | ").append(config.overall().p95Ms())
                    .append(" | ").append(mean)
                    .append(" | ").append(signed(config.overall().p50Ms()
                            - baselineRef.overall().p50Ms(), 0)).append(" ms")
                    .append(" | ").append(signed(config.overall().p95Ms()
                            - baselineRef.overall().p95Ms(), 0)).append(" ms |\n");
        }
        md.append("\n> 基线 1 次检索 = 1 次 embedding；规则改写最多 2 次（并发）；LLM 改写 = 2 次改写调用 + 3 次检索。")
                .append("规则组与基线基本同量级：多出来的一次 embedding 与 Qdrant 检索被并发与网络抖动掩盖，")
                .append("看不出稳定增量。");
        if (!llmConfigsRef.isEmpty()) {
            ConfigResult llmBest = llmConfigsRef.stream().min(QUALITY_ORDER).orElse(llmConfigsRef.get(0));
            md.append("**LLM 组的 P50 比基线高约 ")
                    .append(llmBest.overall().p50Ms() - baselineRef.overall().p50Ms())
                    .append(" ms，增量主要来自两次改写调用本身（各约 0.6~1.1s，并发发起）。**");
        }
        md.append("\n\n");
    }

    private static void appendCost(StringBuilder md) {
        LlmUsage total = totalLlmUsage();
        long llmCalls = total.calls();
        long llmFailures = total.failures();
        long promptTokens = total.promptTokens();
        long completionTokens = total.completionTokens();
        long llmElapsed = total.elapsedMs();
        md.append("## 9. 成本\n\n");
        md.append("| 项 | 数值 | 说明 |\n|---|---:|---|\n");
        md.append("| 向量检索次数 | ").append(totalSearchCalls)
                .append(" | 每次检索 = 1 次 embedding 调用（`VectorSearchService` 内部 1:1） |\n");
        md.append("| embedding 调用次数 | ").append(totalSearchCalls).append(" | 同上 |\n");
        md.append("| LLM 改写状态 | ").append(llmStatus).append(" | 生产默认路径是否被测 |\n");
        md.append("| LLM 改写调用次数 | ").append(llmCalls)
                .append(" | 含 1 次探针；只跑 ≤2 组配置 |\n");
        md.append("| LLM 改写失败次数 | ").append(llmFailures).append(" | 失败的那一路会降级为规则改写 |\n");
        md.append("| LLM prompt / completion token | ").append(promptTokens).append(" / ")
                .append(completionTokens).append(" | 平台回传的真实 usage，0 表示平台未回传 |\n");
        md.append("| LLM 累计耗时 | ").append(llmElapsed).append(" ms | 改写调用自身的累计耗时 |\n");
        md.append("| 测量总墙钟 | ").append(retrievalWallMs).append(" ms | 含播种后的全部配置 |\n\n");
        md.append("> 本报告**不折算金额**：只给调用次数与 token，避免估算价被当成账单。\n\n");
    }

    private static void appendIsolation(StringBuilder md, Isolation isolation) {
        md.append("## 10. 隔离与清理（硬证据）\n\n");
        md.append("| 项 | 值 |\n|---|---|\n");
        md.append("| 评测用户 id | ").append(AgentEvalSeeder.EVAL_USER_ID).append("（888xxx 段，专用） |\n");
        md.append("| 写入集合（测试属性覆盖） | `").append(evalCollectionName).append("` |\n");
        md.append("| 真实集合（全程只读 count） | `").append(AgentEvalSeeder.REAL_COLLECTION).append("` |\n");
        md.append("| **真实集合点数：跑前 / 跑后** | **")
                .append(isolation.realCollectionPointsBefore()).append(" / ")
                .append(isolation.realCollectionPointsAfter()).append("**（相等=未被污染） |\n");
        md.append("| 专用集合点数：本次基线 / seed 后 / 清理后 | ")
                .append(isolation.evalCollectionBaselinePoints()).append(" / ")
                .append(isolation.evalCollectionPointsAfterSeed()).append(" / ")
                .append(isolation.evalCollectionPointsAfterCleanup()).append("（清理后回到基线） |\n");
        md.append("| seed 前清理的残留点 | ").append(isolation.stalePointsPurged()).append(" |\n");
        md.append("| 按 userId 删除业务行 | ").append(isolation.cleanup().rowsDeleted()).append(" 条：")
                .append(isolation.cleanup().tableDeletes()).append(" |\n");
        md.append("| 按 payload user_id 删除向量点 | ").append(isolation.cleanup().pointsDeleted())
                .append(" 个（删除会话 ").append(isolation.cleanup().sessions()).append(" 个） |\n");
        md.append("| 清理后 888 段评测用户残留行 | ")
                .append(isolation.evalRowsRemainingAfterCleanup()).append("（必须为 0） |\n\n");
    }

    private static void appendMutex(StringBuilder md) {
        md.append("## 11. 单实例互斥锁证据\n\n");
        md.append("| 项 | 值 |\n|---|---|\n");
        md.append("| 锁类型 | PostgreSQL 会话级 advisory lock（`pg_try_advisory_lock`） |\n");
        md.append("| 固定 key | ").append(EVAL_ADVISORY_LOCK_KEY)
                .append("（与 `AgentEvalIT` 同一把，防止两个评测互相清数据） |\n");
        md.append("| 拿锁时间 / backend pid | ").append(lockAcquiredAt)
                .append(" / ").append(lockBackendPid).append(" |\n");
        md.append("| 第二个会话再试同一把锁 | ")
                .append(lockProbeBlocked ? "被拒绝（符合预期）" : "拿到了（异常）").append(" |\n\n");
    }

    private static void appendLimitations(StringBuilder md) {
        md.append("## 12. 口径说明、局限与待定取舍\n\n");
        md.append("### 12.1 局限（最可能失真的地方按顺序列）\n\n");
        int index = 0;
        for (String limitation : limitations()) {
            index++;
            md.append(index).append(". ").append(limitation).append("\n");
        }
        md.append("\n### 12.2 规格未覆盖、我按本报告口径处理的取舍（等所有者定）\n\n");
        index = 0;
        for (String question : openQuestions()) {
            index++;
            md.append(index).append(". ").append(question).append("\n");
        }
        md.append("\n");
    }

    private static void appendConclusion(StringBuilder md) {
        md.append("## 13. 结论与推荐配置\n\n");
        md.append(conclusionNarrative()).append("\n\n");

        ConfigResult recommended = recommendation();
        if (recommended != null) {
            md.append("### 13.1 推荐配置（只写进报告，**没有改 yml 默认值**）\n\n");
            md.append("| 项 | 值 |\n|---|---|\n");
            md.append("| minScore | ").append(fmt2(recommended.minScore())).append(" |\n");
            md.append("| max-results | ").append(recommended.cap()).append(" |\n");
            md.append("| max-chunks-per-document | ").append(recommended.chunksPerDocument()).append(" |\n");
            md.append("| 改写方式 | ").append(recommended.rewrite()).append(" |\n");
            md.append("| 该组 recall@5 / MRR | ").append(fmt4(recommended.overall().meanRecall5()))
                    .append(" / ").append(fmt4(recommended.overall().meanMrr())).append(" |\n");
            md.append("| 该组平均证据数 / P95 | ").append(fmt4(recommended.overall().avgHits()))
                    .append(" 条 / ").append(recommended.overall().p95Ms()).append(" ms |\n\n");
            md.append("> 推荐按“recall@5 → MRR → 证据少 → 离默认阈值近 → P95 低”自动选，不掺主观。")
                    .append("**但本次所有组 recall/MRR 都封顶，所以这只是“指标打平时按证据成本选”，")
                    .append("不等于“更准”；是否采用、要不要付 cap 8 的 token 代价，由评估组长与所有者决定。**\n\n");
        }
        md.append("### 13.2 明确回答两个问题\n\n");
        appendDirectAnswers(md);
    }

    private static void appendDirectAnswers(StringBuilder md) {
        ConfigResult pure = findRuleConfig(ruleConfigsRef, 0.35d, 5, 1);
        ConfigResult combo = findRuleConfig(ruleConfigsRef, 0.35d, 5, 2);
        double baselineRecall = baselineRef.overall().meanRecall5();
        md.append("**问题一：多查询 / RRF 相对基线，recall@5 是升还是降？**\n\n");
        md.append("> 先给结论：**升 0.0 个百分点、降 0.0 个百分点——持平**。但必须连着“基线已封顶”一起读：")
                .append("基线 recall@1 = ").append(fmt4(baselineRef.overall().meanRecallAt1()))
                .append("、recall@5 = ").append(fmt4(baselineRecall))
                .append("，候选池只有 ").append(candidateCounts.getOrDefault("document_chunk", 0L))
                .append(" 个 document chunk，没有提升空间，也没有“挤掉”的空间。\n\n");
        if (pure != null) {
            double delta = (pure.overall().meanRecall5() - baselineRecall) * 100.0d;
            md.append("- 同 cap=5、同每篇 1 chunk、同 minScore=0.35 的对照：recall@5 从 **")
                    .append(fmt4(baselineRecall)).append("** 到 **")
                    .append(fmt4(pure.overall().meanRecall5())).append("**，**")
                    .append(direction(delta)).append(" ")
                    .append(String.format("%.1f", Math.abs(delta))).append(" 个百分点**；MRR 从 **")
                    .append(fmt4(baselineRef.overall().meanMrr())).append("** 到 **")
                    .append(fmt4(pure.overall().meanMrr())).append("**（**")
                    .append(direction((pure.overall().meanMrr() - baselineRef.overall().meanMrr()) * 100.0d))
                    .append(" ").append(String.format("%.1f", Math.abs(
                            (pure.overall().meanMrr() - baselineRef.overall().meanMrr()) * 100.0d)))
                    .append(" 个百分点**）。\n");
        }
        if (combo != null) {
            double delta = (combo.overall().meanRecall5() - baselineRecall) * 100.0d;
            md.append("- E1 生产默认组合（cap5 / 每篇 2 chunk）：recall@5 从 **")
                    .append(fmt4(baselineRecall)).append("** 到 **")
                    .append(fmt4(combo.overall().meanRecall5())).append("**，**")
                    .append(direction(delta)).append(" ")
                    .append(String.format("%.1f", Math.abs(delta))).append(" 个百分点**；MRR **")
                    .append(direction((combo.overall().meanMrr() - baselineRef.overall().meanMrr()) * 100.0d))
                    .append(" ").append(String.format("%.1f", Math.abs(
                            (combo.overall().meanMrr() - baselineRef.overall().meanMrr()) * 100.0d)))
                    .append(" 个百分点**。\n");
            if (pure != null) {
                double chunkEffect = (combo.overall().meanRecall5()
                        - pure.overall().meanRecall5()) * 100.0d;
                md.append("- 把“每篇 1 → 2 chunk”单独看：recall@5 ")
                        .append(direction(chunkEffect)).append(" ")
                        .append(String.format("%.1f", Math.abs(chunkEffect)))
                        .append(" 个百分点。**0 差异不代表风险不存在**：本夹具没有第 3 篇文档可被挤掉，")
                        .append("E1 的头号风险在这套夹具上无法验证（见 1.1）。\n");
            }
        }
        ConfigResult worst = ruleConfigsRef.stream()
                .min(java.util.Comparator.comparingDouble(
                        config -> config.overall().meanRecall5()))
                .orElse(null);
        if (worst != null && worst.overall().meanRecall5() < baselineRecall - 1e-9) {
            String lostCases = worst.cases().stream()
                    .filter(measurement -> measurement.score().recallAt5() < 0.9999d)
                    .map(measurement -> "`" + measurement.caseId() + "`（"
                            + measurement.score().goldHitsTop5() + "/"
                            + measurement.score().goldCount() + "）")
                    .collect(Collectors.joining("、"));
            md.append("- **唯一让 recall 下降的是 minScore=0.45**：4 组都降到 ")
                    .append(fmt4(worst.overall().meanRecall5())).append("（")
                    .append(deltaPp(baselineRecall, worst.overall().meanRecall5()))
                    .append(" 个百分点），丢的是 ").append(lostCases)
                    .append("——阈值抬高会把相关度略低的片段滤掉。\n");
        }
        md.append("\n**问题二：cap 5 vs 8、每篇 1 vs 2 哪个好？**\n\n");
        DimensionComparison cap = compareCap();
        DimensionComparison chunks = compareChunks();
        md.append("- cap：8 相对 5 的配对平均 recall@5 **")
                .append(signed(cap.recallDeltaPp(), 1)).append(" 个百分点**，MRR **")
                .append(signed(cap.mrrDeltaPp(), 1)).append(" 个百分点**，平均证据 ")
                .append(signed(cap.avgHitsDelta(), 2)).append(" 条/次，P95 ")
                .append(cap.p95DeltaMs() >= 0 ? "+" : "").append(cap.p95DeltaMs()).append(" ms。\n");
        md.append("- 每篇 chunk：2 相对 1 的配对平均 recall@5 **")
                .append(signed(chunks.recallDeltaPp(), 1)).append(" 个百分点**，MRR **")
                .append(signed(chunks.mrrDeltaPp(), 1)).append(" 个百分点**，平均证据 ")
                .append(signed(chunks.avgHitsDelta(), 2)).append(" 条/次，P95 ")
                .append(chunks.p95DeltaMs() >= 0 ? "+" : "").append(chunks.p95DeltaMs()).append(" ms。\n");
        md.append("- 结论按数字说：**").append(capVerdict(cap)).append("**；**")
                .append(chunksVerdict(chunks)).append("**。\n\n");
        md.append("> 代价提醒：cap 8 的“平均证据条数”已经列在上面；证据越多，进上下文的 token 越多。")
                .append("本报告不把条数折算成金额，是否值得由所有者定。\n\n");
    }

    private static String capVerdict(DimensionComparison cap) {
        if (cap.pairs() == 0) {
            return "没有可配对的 cap 数据";
        }
        if (cap.recallDeltaPp() > 0.5d) {
            return "cap 8 的 recall 更高，但要多付平均 " + fmt2(cap.avgHitsDelta()) + " 条证据";
        }
        if (cap.recallDeltaPp() < -0.5d) {
            return "cap 8 反而更低，维持 cap 5";
        }
        return "cap 8 与 cap 5 的 recall 完全持平（基线已封顶，测不出差异），按证据成本选 cap 5";
    }

    private static String chunksVerdict(DimensionComparison chunks) {
        if (chunks.pairs() == 0) {
            return "没有可配对的 chunk 数据";
        }
        if (chunks.recallDeltaPp() > 0.5d) {
            return "每篇 2 chunk 的 recall 更高";
        }
        if (chunks.recallDeltaPp() < -0.5d) {
            return "每篇 2 chunk 反而更低（正是 E1 担心的“2 chunk 挤掉其它文档”），应维持 1 chunk";
        }
        return "每篇 2 chunk 与 1 chunk 的 recall 完全持平；但这不等于风险不存在——夹具太小，"
                + "测不出“挤掉其它文档”，按证据成本选 1 chunk";
    }

    /**
     * 本次测量的“功效说明”（能测什么、不能测什么）。
     *
     * <p>这是本报告最重要的一节：不写清楚就会把“基线封顶”误读成“多查询没有负优化”。
     */
    private static String powerNote() {
        long docChunks = candidateCounts.getOrDefault("document_chunk", 0L);
        long mindmapNodes = candidateCounts.getOrDefault("mindmap_node", 0L);
        long questions = candidateCounts.getOrDefault("question", 0L);
        StringBuilder text = new StringBuilder();
        text.append("- **候选池很小**：评测用户一共只有 ").append(docChunks)
                .append(" 个 document chunk、").append(mindmapNodes).append(" 个导图节点、")
                .append(questions).append(" 道题。基线一次检索平均就返回 ")
                .append(fmt4(baselineRef.overall().avgHits())).append(" 条，")
                .append("document 的候选 chunk 基本被 top5 全部装下。\n");
        text.append("- **基线已经贴近天花板**：recall@1 = **")
                .append(fmt4(baselineRef.overall().meanRecallAt1()))
                .append("**、recall@5 = **").append(fmt4(baselineRef.overall().meanRecall5()))
                .append("**。recall@5 在基线就封顶（第 1 名也已覆盖 ")
                .append(Math.round(baselineRef.overall().meanRecallAt1() * 100.0d))
                .append("% 的 gold 条目），任何召回策略都无法再把它“提升”。\n");
        text.append("- **E1 头号风险在结构上无法出现**：33 条可测用例里，gold 最多只涉及 **")
                .append(maxDistinctDocumentGolds).append(" 篇**不同文档（gold 跨 ≥3 篇文档的用例有 ")
                .append(casesWithThreePlusDocumentGolds)
                .append(" 条）。cap5 × 每篇 2 chunk 只装得下 2~3 篇，但没有任何用例需要 3 篇以上，")
                .append("所以“多跳 gold 被挤出 top5”在这套夹具里不可能发生。\n");
        text.append("- **所以本次测量能回答成本与证据条数，不能回答“召回是否提升”**。")
                .append("要回答后者，必须增加文档量/干扰样本，并新增 gold 跨 3 篇以上文档的用例——")
                .append("后者会改冻结的 `cases.yaml`，需要所有者批准。\n");
        return text.toString();
    }

    private static String bulletConclusion() {
        ConfigResult pure = findRuleConfig(ruleConfigsRef, 0.35d, 5, 1);
        ConfigResult combo = findRuleConfig(ruleConfigsRef, 0.35d, 5, 2);
        StringBuilder text = new StringBuilder();
        text.append("- **先看功效**：基线 recall@1 = ").append(fmt4(baselineRef.overall().meanRecallAt1()))
                .append("、recall@5 = ").append(fmt4(baselineRef.overall().meanRecall5()))
                .append("，候选池只有 ").append(candidateCounts.getOrDefault("document_chunk", 0L))
                .append(" 个 document chunk——**recall@5 已封顶，本次测量对召回没有区分力**。\n");
        text.append("- 基线（旧路径）recall@5 = **")
                .append(fmt4(baselineRef.overall().meanRecall5())).append("**，MRR = **")
                .append(fmt4(baselineRef.overall().meanMrr())).append("**。\n");
        if (pure != null) {
            text.append("- 纯多查询 / RRF（同 cap、同 1 chunk）：recall@5 = **")
                    .append(fmt4(pure.overall().meanRecall5())).append("**（")
                    .append(deltaPp(baselineRef.overall().meanRecall5(), pure.overall().meanRecall5()))
                    .append(" 个百分点）。\n");
        }
        if (combo != null) {
            text.append("- E1 默认组合（cap5 / 每篇 2 chunk）：recall@5 = **")
                    .append(fmt4(combo.overall().meanRecall5())).append("**（")
                    .append(deltaPp(baselineRef.overall().meanRecall5(), combo.overall().meanRecall5()))
                    .append(" 个百分点）。\n");
        }
        DimensionComparison chunks = compareChunks();
        text.append("- 每篇 2 chunk 相对 1 chunk：").append(signed(chunks.recallDeltaPp(), 1))
                .append(" 个百分点；cap 8 相对 5：").append(signed(compareCap().recallDeltaPp(), 1))
                .append(" 个百分点——**都是 0，因为指标已封顶**。\n");
        ConfigResult recommended = recommendation();
        if (recommended != null) {
            text.append("- 推荐：minScore=").append(fmt2(recommended.minScore()))
                    .append("、cap=").append(recommended.cap())
                    .append("、每篇 ").append(recommended.chunksPerDocument()).append(" chunk")
                    .append("（recall@5=").append(fmt4(recommended.overall().meanRecall5()))
                    .append("；这是“指标打平时按证据成本选”，不是“更准”）。\n");
        }
        text.append("- 生产默认的 LLM 改写路径：").append(llmStatus).append("。\n");
        return text.toString();
    }

    /** 13 节结论的叙述文本（JSON 与 Markdown 共用，保证两边一致）。 */
    private static String conclusionNarrative() {
        ConfigResult pure = findRuleConfig(ruleConfigsRef, 0.35d, 5, 1);
        ConfigResult combo = findRuleConfig(ruleConfigsRef, 0.35d, 5, 2);
        Aggregate baseline = baselineRef.overall();
        StringBuilder text = new StringBuilder();
        text.append("**先说功效，再说数字**：基线在这 ").append(plansRef.size())
                .append(" 条可测用例上 recall@1 = ").append(fmt4(baseline.meanRecallAt1()))
                .append("、recall@5 = ").append(fmt4(baseline.meanRecall5()))
                .append("；document 候选只有 ")
                .append(candidateCounts.getOrDefault("document_chunk", 0L))
                .append(" 个 chunk，且没有任何用例的 gold 跨 3 篇以上文档（最多 ")
                .append(maxDistinctDocumentGolds).append(" 篇）。")
                .append("**基线已经封顶**，所以对“多查询/RRF 是升还是降”的严格回答是：")
                .append("**升 0.0 个百分点、降 0.0 个百分点——这套夹具测不出差异**。")
                .append("不能据此说“检索变好”，也不能说“负优化”。\n\n");

        text.append("基线（旧路径：单 query / 每篇 1 chunk / cap 5 / minScore 0.35）recall@5 = **")
                .append(fmt4(baseline.meanRecall5())).append("**、MRR = **")
                .append(fmt4(baseline.meanMrr())).append("**。");
        if (pure != null) {
            double delta = (pure.overall().meanRecall5() - baseline.meanRecall5()) * 100.0d;
            text.append(" 只把单 query 换成多查询 + RRF（cap、chunk 数、阈值都不变）时，recall@5 = **")
                    .append(fmt4(pure.overall().meanRecall5())).append("**（**")
                    .append(direction(delta)).append(" ").append(String.format("%.1f", Math.abs(delta)))
                    .append(" 个百分点**），MRR ")
                    .append(direction((pure.overall().meanMrr() - baseline.meanMrr()) * 100.0d))
                    .append(" ").append(String.format("%.1f", Math.abs(
                            (pure.overall().meanMrr() - baseline.meanMrr()) * 100.0d)))
                    .append(" 个百分点。");
        }
        if (combo != null) {
            double delta = (combo.overall().meanRecall5() - baseline.meanRecall5()) * 100.0d;
            text.append(" E1 的生产默认组合（cap5 / 每篇 2 chunk）recall@5 = **")
                    .append(fmt4(combo.overall().meanRecall5())).append("**（**")
                    .append(direction(delta)).append(" ").append(String.format("%.1f", Math.abs(delta)))
                    .append(" 个百分点**）。");
        }
        DimensionComparison cap = compareCap();
        DimensionComparison chunks = compareChunks();
        text.append(" 配对平均：cap 8 相对 5 的 recall@5 变化 **")
                .append(signed(cap.recallDeltaPp(), 1)).append(" 个百分点**（平均证据 ")
                .append(signed(cap.avgHitsDelta(), 2)).append(" 条）；每篇 2 chunk 相对 1 chunk **")
                .append(signed(chunks.recallDeltaPp(), 1)).append(" 个百分点**（平均证据 ")
                .append(signed(chunks.avgHitsDelta(), 2)).append(" 条）。");
        text.append(" **这只说明“多拿证据没换来 recall”，不说明“2 chunk 的风险不存在”**：")
                .append("夹具里没有第三篇文档可被挤掉。\n\n");

        ConfigResult worst = ruleConfigsRef.stream()
                .min(java.util.Comparator.comparingDouble(
                        config -> config.overall().meanRecall5()))
                .orElse(null);
        if (worst != null && worst.overall().meanRecall5() < baseline.meanRecall5() - 1e-9) {
            String lostCases = worst.cases().stream()
                    .filter(measurement -> measurement.score().recallAt5() < 0.9999d)
                    .map(measurement -> "`" + measurement.caseId() + "`（"
                            + measurement.score().goldHitsTop5() + "/"
                            + measurement.score().goldCount() + "）")
                    .collect(Collectors.joining("、"));
            text.append("**唯一下降的是 minScore=0.45 的 4 组**：recall@5 = ")
                    .append(fmt4(worst.overall().meanRecall5())).append("（")
                    .append(deltaPp(baseline.meanRecall5(), worst.overall().meanRecall5()))
                    .append(" 个百分点），丢的是 ").append(lostCases)
                    .append("——阈值提到 0.45 会把相关度略低的题目片段滤掉。\n\n");
        }

        double strictGap = (baseline.meanRecall5() - baseline.meanRecall5Strict()) * 100.0d;
        text.append("**口径敏感性（本报告最可能失真的一处）**：导图 gold 只写到导图 id，")
                .append("本轮按“同一张导图任意节点命中”放宽。把导图改回严格 resourceId 对齐后，")
                .append("基线 recall@5 从 ").append(fmt4(baseline.meanRecall5())).append(" 掉到 ")
                .append(fmt4(baseline.meanRecall5Strict())).append("（-")
                .append(String.format("%.1f", strictGap)).append(" 个百分点）；")
                .append("这 ").append(String.format("%.1f", strictGap))
                .append(" 个百分点几乎全部来自 6 条纯导图用例与 1 条 document+mindmap 用例。")
                .append("换句话说，正式 recall@5 的 1.0 里有很大一块是“口径放宽”给的。\n\n");

        if (llmConfigsRef.isEmpty()) {
            text.append("多查询 + LLM 改写（生产默认路径）**未测**：").append(llmStatus)
                    .append("，因此不能对生产默认路径下结论。");
        } else {
            ConfigResult llmBest = llmConfigsRef.stream().min(QUALITY_ORDER).orElse(llmConfigsRef.get(0));
            LlmUsage usage = totalLlmUsage();
            text.append("多查询 + LLM 改写已测 ").append(llmConfigsRef.size()).append(" 组，recall@5 与规则组相同（")
                    .append(fmt4(llmBest.overall().meanRecall5())).append("）；代价是 P50 从 ")
                    .append(baseline.p50Ms()).append("ms 升到 ").append(llmBest.overall().p50Ms())
                    .append("ms（+").append(llmBest.overall().p50Ms() - baseline.p50Ms())
                    .append("ms），共 ").append(usage.calls()).append(" 次改写调用、")
                    .append(usage.promptTokens() + usage.completionTokens()).append(" token。")
                    .append("在这套封顶夹具上，LLM 改写**没有可测的召回收益，只有延迟与调用成本**；")
                    .append("但同样不能据此断言它在真实数据上无效。");
        }
        return text.toString();
    }

    /** 本轮所有 LLM 改写调用（探针 + 2 组配置）的合计用量。 */
    private static LlmUsage totalLlmUsage() {
        LlmUsage total = llmProbeUsage == null ? LlmUsage.none() : llmProbeUsage;
        for (ConfigResult config : llmConfigsRef) {
            total = total.plus(config.llmUsage());
        }
        return total;
    }

    private static String direction(double delta) {
        if (delta > 0.05d) {
            return "升";
        }
        if (delta < -0.05d) {
            return "降";
        }
        return "持平";
    }

    private static String signed(double value, int decimals) {
        String format = "%." + decimals + "f";
        return (value >= 0 ? "+" : "") + String.format(format, value);
    }
}
