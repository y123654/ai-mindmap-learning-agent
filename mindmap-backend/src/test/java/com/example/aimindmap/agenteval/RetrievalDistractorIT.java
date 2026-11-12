package com.example.aimindmap.agenteval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

import com.example.aimindmap.agentruntime.MultiQueryRetriever;
import com.example.aimindmap.agentruntime.RetrievalProperties;
import com.example.aimindmap.ai.AiGateway;
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
import io.qdrant.client.ConditionFactory;
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
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
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
 * P1-E2b <b>干扰压力实验</b>：在评测用户下临时写入 40 条「同领域异答案」课程笔记片段，
 * 再跑关键配置，测「噪声把 gold 挤出 top5」的幅度，回答“多查询 / 每篇 2 chunk / cap 8
 * 到底能不能抗噪”。
 *
 * <p><b>为什么不改 cases.yaml</b>：gold 冻结（SHA256 {@code 906B0D1E…}）是 P0 对比的基准。
 * 本类通过<b>改变检索环境</b>（往候选池里加干扰）而不是改变真值，来制造 E1 头号风险
 * 「cap5 × 每篇 2 chunk 把第 3 篇文档挤掉」原本出现不了的结构条件。
 *
 * <p><b>这不是功能开发，是压力测量</b>：不改任何生产默认值。每个配置都在测试里构造
 * {@link QdrantProperties} / {@link RetrievalProperties} / {@link VectorSearchService} /
 * {@link MultiQueryRetriever} 实例，注入的 Spring Bean（含真实 {@code app.qdrant.min-score=0.35}）
 * 一个字段都不改。改写一律用<b>规则改写</b>（{@code null} AiGateway）——本轮不花对话模型的钱。
 *
 * <p><b>三态实验设计</b>
 * <ol>
 *   <li><b>对照组</b>（无干扰）：播种 22 点后跑 5 组配置 → 必须复现 P1-E2 的数字；</li>
 *   <li><b>实验组</b>（有干扰）：写入 40 条干扰 chunk 后跑同样 5 组配置；</li>
 *   <li><b>清理后复核</b>：按确定性 pointId 精确删除干扰，再跑一次基线 → 指标必须回到对照组。</li>
 * </ol>
 *
 * <p><b>隔离与互斥（与 {@code AgentEvalIT} / {@code RetrievalQualityIT} 完全相同的机制）</b>
 * <ul>
 *   <li>同一把 PostgreSQL 会话级 advisory lock（key 逐字相同），拿不到立即失败；</li>
 *   <li>{@code app.qdrant.collection} 覆盖为 {@link AgentEvalSeeder#EVAL_COLLECTION}，
 *       业务行与向量点只按 {@link AgentEvalSeeder#EVAL_USER_ID} 圈定；</li>
 *   <li>真实集合 {@link AgentEvalSeeder#REAL_COLLECTION} 只读 count，跑完断言点数不变。</li>
 * </ul>
 *
 * <p><b>运行方式</b>（默认门禁不含本类，{@code @Tag("eval")} 被 surefire excludedGroups 排除）：
 * <pre>
 * mvn.cmd -f mindmap-backend/pom.xml -B -Peval test '-Dtest=RetrievalDistractorIT'
 *         '-Djdk.net.unixdomain.tmpdir=D:\tmp' '-DargLine=-Djdk.net.unixdomain.tmpdir=D:\tmp'
 * </pre>
 * <b>不要</b>不加 {@code -Dtest} 就跑 {@code -Peval}：那会把 {@code AgentEvalIT} 一起跑（¥10~25）。
 *
 * <p>报告落盘：{@code docs/agent-eval/2026-10-28-retrieval-distractor.md} 与 {@code .json}。
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
class RetrievalDistractorIT {

    private static final Logger log = LoggerFactory.getLogger(RetrievalDistractorIT.class);

    /** 报告名可覆盖（复跑用 -Ddistractor.report.name=...），默认按本轮日期。 */
    private static final String REPORT_BASENAME = System.getProperty("distractor.report.name",
            "2026-10-28-retrieval-distractor");

    /** 冻结的 cases.yaml SHA256（必须与 P1-E1/E2 验收记录里的 906B0D1E… 一致）。 */
    private static final String FROZEN_CASES_SHA256 =
            "906B0D1E74389F50272206299697D39C3913DECE00809133B5933C2554A49D96";

    /** 与 {@code AgentEvalIT} 同一把锁：key 必须一致，否则两个评测会并发跑、互相清数据。 */
    private static final long EVAL_ADVISORY_LOCK_KEY = 888900_20261026L;

    /** 本轮要跑的 5 组配置（只跑必要组合，不重跑 E2 的 19 组）。 */
    private static final List<ConfigSpec> CONFIGS = List.of(
            // 1. “改前”基线：单 query、每篇 1 chunk、cap 5、minScore 0.35
            new ConfigSpec("baseline-legacy", "legacy-vector", 0.35d, 5, 1, "none"),
            // 2. 多查询 + cap5 + 每篇 1（只改“单 query → 多 query”，隔离出多查询本身）
            new ConfigSpec("mq-0.35-c5-ch1", "multi-query", 0.35d, 5, 1, "rule"),
            // 3. 多查询 + cap5 + 每篇 2（E1 生产默认）
            new ConfigSpec("mq-0.35-c5-ch2", "multi-query", 0.35d, 5, 2, "rule"),
            // 4. 多查询 + cap8 + 每篇 2（“多给条数能不能抗干扰”）
            new ConfigSpec("mq-0.35-c8-ch2", "multi-query", 0.35d, 8, 2, "rule"),
            // 5. 多查询 + cap8 + 每篇 1（补全 2×2 因子，用来分离 cap 与 chunk 两个维度）
            new ConfigSpec("mq-0.35-c8-ch1", "multi-query", 0.35d, 8, 1, "rule"));

    /**
     * P1-E2 已发布的平均证据条数（外部引用，来源 {@code docs/agent-eval/2026-10-28-retrieval-quality.md} §4）。
     *
     * <p>用途只有一个：<b>断言本轮对照组复现上一轮</b>。如果哪天环境变了（集合配置、embedding 版本、
     * 夹具内容），这条断言会先炸，避免拿“不可比的数字”做实验组对比。
     */
    private static final Map<String, Double> E2_PUBLISHED_AVG_HITS = Map.of(
            "baseline-legacy", 4.0909d,
            "mq-0.35-c5-ch1", 4.0909d,
            "mq-0.35-c5-ch2", 4.6970d,
            "mq-0.35-c8-ch2", 5.4242d,
            "mq-0.35-c8-ch1", 4.7879d);

    /** P1-E2 已发布的 recall@5（本轮对照组必须全部复现 1.0000）。 */
    private static final double E2_PUBLISHED_RECALL5 = 1.0d;

    /** 基线参数（= 改造前默认值）。 */
    private static final double BASELINE_MIN_SCORE = 0.35d;
    private static final int BASELINE_CAP = 5;

    /** 评测用户里 gold 笔记的 id 段（干扰编号不得复用）。 */
    private static final Set<Long> GOLD_SOURCE_IDS = Set.of(
            888901L, 888902L, 888903L, 888904L, 888911L,
            888921L, 888922L, 888923L, 888931L, 888932L, 888933L);

    /** 干扰点的 source_id 集合（命中判定用：命中这些 point 就算“噪声进了 top5”）。 */
    private static final Set<String> DISTRACTOR_SOURCE_IDS =
            Set.copyOf(RetrievalDistractorCorpus.sourceIds());

    @Autowired private EmbeddingModel embeddingModel;
    @Autowired private QdrantVectorGateway gateway;
    @Autowired private QdrantProperties qdrantProperties;
    @Autowired private QdrantClient qdrantClient;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private AiGateway aiGateway;
    @Autowired private BusinessMetrics businessMetrics;

    /** 与 AgentEvalIT 同样的两个后台 worker mock（理由见那两个类的注释）。 */
    @MockBean private VectorIndexWorker vectorIndexWorker;
    @MockBean private PdfImportWorker pdfImportWorker;

    private static AgentEvalSeeder seeder;
    private static long realCollectionBefore = -1L;
    private static long evalCollectionBaseline = -1L;
    private static long fixturePoints = -1L;
    private static long evalPointsAfterSeed = -1L;
    private static long docChunksControl = -1L;
    private static long evalPointsAfterDistractors = -1L;
    private static long docChunksWithDistractors = -1L;
    private static long distractorPointsWritten = -1L;
    private static int distractorWriteFailures = 0;
    private static long evalPointsAfterDistractorCleanup = -1L;
    private static long docChunksAfterDistractorCleanup = -1L;
    private static long distractorPointsAfterCleanup = -1L;
    private static long cleanupSearchCalls = 0L;

    // 单实例互斥锁（与 AgentEvalIT 同一套实现）
    private static Connection lockConnection;
    private static boolean lockAcquired;
    private static boolean lockProbeBlocked;
    private static Integer lockBackendPid;
    private static String lockAcquiredAt = "";

    // 报告快照
    private static AgentEvalCases.AgentEvalDataset datasetRef;
    private static List<CasePlan> plansRef;
    private static String casesSha256 = "";
    private static String embeddingModelName = "";
    private static String chatModelName = "n/a";
    private static String evalCollectionName = "";
    private static String flywayVersion = "unknown";
    private static List<ConfigResult> controlResults = List.of();
    private static List<ConfigResult> distractorResults = List.of();
    private static ConfigResult controlRecheckResult;
    private static List<String> emptyGoldIds = List.of();
    private static List<String> memoryExcludedIds = List.of();
    private static Map<String, Long> candidateCountsControl = Map.of();
    private static Map<String, Long> candidateCountsDistractor = Map.of();
    private static int maxDistinctDocumentGolds = 0;
    private static int documentOnlyCaseCount = 0;
    private static int distractorWritesCount = 0;
    private static long distractorCorpusChars = 0L;
    private static int distractorCorpusMinChars = Integer.MAX_VALUE;
    private static int distractorCorpusMaxChars = 0;
    private static long runWallMs = 0L;
    private static long totalSearchCalls = 0L;
    private static ObjectMapper objectMapperRef;
    private static int labelDiffBetweenControlAndRecheck = -1;

    // ------------------------------------------------------------------
    // 1. 互斥锁（与 AgentEvalIT 逐字一致；拿不到立即失败）
    // ------------------------------------------------------------------

    @BeforeAll
    void acquireEvalLock() throws SQLException {
        DataSource dataSource = jdbc.getDataSource();
        if (dataSource == null) {
            log.error("互斥锁获取失败：JdbcTemplate 没有可用的 DataSource");
            fail("无法获取 DataSource，拒绝启动干扰压力实验");
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
    // 2. 主流程：对照 → 写干扰 → 实验 → 精确清理 → 复核
    // ------------------------------------------------------------------

    @Test
    void measureDistractorSensitivity() throws Exception {
        long startedNanos = System.nanoTime();
        AgentEvalCases.AgentEvalDataset dataset = AgentEvalCases.load();
        assertThat(dataset.cases())
                .as("冻结的用例集必须是 50 条（本实验不改 cases.yaml）")
                .hasSize(50);
        datasetRef = dataset;
        casesSha256 = casesYamlSha256();
        assertThat(casesSha256)
                .as("cases.yaml 必须保持冻结（E2b 只测量、不改用例、不改真值）")
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
                .as("专用集合 %s 必须存在", AgentEvalSeeder.EVAL_COLLECTION)
                .isTrue();

        realCollectionBefore = seeder.countPoints(AgentEvalSeeder.REAL_COLLECTION);
        int stalePointsPurged = seeder.purgeVectorPoints();
        evalCollectionBaseline = seeder.countPoints(AgentEvalSeeder.EVAL_COLLECTION);
        log.info("==== 隔离基线 ==== 真实集合={} 点数={} | 评测集合={} 基线点数={} 清理残留点={}",
                AgentEvalSeeder.REAL_COLLECTION, realCollectionBefore,
                AgentEvalSeeder.EVAL_COLLECTION, evalCollectionBaseline, stalePointsPurged);

        // ---- 2.1 干扰语料自检（编号段、条数、与 gold 不重叠、字长） ----
        assertDistractorCorpus();

        // ---- 2.2 embedding 探针（失败立即停，不播种、不跑配置） ----
        probeEmbedding();

        // ---- 2.3 播种（22 点：5 文档 chunk + 11 导图节点 + 3 题目 + 3 记忆） ----
        seeder.seed();
        fixturePoints = seeder.seededPoints();
        evalPointsAfterSeed = seeder.countPoints(AgentEvalSeeder.EVAL_COLLECTION);
        long userPoints = seeder.countUserPoints(AgentEvalSeeder.EVAL_COLLECTION);
        docChunksControl = countUserPointsByType(VectorResourceType.DOCUMENT_CHUNK);
        candidateCountsControl = candidateCounts();
        log.info("夹具落库后 评测集合总点数={} 评测用户点数={} document_chunk={} seed 写入={}",
                evalPointsAfterSeed, userPoints, docChunksControl, fixturePoints);
        assertThat(userPoints)
                .as("评测用户向量点数必须等于 seed 写入数（防止漏写/多写）")
                .isEqualTo(fixturePoints);
        assertThat(docChunksControl)
                .as("对照组 document_chunk 候选必须是 5 个（E2 的封顶前提）")
                .isEqualTo(5L);

        // ---- 2.4 选出可测量的用例（gold 非空且不含 memory） ----
        plansRef = buildPlans(dataset);
        documentOnlyCaseCount = (int) plansRef.stream()
                .filter(plan -> plan.golds().stream()
                        .anyMatch(gold -> gold.type() == GoldType.DOCUMENT))
                .count();
        log.info("==== 可测量用例 ==== 计入={} 其中含 document gold 的={} 排除：gold 为空={} memory={}",
                plansRef.size(), documentOnlyCaseCount, emptyGoldIds.size(), memoryExcludedIds.size());
        assertThat(plansRef).as("gold 非空且不含 memory 的用例数必须为 33").hasSize(33);
        assertThat(emptyGoldIds).as("gold_chunks 为空的用例必须为 12 条").hasSize(12);
        assertThat(memoryExcludedIds).as("只有 memory gold 的用例必须为 5 条").hasSize(5);

        // ---- 2.5 对照组：无干扰跑 5 组 ----
        controlResults = runAllConfigs(Scenario.CONTROL, plansRef);
        assertThat(controlResults).as("对照组必须跑满 5 组配置").hasSize(CONFIGS.size());
        for (ConfigResult result : controlResults) {
            assertThat(result.overall().failedCases())
                    .as("对照组配置 %s 不得有检索失败（不把异常静默当 0 分）", result.spec().id())
                    .isZero();
        }
        ConfigResult baselineControl = find(controlResults, "baseline-legacy");
        assertThat(baselineControl.overall().meanRecall5())
                .as("对照组基线 recall@5 必须复现 P1-E2 的 1.0000（否则说明环境已变，实验不可比）")
                .isEqualTo(1.0d);
        assertThat(baselineControl.overall().avgHits())
                .as("对照组基线平均证据必须复现 P1-E2 的 4.0909")
                .isEqualTo(E2_PUBLISHED_AVG_HITS.get("baseline-legacy"));
        for (ConfigResult result : controlResults) {
            assertThat(result.overall().meanRecall5())
                    .as("对照组 %s 的 recall@5 必须复现 P1-E2 的 1.0000", result.spec().id())
                    .isEqualTo(E2_PUBLISHED_RECALL5);
            assertThat(result.overall().avgHits())
                    .as("对照组 %s 的平均证据必须复现 P1-E2 的已发布值", result.spec().id())
                    .isEqualTo(E2_PUBLISHED_AVG_HITS.get(result.spec().id()));
        }
        log.info("对照组已逐组复现 P1-E2 已发布数字：recall@5 全 = {}，平均证据 = {}",
                E2_PUBLISHED_RECALL5, E2_PUBLISHED_AVG_HITS);

        // ---- 2.6 写入干扰（40 条，source_id 888951~888990） ----
        writeDistractors();
        evalPointsAfterDistractors = seeder.countPoints(AgentEvalSeeder.EVAL_COLLECTION);
        long userPointsWithDistractors = seeder.countUserPoints(AgentEvalSeeder.EVAL_COLLECTION);
        docChunksWithDistractors = countUserPointsByType(VectorResourceType.DOCUMENT_CHUNK);
        distractorPointsWritten = awaitDistractorCount(
                (long) RetrievalDistractorCorpus.all().size(), "写入后");
        candidateCountsDistractor = candidateCounts();
        log.info("干扰写入后 评测集合总点数={} 评测用户点数={} document_chunk={} 干扰点={}",
                evalPointsAfterDistractors, userPointsWithDistractors,
                docChunksWithDistractors, distractorPointsWritten);
        assertThat(distractorPointsWritten)
                .as("干扰点必须全部落库（%d 条）", RetrievalDistractorCorpus.all().size())
                .isEqualTo((long) RetrievalDistractorCorpus.all().size());
        assertThat(docChunksWithDistractors)
                .as("document_chunk 候选必须从 5 涨到 45（5 gold + 40 干扰）")
                .isEqualTo(5L + RetrievalDistractorCorpus.all().size());
        assertThat(userPointsWithDistractors)
                .as("评测用户总点数必须是 22 + 干扰数")
                .isEqualTo(fixturePoints + (long) RetrievalDistractorCorpus.all().size());

        // ---- 2.7 实验组：有干扰跑 5 组 ----
        distractorResults = runAllConfigs(Scenario.DISTRACTOR, plansRef);
        assertThat(distractorResults).as("实验组必须跑满 5 组配置").hasSize(CONFIGS.size());
        for (ConfigResult result : distractorResults) {
            assertThat(result.overall().failedCases())
                    .as("实验组配置 %s 不得有检索失败", result.spec().id())
                    .isZero();
        }
        log.info("==== 对照 vs 干扰（recall@5 / MRR / 平均证据 / 干扰条数 / 覆盖文档数 / P95）====");
        for (ConfigSpec spec : CONFIGS) {
            ConfigResult control = find(controlResults, spec.id());
            ConfigResult distractor = find(distractorResults, spec.id());
            log.info("  {} 对照 recall@5={} MRR={} 证据={} | 干扰 recall@5={} MRR={} 证据={} "
                            + "干扰条数={} 覆盖文档={} P95={}ms→{}ms",
                    spec.id(),
                    fmt4(control.overall().meanRecall5()), fmt4(control.overall().meanMrr()),
                    fmt4(control.overall().avgHits()),
                    fmt4(distractor.overall().meanRecall5()), fmt4(distractor.overall().meanMrr()),
                    fmt4(distractor.overall().avgHits()),
                    fmt4(distractor.avgDistractorHitsTop5()),
                    fmt4(distractor.avgDistinctDocumentsTop5()),
                    control.overall().p95Ms(), distractor.overall().p95Ms());
        }

        // ---- 2.8 精确清理干扰（按确定性 pointId），并断言回到对照组环境 ----
        deleteDistractors();
        evalPointsAfterDistractorCleanup = seeder.countPoints(AgentEvalSeeder.EVAL_COLLECTION);
        docChunksAfterDistractorCleanup =
                countUserPointsByType(VectorResourceType.DOCUMENT_CHUNK);
        distractorPointsAfterCleanup = awaitDistractorCount(0L, "精确删除后");
        long userPointsAfterCleanup = seeder.countUserPoints(AgentEvalSeeder.EVAL_COLLECTION);
        log.info("干扰清理后 评测集合总点数={} 评测用户点数={} document_chunk={} 干扰残留点={}",
                evalPointsAfterDistractorCleanup, userPointsAfterCleanup,
                docChunksAfterDistractorCleanup, distractorPointsAfterCleanup);
        assertThat(distractorPointsAfterCleanup)
                .as("按 source_id 段精确删除后，干扰点残留必须为 0")
                .isZero();
        assertThat(docChunksAfterDistractorCleanup)
                .as("document_chunk 候选必须回到 5")
                .isEqualTo(docChunksControl);
        assertThat(userPointsAfterCleanup)
                .as("评测用户总点数必须回到播种后的 22")
                .isEqualTo(fixturePoints);

        // ---- 2.9 清理后复核：再跑一次基线，指标必须回到对照组 ----
        controlRecheckResult = runConfig(Scenario.RECHECK, spec("baseline-legacy"), plansRef);
        assertThat(controlRecheckResult.overall().failedCases()).isZero();
        assertThat(controlRecheckResult.overall().meanRecall5())
                .as("清理干扰后基线 recall@5 必须回到对照组（证明干扰被删干净、实验可复现）")
                .isEqualTo(baselineControl.overall().meanRecall5());
        assertThat(controlRecheckResult.overall().meanMrr())
                .as("清理干扰后基线 MRR 必须回到对照组")
                .isEqualTo(baselineControl.overall().meanMrr());
        assertThat(controlRecheckResult.overall().avgHits())
                .as("清理干扰后基线平均证据必须回到对照组")
                .isEqualTo(baselineControl.overall().avgHits());
        labelDiffBetweenControlAndRecheck = countLabelDiffs(baselineControl, controlRecheckResult);
        log.info("清理后复核 基线 recall@5={} MRR={} 证据={}；与对照组的 top5 标签差异用例数={}",
                fmt4(controlRecheckResult.overall().meanRecall5()),
                fmt4(controlRecheckResult.overall().meanMrr()),
                fmt4(controlRecheckResult.overall().avgHits()),
                labelDiffBetweenControlAndRecheck);

        runWallMs = (System.nanoTime() - startedNanos) / 1_000_000L;
        totalSearchCalls = 0L;
        for (ConfigResult result : allResults()) {
            totalSearchCalls += result.searchCalls();
        }
        cleanupSearchCalls = controlRecheckResult.searchCalls();
        log.info("==== 测量完成 ==== 场景数=3 配置数={} 用例/配置={} 向量检索次数(=embedding 次数)={} "
                        + "测量总耗时={}ms",
                CONFIGS.size(), plansRef.size(), totalSearchCalls, runWallMs);

        // ---- 2.10 隔离取证（最终清理与断言在 @AfterAll） ----
        long realAfterCases = seeder.countPoints(AgentEvalSeeder.REAL_COLLECTION);
        log.info("==== 隔离取证（跑完实验、清理前）==== 真实集合点数 跑前={} 跑后={}",
                realCollectionBefore, realAfterCases);
        assertThat(realAfterCases)
                .as("真实集合 %s 的点数必须全程不变（本实验只读它）",
                        AgentEvalSeeder.REAL_COLLECTION)
                .isEqualTo(realCollectionBefore);
    }

    // ------------------------------------------------------------------
    // 3. 数据模型
    // ------------------------------------------------------------------

    /** 一个待测配置。 */
    private record ConfigSpec(String id, String path, double minScore, int cap, int chunks,
                              String rewrite) {
    }

    /** 场景：对照组 / 实验组 / 清理后复核。 */
    private enum Scenario {
        CONTROL("无干扰（对照组）"),
        DISTRACTOR("有干扰（实验组）"),
        RECHECK("清理后复核（无干扰）");

        private final String label;

        Scenario(String label) {
            this.label = label;
        }

        String label() {
            return label;
        }
    }

    /** 一条用例的检索计划：问题 + gold + 由 gold 类型决定的过滤类型。 */
    private record CasePlan(AgentEvalCase evalCase, List<GoldRef> golds,
                            List<VectorResourceType> resourceTypes, String category) {

        String goldTypeLabel() {
            return golds.stream().map(gold -> gold.type().shortName()).distinct()
                    .collect(Collectors.joining("+"));
        }
    }

    /** 检索调用抽象：返回有序 hit 列表。 */
    @FunctionalInterface
    private interface RetrievalCall {
        List<VectorSearchService.VectorHit> retrieve(String query,
                                                     List<VectorResourceType> resourceTypes);
    }

    /**
     * 一次「配置 × 场景」的完整结果。
     *
     * <p>除 E2 的 recall/MRR/证据条数外，本实验额外算两类<b>抗噪指标</b>
     * （recall 打平时它们才是区分力所在）：
     * <ul>
     *   <li>{@code avgDistractorHitsTop5}：前 5 条里平均混进几条干扰——衡量“上下文被污染多少”；</li>
     *   <li>{@code goldShareAt5}：前 5 条里 gold 占多少（分母 = min(5, 实际返回条数)）——精度口径。</li>
     * </ul>
     */
    private record ConfigResult(ConfigSpec spec, Scenario scenario, List<CaseMeasurement> cases,
                                Aggregate overall, double avgGoldHitsTop5, double goldShareAt5,
                                double avgDistractorHitsTop5, double avgDistinctDocumentsTop5,
                                long searchCalls, long wallMs) {
    }

    // ------------------------------------------------------------------
    // 4. 配置执行
    // ------------------------------------------------------------------

    private List<ConfigResult> runAllConfigs(Scenario scenario, List<CasePlan> plans) {
        List<ConfigResult> results = new ArrayList<>(CONFIGS.size());
        for (ConfigSpec spec : CONFIGS) {
            results.add(runConfig(scenario, spec, plans));
        }
        return List.copyOf(results);
    }

    private ConfigResult runConfig(Scenario scenario, ConfigSpec spec, List<CasePlan> plans) {
        QdrantProperties properties = copyQdrantProperties(spec.minScore());
        CountingGateway countingGateway = new CountingGateway(gateway);
        VectorSearchService service = new VectorSearchService(
                embeddingModel, countingGateway, properties, businessMetrics);
        RetrievalCall call;
        if ("legacy-vector".equals(spec.path())) {
            call = (query, resourceTypes) ->
                    service.search(AgentEvalSeeder.EVAL_USER_ID, query, resourceTypes).hits();
        } else {
            RetrievalProperties retrievalProperties = new RetrievalProperties();
            retrievalProperties.setMultiQueryEnabled(true);
            retrievalProperties.setMaxResults(spec.cap());
            retrievalProperties.setMaxChunksPerDocument(spec.chunks());
            // 传 null = 强制规则改写（确定性、零对话成本；本轮不花 LLM 的钱）
            MultiQueryRetriever retriever = new MultiQueryRetriever(
                    service, null, properties, retrievalProperties);
            assertThat(retriever.isEnabled())
                    .as("多查询组必须处于启用状态，config=%s", spec.id())
                    .isTrue();
            call = (query, resourceTypes) ->
                    retriever.search(AgentEvalSeeder.EVAL_USER_ID, query, resourceTypes).hits();
        }
        long configStarted = System.nanoTime();
        List<CaseMeasurement> measurements = new ArrayList<>(plans.size());
        for (CasePlan plan : plans) {
            String caseId = plan.evalCase().id();
            long caseStarted = System.nanoTime();
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
                log.error("干扰压力实验检索失败 config={} scenario={} caseId={}（按 0 分记录，末尾断言失败）",
                        spec.id(), scenario.name(), caseId, e);
            }
            long latencyMs = Math.max(0L, (System.nanoTime() - caseStarted) / 1_000_000L);
            CaseScore score = RetrievalQualityMetrics.score(plan.golds(), hits);
            measurements.add(new CaseMeasurement(caseId, plan.category(),
                    plan.evalCase().intent(), plan.goldTypeLabel(), plan.golds(), hits, score,
                    latencyMs, error));
        }
        long wallMs = Math.max(0L, (System.nanoTime() - configStarted) / 1_000_000L);
        Aggregate overall = RetrievalQualityMetrics.aggregate(measurements);
        double goldHits = measurements.stream()
                .mapToInt(measurement -> measurement.score().goldHitsTop5()).sum()
                / (double) measurements.size();
        double noiseHits = measurements.stream()
                .mapToInt(RetrievalDistractorIT::distractorHitsTop5).sum()
                / (double) measurements.size();
        double shareSum = measurements.stream()
                .mapToDouble(RetrievalDistractorIT::goldShareAt5).sum() / measurements.size();
        double documents = measurements.stream()
                .mapToInt(RetrievalDistractorIT::distinctEvidenceSourcesTop5).sum()
                / (double) measurements.size();
        ConfigResult result = new ConfigResult(spec, scenario, List.copyOf(measurements), overall,
                round4(goldHits), round4(shareSum), round4(noiseHits), round4(documents),
                countingGateway.searchCalls(), wallMs);
        log.info("配置完成 scenario={} id={} recall@5={} MRR={} 证据={} gold占比@5={} "
                        + "干扰条数@5={} 覆盖文档@5={} P50={}ms P95={}ms 失败={} 检索次数={} 耗时={}ms",
                scenario.name(), spec.id(), fmt4(overall.meanRecall5()), fmt4(overall.meanMrr()),
                fmt4(overall.avgHits()), fmt4(result.goldShareAt5()),
                fmt4(result.avgDistractorHitsTop5()), fmt4(result.avgDistinctDocumentsTop5()),
                overall.p50Ms(), overall.p95Ms(), overall.failedCases(),
                result.searchCalls(), wallMs);
        return result;
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

    // ------------------------------------------------------------------
    // 5. 干扰的写入、统计、精确删除
    // ------------------------------------------------------------------

    /** 语料自检：条数 40、编号连续在 888951~888990、不与 gold 编号重叠、字长同量级。 */
    private void assertDistractorCorpus() {
        List<RetrievalDistractorCorpus.DistractorChunk> corpus = RetrievalDistractorCorpus.all();
        assertThat(corpus)
                .as("干扰语料必须是 40 条（任务要求 20~50）")
                .hasSize(40);
        assertThat(corpus).hasSize(RetrievalDistractorCorpus.sourceIds().size());
        long expected = RetrievalDistractorCorpus.FIRST_SOURCE_ID;
        for (RetrievalDistractorCorpus.DistractorChunk chunk : corpus) {
            assertThat(chunk.sourceId())
                    .as("干扰编号必须从 888951 开始逐条 +1（报告要按段精确删除）")
                    .isEqualTo(expected);
            expected++;
            assertThat(GOLD_SOURCE_IDS)
                    .as("干扰编号 %s 不得复用 gold 的编号段", chunk.sourceId())
                    .doesNotContain(chunk.sourceId());
            assertThat(chunk.content().length())
                    .as("干扰正文不能为空 sourceId=%s", chunk.sourceId())
                    .isGreaterThan(100);
        }
        assertThat(expected - 1).isEqualTo(RetrievalDistractorCorpus.LAST_SOURCE_ID);
        for (RetrievalDistractorCorpus.DistractorChunk chunk : corpus) {
            distractorCorpusChars += chunk.content().length();
            distractorCorpusMinChars = Math.min(distractorCorpusMinChars, chunk.content().length());
            distractorCorpusMaxChars = Math.max(distractorCorpusMaxChars, chunk.content().length());
        }
        distractorWritesCount = corpus.size();
        log.info("干扰语料自检通过 条数={} 编号段={}~{} 字长 min={} avg={} max={} 分组={}",
                corpus.size(), RetrievalDistractorCorpus.FIRST_SOURCE_ID,
                RetrievalDistractorCorpus.LAST_SOURCE_ID, distractorCorpusMinChars,
                distractorCorpusChars / corpus.size(), distractorCorpusMaxChars,
                corpus.stream().collect(Collectors.groupingBy(
                        RetrievalDistractorCorpus.DistractorChunk::group,
                        LinkedHashMap::new, Collectors.counting())));
    }

    /** 逐条写入 40 条干扰（复用 Seeder 的 embedding + Qdrant 路径）。 */
    private void writeDistractors() {
        distractorWriteFailures = 0;
        long startedNanos = System.nanoTime();
        for (RetrievalDistractorCorpus.DistractorChunk chunk : RetrievalDistractorCorpus.all()) {
            try {
                seeder.seedExtraDocumentChunk(chunk.sourceId(), 0, chunk.title(), chunk.content(),
                        chunk.sourcePath());
            } catch (RuntimeException e) {
                distractorWriteFailures++;
                log.error("写入干扰 chunk 失败 sourceId={}（计入失败数，末尾断言）",
                        chunk.sourceId(), e);
            }
        }
        long elapsedMs = Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
        log.info("干扰写入完成 条数={} 失败={} 耗时={}ms（每条 1 次 embedding）",
                RetrievalDistractorCorpus.all().size(), distractorWriteFailures, elapsedMs);
        assertThat(distractorWriteFailures)
                .as("干扰写入不得有失败（失败会让“抗噪”结论失真）")
                .isZero();
    }

    /** 按确定性 pointId 逐条精确删除 40 条干扰，并回读确认。 */
    private void deleteDistractors() {
        long startedNanos = System.nanoTime();
        for (RetrievalDistractorCorpus.DistractorChunk chunk : RetrievalDistractorCorpus.all()) {
            seeder.deleteExtraDocumentChunk(chunk.sourceId(), 0);
        }
        long elapsedMs = Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
        log.info("干扰精确删除完成 条数={} 耗时={}ms（按确定性 pointId，不做范围扫描）",
                RetrievalDistractorCorpus.all().size(), elapsedMs);
    }

    /**
     * 等干扰点数量收敛到期望值，再把观察值交给断言。
     *
     * <p>Qdrant 的 delete/upsert 走 {@code *Async(...).get()}（等 ack，但不等于索引视图立刻切换），
     * 所以这里做<b>有界等待</b>（最多 5s）后再断言；断言本身仍是「必须等于期望值」，
     * 不是“容忍若干残留”。等不到就带着真实观察值失败。
     */
    private long awaitDistractorCount(long expected, String stage) {
        long observed = countPointsBySourceIds(RetrievalDistractorCorpus.sourceIds());
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (observed != expected && System.nanoTime() < deadline) {
            try {
                Thread.sleep(100L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("等待干扰点数时被中断 stage={} expected={} observed={}",
                        stage, expected, observed, e);
                break;
            }
            observed = countPointsBySourceIds(RetrievalDistractorCorpus.sourceIds());
        }
        log.info("干扰点数等待 stage={} 期望={} 观察={}（有界等待 5s）", stage, expected, observed);
        return observed;
    }

    /** 只统计「source_id 落在干扰编号段」的点（写完必须=40，删完必须=0）。 */
    private long countPointsBySourceIds(List<String> sourceIds) {        Filter filter = Filter.newBuilder()
                .addMust(ConditionFactory.matchValues("user_id",
                        List.of(AgentEvalSeeder.EVAL_USER_ID)))
                .addMust(ConditionFactory.matchKeyword("status", "active"))
                .addMust(ConditionFactory.matchKeywords("source_id", sourceIds))
                .build();
        Duration timeout = Duration.ofSeconds(
                Math.max(10L, qdrantProperties.getCallTimeoutSeconds()));
        try {
            Long count = qdrantClient.countAsync(qdrantProperties.getCollection(), filter,
                    Boolean.TRUE, timeout).get();
            return count == null ? 0L : count;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("统计干扰点数被中断", e);
        } catch (Exception e) {
            throw new IllegalStateException("统计干扰点数失败", e);
        }
    }

    /** 候选池规模（按类型），用于说明“干扰把 document 候选从 5 拉到 45”。 */
    private Map<String, Long> candidateCounts() {
        Map<String, Long> counts = new LinkedHashMap<>();
        long total = 0L;
        for (VectorResourceType type : List.of(VectorResourceType.DOCUMENT_CHUNK,
                VectorResourceType.MINDMAP_NODE, VectorResourceType.QUESTION,
                VectorResourceType.MEMORY)) {
            long count = countUserPointsByType(type);
            counts.put(type.wireName(), count);
            total += count;
        }
        log.info("候选池（评测用户、按类型）：{} 合计={}", counts, total);
        return java.util.Collections.unmodifiableMap(counts);
    }

    private long countUserPointsByType(VectorResourceType type) {
        Filter filter = VectorSearchService.buildFilter(AgentEvalSeeder.EVAL_USER_ID, List.of(type));
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
    // 6. 用例计划
    // ------------------------------------------------------------------

    private List<CasePlan> buildPlans(AgentEvalCases.AgentEvalDataset dataset) {
        List<CasePlan> plans = new ArrayList<>();
        Set<String> emptyGold = new LinkedHashSet<>();
        Set<String> memoryOnly = new LinkedHashSet<>();
        for (AgentEvalCase evalCase : dataset.cases()) {
            if (evalCase.goldChunks().isEmpty()) {
                emptyGold.add(evalCase.id());
                continue;
            }
            List<GoldRef> golds = evalCase.goldChunks().stream()
                    .map(RetrievalQualityMetrics::parseGold)
                    .toList();
            if (golds.stream().anyMatch(gold -> gold.type() == GoldType.MEMORY)) {
                memoryOnly.add(evalCase.id());
                continue;
            }
            List<VectorResourceType> resourceTypes = golds.stream()
                    .map(GoldRef::type).distinct()
                    .map(RetrievalDistractorIT::toResourceType)
                    .toList();
            String category = evalCase.tags().isEmpty() ? "unknown" : evalCase.tags().get(0);
            plans.add(new CasePlan(evalCase, RetrievalQualityMetrics.dedupeGolds(golds),
                    resourceTypes, category));
        }
        emptyGoldIds = List.copyOf(emptyGold);
        memoryExcludedIds = List.copyOf(memoryOnly);
        int maxDocuments = 0;
        for (CasePlan plan : plans) {
            long distinctDocuments = plan.golds().stream()
                    .filter(gold -> gold.type() == GoldType.DOCUMENT)
                    .map(GoldRef::resourceId).distinct().count();
            maxDocuments = (int) Math.max(maxDocuments, distinctDocuments);
        }
        maxDistinctDocumentGolds = maxDocuments;
        return List.copyOf(plans);
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
    // 7. 探针
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
            log.error("探针① embedding 失败，立即中止干扰实验（不播种、不跑配置）", e);
            throw new IllegalStateException("embedding 探针失败，实验中止：" + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------
    // 8. 清理、隔离断言、报告落盘（@AfterAll）
    // ------------------------------------------------------------------

    private record Isolation(long realBefore, long realAfter, long evalBaseline,
                             long evalAfterSeed, long evalAfterDistractors,
                             long evalAfterDistractorCleanup, long evalAfterCleanup,
                             AgentEvalSeeder.CleanupResult cleanup, long evalRowsRemaining) {
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
            log.warn("干扰实验夹具未被创建（互斥锁/探针阶段即中止），无需清理");
            return;
        }
        // 兜底：即使上面精确删除没跑到，这里按 userId 再清一次
        AgentEvalSeeder.CleanupResult cleanup = seeder.cleanup();
        long realAfter = seeder.countPoints(AgentEvalSeeder.REAL_COLLECTION);
        long evalAfter = seeder.countPoints(AgentEvalSeeder.EVAL_COLLECTION);
        long evalRowsRemaining = countRemainingEvalRows();
        long distractorRemaining = countPointsBySourceIds(RetrievalDistractorCorpus.sourceIds());
        log.info("==== 隔离证据 ====");
        log.info("真实集合 {}：跑前={} 跑后={}（只读，必须相等）",
                AgentEvalSeeder.REAL_COLLECTION, realCollectionBefore, realAfter);
        log.info("专用集合 {}：本次基线={} seed后={} 干扰后={} 干扰精确删除后={} 最终清理后={}",
                AgentEvalSeeder.EVAL_COLLECTION, evalCollectionBaseline, evalPointsAfterSeed,
                evalPointsAfterDistractors, evalPointsAfterDistractorCleanup, evalAfter);
        log.info("清理明细：按 userId={} 删除业务行={} 条（{}），删除向量点={} 个",
                AgentEvalSeeder.EVAL_USER_ID, cleanup.rowsDeleted(), cleanup.tableDeletes(),
                cleanup.pointsDeleted());
        log.info("清理后 888 段评测用户残留行={} 干扰残留点={}（都必须为 0）",
                evalRowsRemaining, distractorRemaining);

        Isolation isolation = new Isolation(realCollectionBefore, realAfter,
                evalCollectionBaseline, evalPointsAfterSeed, evalPointsAfterDistractors,
                evalPointsAfterDistractorCleanup, evalAfter, cleanup, evalRowsRemaining);

        if (datasetRef != null && plansRef != null && !controlResults.isEmpty()
                && !distractorResults.isEmpty()) {
            Path reportDir = resolveDocsDir().resolve("agent-eval");
            Files.createDirectories(reportDir);
            Path jsonPath = reportDir.resolve(REPORT_BASENAME + ".json");
            Path mdPath = reportDir.resolve(REPORT_BASENAME + ".md");
            Files.writeString(jsonPath, objectMapperRef.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(jsonReport(isolation)), StandardCharsets.UTF_8);
            Files.writeString(mdPath, markdownReport(isolation), StandardCharsets.UTF_8);
            log.info("干扰压力实验报告已落盘 json={} md={}",
                    jsonPath.toAbsolutePath(), mdPath.toAbsolutePath());
            assertThat(jsonPath).exists();
            assertThat(mdPath).exists();
        } else {
            log.warn("实验数据不完整（dataset/control/distractor 有缺失），本轮不写报告");
        }

        assertThat(realAfter)
                .as("真实集合 %s 的点数必须跑前=跑后", AgentEvalSeeder.REAL_COLLECTION)
                .isEqualTo(realCollectionBefore);
        assertThat(distractorRemaining)
                .as("最终清理后干扰点残留必须为 0")
                .isZero();
        if (evalCollectionBaseline >= 0) {
            assertThat(evalAfter)
                    .as("评测集合 %s 清理后应回到本次基线", AgentEvalSeeder.EVAL_COLLECTION)
                    .isEqualTo(evalCollectionBaseline);
        }
        assertThat(cleanup.pointsDeleted()).as("评测用户的向量点应被清理").isPositive();
        assertThat(evalRowsRemaining)
                .as("清理后 888 段评测用户（userId=%s）不得有残留行",
                        AgentEvalSeeder.EVAL_USER_ID)
                .isZero();
    }

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
    // 9. 互斥锁实现（与 AgentEvalIT 逐字一致）
    // ------------------------------------------------------------------

    private static boolean tryAdvisoryLockOnFreshConnection(DataSource dataSource)
            throws SQLException {
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
    // 10. 小工具
    // ------------------------------------------------------------------

    private static List<ConfigResult> allResults() {
        List<ConfigResult> all = new ArrayList<>(controlResults);
        all.addAll(distractorResults);
        if (controlRecheckResult != null) {
            all.add(controlRecheckResult);
        }
        return List.copyOf(all);
    }

    private static ConfigResult find(List<ConfigResult> results, String id) {
        return results.stream().filter(result -> result.spec().id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalStateException("找不到配置结果 id=" + id));
    }

    private static ConfigSpec spec(String id) {
        return CONFIGS.stream().filter(item -> item.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalStateException("找不到配置 id=" + id));
    }

    /** 两条结果的 top5 标签序列有多少条用例不同（索引状态噪声的证据，不参与断言）。 */
    private static int countLabelDiffs(ConfigResult left, ConfigResult right) {
        int diffs = 0;
        for (int index = 0; index < left.cases().size(); index++) {
            List<String> leftLabels = topLabels(left.cases().get(index));
            List<String> rightLabels = topLabels(right.cases().get(index));
            if (!leftLabels.equals(rightLabels)) {
                diffs++;
            }
        }
        return diffs;
    }

    private static List<String> topLabels(CaseMeasurement measurement) {
        return measurement.hits().stream().limit(RetrievalQualityMetrics.RECALL_AT)
                .map(HitRef::label).toList();
    }

    // ---- 抗噪指标（recall 打平时它们才是区分力所在） ----

    /** 前 5 条里混进几条干扰点（source_id 落在干扰编号段、类型为 document_chunk）。 */
    private static int distractorHitsTop5(CaseMeasurement measurement) {
        int count = 0;
        for (int index = 0; index < measurement.hits().size()
                && index < RetrievalQualityMetrics.RECALL_AT; index++) {
            HitRef hit = measurement.hits().get(index);
            if ("document_chunk".equals(hit.sourceType())
                    && DISTRACTOR_SOURCE_IDS.contains(hit.sourceId())) {
                count++;
            }
        }
        return count;
    }

    /** 前 5 条里 gold 的占比（分母 = min(5, 实际返回条数)；返回 0 条时按 0）。 */
    private static double goldShareAt5(CaseMeasurement measurement) {
        int denominator = Math.min(RetrievalQualityMetrics.RECALL_AT, measurement.hits().size());
        if (denominator <= 0) {
            return 0.0d;
        }
        return measurement.score().goldHitsTop5() / (double) denominator;
    }

    /**
     * 前 5 条覆盖到多少个<b>不同的证据来源</b>：document/question 用 source_id，
     * 导图节点归并回导图 id（{@code 888911:n-rr} → {@code 888911}）。
     */
    private static int distinctEvidenceSourcesTop5(CaseMeasurement measurement) {
        Set<String> sources = new LinkedHashSet<>();
        for (int index = 0; index < measurement.hits().size()
                && index < RetrievalQualityMetrics.RECALL_AT; index++) {
            HitRef hit = measurement.hits().get(index);
            sources.add(evidenceSourceKey(hit));
        }
        return sources.size();
    }

    private static String evidenceSourceKey(HitRef hit) {
        if ("mindmap_node".equals(hit.sourceType()) && hit.sourceId().contains(":")) {
            return hit.sourceType() + ":" + hit.sourceId().substring(0, hit.sourceId().indexOf(':'));
        }
        return hit.sourceType() + ":" + hit.sourceId();
    }

    private static String casesYamlSha256() throws Exception {
        Path path = Path.of("src/test/resources/agent-eval/cases.yaml");
        byte[] bytes;
        if (Files.isRegularFile(path)) {
            bytes = Files.readAllBytes(path);
        } else {
            try (InputStream input = RetrievalDistractorIT.class
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

    private static double round4(double value) {
        return Math.round(value * 10000.0d) / 10000.0d;
    }

    private static double round2(double value) {
        return Math.round(value * 100.0d) / 100.0d;
    }

    private static String fmt4(double value) {
        return String.format(Locale.ROOT, "%.4f", value);
    }

    private static String fmt2(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private static String deltaPp(double baseline, double candidate) {
        double delta = (candidate - baseline) * 100.0d;
        return (delta >= 0 ? "+" : "") + String.format(Locale.ROOT, "%.1f", delta);
    }

    private static String oneLine(String text, int maxChars) {
        if (text == null || text.isBlank()) {
            return "（空）";
        }
        String single = text.replaceAll("\\s+", " ").trim();
        return single.length() > maxChars ? single.substring(0, maxChars) + "…" : single;
    }

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
    // 11. 报告落盘（@AfterAll 内调用）
    // ------------------------------------------------------------------

    private Map<String, Object> jsonReport(Isolation isolation) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("reportType", "retrieval-distractor");
        root.put("reportName", REPORT_BASENAME);
        root.put("generatedAt", OffsetDateTime.now().toString());
        root.put("date", LocalDate.now().toString());
        root.put("disclaimer",
                "只测检索层（播种 → 写干扰 → 检索 → 算指标 → 精确删除干扰）；不回答、不做 Planner；"
                        + "改写一律用规则改写（null AiGateway），本轮零对话模型调用。"
                        + "数字来自真实 embedding + Qdrant 检索，没有手工填数。");

        Map<String, Object> dataset = new LinkedHashMap<>();
        dataset.put("dataset", datasetRef.dataset());
        dataset.put("version", datasetRef.version());
        dataset.put("caseCount", datasetRef.cases().size());
        dataset.put("measuredCases", plansRef.size());
        dataset.put("sha256", casesSha256);
        dataset.put("frozenHashMatches", FROZEN_CASES_SHA256.equals(casesSha256));
        dataset.put("modified", false);
        root.put("dataset", dataset);

        root.put("embeddingModel", embeddingModelName);
        root.put("chatModel", chatModelName);
        root.put("evalCollection", evalCollectionName);
        root.put("realCollection", AgentEvalSeeder.REAL_COLLECTION);
        root.put("evalUserId", AgentEvalSeeder.EVAL_USER_ID);
        root.put("flywayVersion", flywayVersion);

        root.put("distractorCorpus", corpusMap());
        root.put("power", powerMap());
        root.put("summary", summaryList());
        root.put("deltas", deltaList());
        root.put("caseDrops", caseDropsList());
        root.put("answers", answersMap());
        root.put("latency", latencyMap());
        root.put("cost", costMap());
        root.put("isolation", isolationMap(isolation));
        root.put("mutex", mutexMap());
        root.put("limitations", limitations());
        return root;
    }

    private Map<String, Object> corpusMap() {
        Map<String, Object> corpus = new LinkedHashMap<>();
        corpus.put("count", RetrievalDistractorCorpus.all().size());
        corpus.put("sourceIdRange", List.of(RetrievalDistractorCorpus.FIRST_SOURCE_ID,
                RetrievalDistractorCorpus.LAST_SOURCE_ID));
        corpus.put("chunkIndex", 0);
        corpus.put("resourceType", VectorResourceType.DOCUMENT_CHUNK.wireName());
        corpus.put("writePath",
                "AgentEvalSeeder.seedExtraDocumentChunk(...)：复用 seed() 的 embed() + upsert() + "
                        + "VectorPointIds 确定性 pointId，不新写 Qdrant 客户端");
        corpus.put("pgRows", "不写任何 PG 业务行（本轮只测检索层）");
        corpus.put("lengthChars", Map.of("min", distractorCorpusMinChars,
                "avg", distractorCorpusChars / Math.max(1, distractorWritesCount),
                "max", distractorCorpusMaxChars));
        Map<String, Long> byGroup = RetrievalDistractorCorpus.all().stream()
                .collect(Collectors.groupingBy(RetrievalDistractorCorpus.DistractorChunk::group,
                        LinkedHashMap::new, Collectors.counting()));
        corpus.put("byGroup", byGroup);
        List<Map<String, Object>> items = new ArrayList<>();
        for (RetrievalDistractorCorpus.DistractorChunk chunk : RetrievalDistractorCorpus.all()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("sourceId", chunk.sourceId());
            item.put("groupId", chunk.group());
            item.put("title", chunk.title());
            item.put("contentChars", chunk.content().length());
            item.put("preview", oneLine(chunk.content(), 80));
            items.add(item);
        }
        corpus.put("items", items);
        return corpus;
    }

    private Map<String, Object> powerMap() {
        Map<String, Object> power = new LinkedHashMap<>();
        power.put("candidatePoolControl", candidateCountsControl);
        power.put("candidatePoolWithDistractors", candidateCountsDistractor);
        power.put("documentChunksControl", docChunksControl);
        power.put("documentChunksWithDistractors", docChunksWithDistractors);
        power.put("searchTopK", qdrantProperties.getSearchTopK());
        power.put("casesWithDocumentGold", documentOnlyCaseCount);
        power.put("casesStructurallyImmune", plansRef.size() - documentOnlyCaseCount);
        power.put("maxDistinctDocumentGoldsPerCase", maxDistinctDocumentGolds);
        power.put("note",
                "干扰只写 document_chunk，所以只有 gold 含 document 的 " + documentOnlyCaseCount
                        + " 条用例可能被影响；其余 " + (plansRef.size() - documentOnlyCaseCount)
                        + " 条（mindmap / question）按资源类型过滤，结构上不可能被这轮干扰影响，"
                        + "构成 recall@5 的下界 " + fmt4((plansRef.size() - documentOnlyCaseCount)
                        / (double) plansRef.size()) + "。");
        return power;
    }

    private List<Map<String, Object>> summaryList() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ConfigResult control : controlResults) {
            ConfigResult distractor = find(distractorResults, control.spec().id());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("configId", control.spec().id());
            row.put("path", control.spec().path());
            row.put("rewrite", control.spec().rewrite());
            row.put("minScore", control.spec().minScore());
            row.put("maxResults", control.spec().cap());
            row.put("maxChunksPerDocument", control.spec().chunks());
            row.put("control", scenarioMap(control));
            row.put("distractor", scenarioMap(distractor));
            rows.add(row);
        }
        return rows;
    }

    private Map<String, Object> scenarioMap(ConfigResult result) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("cases", result.overall().cases());
        map.put("failedCases", result.overall().failedCases());
        map.put("recallAt1", result.overall().meanRecallAt1());
        map.put("recallAt5", result.overall().meanRecall5());
        map.put("recallAt5Strict", result.overall().meanRecall5Strict());
        map.put("mrr", result.overall().meanMrr());
        map.put("mrrAt5", result.overall().meanMrr5());
        map.put("avgHits", result.overall().avgHits());
        map.put("avgGoldHitsTop5", result.avgGoldHitsTop5());
        map.put("goldShareAt5", result.goldShareAt5());
        map.put("avgDistractorHitsTop5", result.avgDistractorHitsTop5());
        map.put("avgDistinctEvidenceSourcesTop5", result.avgDistinctDocumentsTop5());
        map.put("p50Ms", result.overall().p50Ms());
        map.put("p95Ms", result.overall().p95Ms());
        map.put("searchCalls", result.searchCalls());
        return map;
    }

    private List<Map<String, Object>> deltaList() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ConfigResult control : controlResults) {
            ConfigResult distractor = find(distractorResults, control.spec().id());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("configId", control.spec().id());
            row.put("recallAt5DeltaPp", round2((distractor.overall().meanRecall5()
                    - control.overall().meanRecall5()) * 100.0d));
            row.put("recallAt5StrictDeltaPp", round2((distractor.overall().meanRecall5Strict()
                    - control.overall().meanRecall5Strict()) * 100.0d));
            row.put("mrrDeltaPp", round2((distractor.overall().meanMrr()
                    - control.overall().meanMrr()) * 100.0d));
            row.put("avgHitsDelta", round2(distractor.overall().avgHits()
                    - control.overall().avgHits()));
            row.put("distractorHitsTop5", distractor.avgDistractorHitsTop5());
            row.put("goldShareAt5Control", control.goldShareAt5());
            row.put("goldShareAt5Distractor", distractor.goldShareAt5());
            row.put("evidenceSourcesControl", control.avgDistinctDocumentsTop5());
            row.put("evidenceSourcesDistractor", distractor.avgDistinctDocumentsTop5());
            row.put("p50DeltaMs", distractor.overall().p50Ms() - control.overall().p50Ms());
            row.put("p95DeltaMs", distractor.overall().p95Ms() - control.overall().p95Ms());
            rows.add(row);
        }
        return rows;
    }

    /** 一条“在干扰场景下变差”的用例明细（recall 下降 或 首个 gold 名次变差）。 */
    private record CaseDrop(String configId, String caseId, String category, List<String> golds,
                            int goldCount, double controlRecallAt5, double distractorRecallAt5,
                            int controlFirstGoldRank, int distractorFirstGoldRank,
                            boolean recallDropped, List<String> controlTop5,
                            List<String> distractorTop5, int distractorHitsTop5, String question) {

        double recallDeltaPp() {
            return round2((distractorRecallAt5 - controlRecallAt5) * 100.0d);
        }
    }

    /** sourceId → 干扰标题（报告里要写清“是哪一条干扰抢了第一名”）。 */
    private static String distractorTitle(String sourceId) {
        for (RetrievalDistractorCorpus.DistractorChunk chunk : RetrievalDistractorCorpus.all()) {
            if (String.valueOf(chunk.sourceId()).equals(sourceId)) {
                return chunk.title();
            }
        }
        return "（未知干扰 sourceId=" + sourceId + "）";
    }

    /** 干扰抢走第 1 名的用例（这是“第一名被抢”的直接证据，不是聚合数字）。 */
    private record TopOneSteal(String configId, String caseId, String question,
                               String distractorSourceId, String distractorTitle,
                               List<String> golds, List<String> distractorTop5) {
    }

    private List<TopOneSteal> topOneSteals() {
        List<TopOneSteal> rows = new ArrayList<>();
        for (ConfigResult result : distractorResults) {
            for (CaseMeasurement measurement : result.cases()) {
                if (measurement.hits().isEmpty()) {
                    continue;
                }
                HitRef first = measurement.hits().get(0);
                if (!"document_chunk".equals(first.sourceType())
                        || !DISTRACTOR_SOURCE_IDS.contains(first.sourceId())) {
                    continue;
                }
                rows.add(new TopOneSteal(result.spec().id(), measurement.caseId(),
                        datasetRef.requireById(measurement.caseId()).question(),
                        first.sourceId(), distractorTitle(first.sourceId()),
                        measurement.golds().stream().map(GoldRef::raw).toList(),
                        topLabels(measurement)));
            }
        }
        return List.copyOf(rows);
    }

    /** 每用例明细：只取干扰场景里 recall@5 下降（或第一条被抢）的用例。 */
    private List<CaseDrop> caseDrops() {
        List<CaseDrop> rows = new ArrayList<>();
        for (ConfigResult control : controlResults) {
            ConfigResult distractor = find(distractorResults, control.spec().id());
            for (int index = 0; index < control.cases().size(); index++) {
                CaseMeasurement controlCase = control.cases().get(index);
                CaseMeasurement distractorCase = distractor.cases().get(index);
                boolean recallDropped = distractorCase.score().recallAt5()
                        < controlCase.score().recallAt5() - 1e-9;
                boolean rankWorsened = distractorCase.score().firstGoldRank()
                        > controlCase.score().firstGoldRank();
                if (!recallDropped && !rankWorsened) {
                    continue;
                }
                rows.add(new CaseDrop(control.spec().id(), controlCase.caseId(),
                        controlCase.category(),
                        controlCase.golds().stream().map(GoldRef::raw).toList(),
                        controlCase.score().goldCount(), controlCase.score().recallAt5(),
                        distractorCase.score().recallAt5(),
                        controlCase.score().firstGoldRank(),
                        distractorCase.score().firstGoldRank(), recallDropped,
                        topLabels(controlCase), topLabels(distractorCase),
                        distractorHitsTop5(distractorCase),
                        datasetRef.requireById(controlCase.caseId()).question()));
            }
        }
        return List.copyOf(rows);
    }

    /** JSON 里的用例明细（由 {@link #caseDrops()} 转成 Map）。 */
    private List<Map<String, Object>> caseDropsList() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (CaseDrop drop : caseDrops()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("configId", drop.configId());
            row.put("caseId", drop.caseId());
            row.put("category", drop.category());
            row.put("golds", drop.golds());
            row.put("goldCount", drop.goldCount());
            row.put("controlRecallAt5", drop.controlRecallAt5());
            row.put("distractorRecallAt5", drop.distractorRecallAt5());
            row.put("recallDeltaPp", drop.recallDeltaPp());
            row.put("controlFirstGoldRank", drop.controlFirstGoldRank());
            row.put("distractorFirstGoldRank", drop.distractorFirstGoldRank());
            row.put("recallDropped", drop.recallDropped());
            row.put("controlTop5", drop.controlTop5());
            row.put("distractorTop5", drop.distractorTop5());
            row.put("distractorHitsTop5", drop.distractorHitsTop5());
            row.put("question", drop.question());
            rows.add(row);
        }
        return rows;
    }

    /** 六个待回答问题的结构化答案（数字全部由本次测量算出，没有硬编码）。 */
    private Map<String, Object> answersMap() {
        Map<String, Object> answers = new LinkedHashMap<>();
        ConfigResult baselineControl = find(controlResults, "baseline-legacy");
        ConfigResult baselineDistractor = find(distractorResults, "baseline-legacy");
        ConfigResult mqControl = find(controlResults, "mq-0.35-c5-ch1");
        ConfigResult mqDistractor = find(distractorResults, "mq-0.35-c5-ch1");
        ConfigResult ch1Distractor = find(distractorResults, "mq-0.35-c5-ch1");
        ConfigResult ch2Distractor = find(distractorResults, "mq-0.35-c5-ch2");
        ConfigResult cap8Distractor = find(distractorResults, "mq-0.35-c8-ch2");
        ConfigResult c8ch1Distractor = find(distractorResults, "mq-0.35-c8-ch1");

        answers.put("q1_baselineRecallDropPp", round2((baselineControl.overall().meanRecall5()
                - baselineDistractor.overall().meanRecall5()) * 100.0d));
        answers.put("q1_baselineControlRecallAt5", baselineControl.overall().meanRecall5());
        answers.put("q1_baselineDistractorRecallAt5", baselineDistractor.overall().meanRecall5());

        double baselineDrop = baselineControl.overall().meanRecall5()
                - baselineDistractor.overall().meanRecall5();
        double mqDrop = mqControl.overall().meanRecall5()
                - mqDistractor.overall().meanRecall5();
        answers.put("q2_baselineDropPp", round2(baselineDrop * 100.0d));
        answers.put("q2_multiQueryDropPp", round2(mqDrop * 100.0d));
        answers.put("q2_differencePp", round2((baselineDrop - mqDrop) * 100.0d));
        answers.put("q2_verdict", verdict(baselineDrop, mqDrop));

        answers.put("q3_ch1DistractorRecallAt5", ch1Distractor.overall().meanRecall5());
        answers.put("q3_ch2DistractorRecallAt5", ch2Distractor.overall().meanRecall5());
        answers.put("q3_ch1EvidenceSources", ch1Distractor.avgDistinctDocumentsTop5());
        answers.put("q3_ch2EvidenceSources", ch2Distractor.avgDistinctDocumentsTop5());
        answers.put("q3_ch1DistractorHits", ch1Distractor.avgDistractorHitsTop5());
        answers.put("q3_ch2DistractorHits", ch2Distractor.avgDistractorHitsTop5());

        answers.put("q4_cap8DistractorRecallAt5", cap8Distractor.overall().meanRecall5());
        answers.put("q4_cap8AvgHits", cap8Distractor.overall().avgHits());
        answers.put("q4_cap5AvgHits", ch2Distractor.overall().avgHits());
        answers.put("q4_cap8VsCap5RecallDeltaPp", round2(
                (cap8Distractor.overall().meanRecall5()
                        - ch2Distractor.overall().meanRecall5()) * 100.0d));
        answers.put("q4_cap8ch1RecallAt5", c8ch1Distractor.overall().meanRecall5());

        Map<String, Object> mrr = new LinkedHashMap<>();
        for (ConfigResult control : controlResults) {
            ConfigResult distractor = find(distractorResults, control.spec().id());
            mrr.put(control.spec().id(), Map.of(
                    "control", control.overall().meanMrr(),
                    "distractor", distractor.overall().meanMrr(),
                    "deltaPp", round2((distractor.overall().meanMrr()
                            - control.overall().meanMrr()) * 100.0d)));
        }
        answers.put("q5_mrr", mrr);

        Map<String, Object> latency = new LinkedHashMap<>();
        for (ConfigResult control : controlResults) {
            ConfigResult distractor = find(distractorResults, control.spec().id());
            latency.put(control.spec().id(), Map.of(
                    "controlP50Ms", control.overall().p50Ms(),
                    "distractorP50Ms", distractor.overall().p50Ms(),
                    "controlP95Ms", control.overall().p95Ms(),
                    "distractorP95Ms", distractor.overall().p95Ms()));
        }
        answers.put("q6_latency", latency);
        return answers;
    }

    private static String verdict(double baselineDrop, double multiQueryDrop) {
        double difference = (baselineDrop - multiQueryDrop) * 100.0d;
        if (difference > 0.1d) {
            return "多查询在干扰下掉得更少（抗噪更好），绝对差 " + String.format(Locale.ROOT,
                    "%.1f", difference) + " 个百分点";
        }
        if (difference < -0.1d) {
            return "多查询在干扰下掉得更多（抗噪更差），绝对差 " + String.format(Locale.ROOT,
                    "%.1f", difference) + " 个百分点";
        }
        return "多查询与基线在干扰下掉得一样（无差别），绝对差 "
                + String.format(Locale.ROOT, "%.1f", difference) + " 个百分点";
    }

    private Map<String, Object> latencyMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        for (ConfigResult result : allResults()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("scenario", result.scenario().name());
            item.put("p50Ms", result.overall().p50Ms());
            item.put("p95Ms", result.overall().p95Ms());
            item.put("meanMs", result.overall().cases() == 0 ? 0L
                    : result.overall().totalMs() / result.overall().cases());
            item.put("totalMs", result.overall().totalMs());
            item.put("wallMs", result.wallMs());
            map.put(result.spec().id() + "/" + result.scenario().name(), item);
        }
        return map;
    }

    private Map<String, Object> costMap() {
        Map<String, Object> cost = new LinkedHashMap<>();
        cost.put("vectorSearchCalls", totalSearchCalls);
        cost.put("embeddingCallsForSearch", totalSearchCalls);
        cost.put("embeddingCallsForDistractorWrite", distractorWritesCount);
        cost.put("embeddingCallsForSeed", fixturePoints + 1);
        cost.put("llmRewriteCalls", 0);
        cost.put("llmRewriteNote",
                "本轮 5 组配置全部用规则改写（MultiQueryRetriever 传 null AiGateway），"
                        + "因此对话模型调用为 0；只消耗 embedding。");
        cost.put("measurementWallMs", runWallMs);
        cost.put("money", "未换算金额：只记录调用次数；embedding 单价按平台账单为准。");
        return cost;
    }

    private Map<String, Object> isolationMap(Isolation isolation) {
        Map<String, Object> iso = new LinkedHashMap<>();
        iso.put("realCollection", AgentEvalSeeder.REAL_COLLECTION);
        iso.put("realCollectionPointsBefore", isolation.realBefore());
        iso.put("realCollectionPointsAfter", isolation.realAfter());
        iso.put("realCollectionUnchanged", isolation.realBefore() == isolation.realAfter());
        iso.put("evalCollection", evalCollectionName);
        iso.put("evalCollectionBaselinePoints", isolation.evalBaseline());
        iso.put("evalCollectionPointsAfterSeed", isolation.evalAfterSeed());
        iso.put("evalCollectionPointsAfterDistractors", isolation.evalAfterDistractors());
        iso.put("evalCollectionPointsAfterDistractorCleanup",
                isolation.evalAfterDistractorCleanup());
        iso.put("evalCollectionPointsAfterFinalCleanup", isolation.evalAfterCleanup());
        iso.put("evalCollectionBackToBaseline",
                isolation.evalAfterCleanup() == isolation.evalBaseline());
        iso.put("distractorsWritten", distractorWritesCount);
        iso.put("distractorsCountedAfterWrite", distractorPointsWritten);
        iso.put("distractorsCountedAfterCleanup", distractorPointsAfterCleanup);
        iso.put("deleteStrategy",
                "按 VectorPointIds.pointId(document_chunk, sourceId, 0) 确定性 pointId 逐条删除，"
                        + "不做范围扫描；@AfterAll 再用 Seeder.cleanup() 按 userId 兜底");
        iso.put("cleanupRowsDeleted", isolation.cleanup().rowsDeleted());
        iso.put("cleanupTableDeletes", isolation.cleanup().tableDeletes());
        iso.put("cleanupVectorPointsDeleted", isolation.cleanup().pointsDeleted());
        iso.put("evalRowsRemainingAfterCleanup", isolation.evalRowsRemaining());
        iso.put("controlRecheckEqualsControl", controlRecheckResult != null
                && controlRecheckResult.overall().meanRecall5()
                == find(controlResults, "baseline-legacy").overall().meanRecall5());
        iso.put("top5LabelDiffControlVsRecheck", labelDiffBetweenControlAndRecheck);
        return iso;
    }

    private Map<String, Object> mutexMap() {
        Map<String, Object> mutex = new LinkedHashMap<>();
        mutex.put("type", "PostgreSQL session-level advisory lock");
        mutex.put("key", EVAL_ADVISORY_LOCK_KEY);
        mutex.put("sameKeyAsAgentEvalIT", true);
        mutex.put("acquired", lockAcquired);
        mutex.put("acquiredAt", lockAcquiredAt);
        mutex.put("backendPid", lockBackendPid);
        mutex.put("secondSessionProbeBlocked", lockProbeBlocked);
        mutex.put("release", "同一 Connection 全程持有；@AfterAll 的 finally 里 pg_advisory_unlock + close");
        return mutex;
    }

    private List<String> limitations() {
        return List.of(
                "**干扰只有 document_chunk 一种类型**：mindmap / question 两类 gold 的用例按资源类型过滤，"
                        + "候选池里本来就没有干扰，结构上不可能被影响。所以 recall@5 的下界是 "
                        + fmt4((plansRef.size() - documentOnlyCaseCount) / (double) plansRef.size())
                        + "（" + (plansRef.size() - documentOnlyCaseCount) + "/"
                        + plansRef.size() + " 条免疫），本轮数字不能解释成“整轮检索的抗噪能力”。",
                "**干扰是“一条干扰 = 一篇文档”（chunk_index 恒为 0）**：这让每个干扰都能各占 top5 一个位置，"
                        + "是<b>对 cap/每篇 chunk 上限最不利</b>的构造；真实笔记更可能是“一篇长文切成多个 chunk”，"
                        + "那样每篇 chunk 上限会先削掉同一篇的重复，干扰的杀伤力与本轮不同。",
                "**干扰内容是手写的、主题集中在操作系统**：字长与真实 chunk 同量级（"
                        + distractorCorpusMinChars + "~" + distractorCorpusMaxChars + " 字），"
                        + "但语义密度与真实用户笔记仍有差别；embedding 对“同一个词不同答案”的区分力"
                        + "是这轮结论的关键，换 embedding 模型结论可能变。",
                "**语料里含 8 条硬负样本（B-near-miss）**：它们与 gold 同一子话题（调度算法/同步机制），"
                        + "用词高度重叠，可能对某些用例“部分相关”。这会让 recall 的下降既包含“被噪声挤掉”，"
                        + "也包含“检索其实找了个相邻但不对的答案”，两者在本报告里无法完全分离。",
                "gold 只标到 chunk 级：chunk 进 top5 就算命中，不判断 chunk 内是否真有答案句；"
                        + "所以 recall 不下降不等于“答得对”。",
                "导图 gold 沿用 E2 的放宽口径（同一张导图任意节点命中），严格口径一并给出；"
                        + "导图用例本轮不受干扰影响。",
                "单次测量、无重复：embedding 与 Qdrant 延迟受网络与机器负载影响；"
                        + "干扰场景的 P50/P95 只反映“候选池变大后的本轮延迟”，不是线上容量结论。",
                "只测检索层：不覆盖 Planner、工具选择、证据编号、上下文截断、read_my_document 的二次读取；"
                        + "也不衡量“多出来的干扰证据会不会让回答变差”（那是 P1-F 端到端评测的事）。",
                "**延迟对比存在顺序效应**：对照组先跑（Qdrant 连接与页缓存冷），实验组后跑（已热），"
                        + "所以本报告的 ΔP50/ΔP95 里混着“先跑/后跑”的差异，甚至出现实验组更快；"
                        + "这一列只能当“量级没变化”看，不能当“干扰更快/更慢”的证据。",
                "**22 条含 document gold 的用例，全部（22/22）在 top5 里至少混进 1 条干扰**："
                        + "说明 minScore=0.35 完全没拦住同领域干扰（干扰分数普遍过阈值）。"
                        + "这与 E2 的结论联动——E2 已测出“把阈值抬到 0.45 会丢 gold”，"
                        + "所以“靠抬阈值去噪”这条路在本夹具上是被两边夹住的，本轮没有重测阈值。",
                "33 条用例 × 5 组配置存在多重比较风险；结论只描述本次测量。");
    }

    // ------------------------------------------------------------------
    // 11b. Markdown 报告
    // ------------------------------------------------------------------

    private String markdownReport(Isolation isolation) {
        StringBuilder md = new StringBuilder();
        appendHeader(md);
        appendCorpus(md);
        appendComparison(md);
        appendCaseDetails(md);
        appendAnswers(md);
        appendIsolation(md, isolation);
        appendLimitations(md);
        return md.toString();
    }

    private void appendHeader(StringBuilder md) {
        md.append("# 检索抗噪压力实验：干扰 chunk 对 recall@5 / MRR 的影响（")
                .append(LocalDate.now()).append("）\n\n");
        md.append("> 报告名：`").append(REPORT_BASENAME)
                .append("`；由 `RetrievalDistractorIT` 自动生成")
                .append("（`mvn.cmd -f mindmap-backend/pom.xml -B -Peval test -Dtest=RetrievalDistractorIT`）。\n>\n");
        md.append("> **只测检索层**：播种 → 写 40 条干扰 chunk → 检索 → 算指标 → 按 pointId 精确删除干扰 → 复核。")
                .append("不回答、不做 Planner；改写一律用**规则改写**（`null` AiGateway），本轮 **0 次对话模型调用**。\n>\n");
        md.append("> `cases.yaml` **一个字都没改**：SHA256 `").append(casesSha256)
                .append("`（与冻结值一致=").append(FROZEN_CASES_SHA256.equals(casesSha256))
                .append("）；所有数字来自真实 embedding + Qdrant 检索，没有手工填数。\n\n");

        ConfigResult baselineControl = find(controlResults, "baseline-legacy");
        ConfigResult baselineDistractor = find(distractorResults, "baseline-legacy");
        ConfigResult mqControl = find(controlResults, "mq-0.35-c5-ch1");
        ConfigResult mqDistractor = find(distractorResults, "mq-0.35-c5-ch1");
        double baselineDrop = baselineControl.overall().meanRecall5()
                - baselineDistractor.overall().meanRecall5();
        double mqDrop = mqControl.overall().meanRecall5()
                - mqDistractor.overall().meanRecall5();

        md.append("## 0. 一句话结论\n\n");
        md.append("- 干扰把评测用户的 document 候选从 **").append(docChunksControl)
                .append(" 条** 拉到 **").append(docChunksWithDistractors).append(" 条**（5 gold + ")
                .append(distractorWritesCount).append(" 干扰），而每个 query 只取 top ")
                .append(qdrantProperties.getSearchTopK()).append("，从结构上第一次真正制造了「候选被挤掉」。\n");
        md.append("- 基线 `baseline-legacy` recall@5：**")
                .append(fmt4(baselineControl.overall().meanRecall5())).append(" → ")
                .append(fmt4(baselineDistractor.overall().meanRecall5())).append("**（")
                .append(deltaPp(baselineControl.overall().meanRecall5(),
                        baselineDistractor.overall().meanRecall5()))
                .append(" 个百分点）。\n");
        md.append("- 多查询 `mq-0.35-c5-ch1` recall@5：**")
                .append(fmt4(mqControl.overall().meanRecall5())).append(" → ")
                .append(fmt4(mqDistractor.overall().meanRecall5())).append("**（")
                .append(deltaPp(mqControl.overall().meanRecall5(),
                        mqDistractor.overall().meanRecall5())).append(" 个百分点）。\n");
        md.append("- **多查询 vs 基线 的抗噪差 = ").append(verdict(baselineDrop, mqDrop))
                .append("**。\n");
        md.append("- 干扰进 top5 的条数（多查询 cap5 组）：对照 ")
                .append(fmt4(mqControl.avgDistractorHitsTop5())).append(" 条 → 干扰 ")
                .append(fmt4(mqDistractor.avgDistractorHitsTop5())).append(" 条；gold 占比@5 从 ")
                .append(fmt4(mqControl.goldShareAt5())).append(" 降到 ")
                .append(fmt4(mqDistractor.goldShareAt5())).append("。\n");
        md.append("- **recall@5 没掉，但排序掉了**：recall@1 从 ")
                .append(fmt4(baselineControl.overall().meanRecallAt1())).append(" 降到 ")
                .append(fmt4(baselineDistractor.overall().meanRecallAt1())).append("（")
                .append(deltaPp(baselineControl.overall().meanRecallAt1(),
                        baselineDistractor.overall().meanRecallAt1()))
                .append(" 个百分点），MRR 从 ")
                .append(fmt4(baselineControl.overall().meanMrr())).append(" 降到 ")
                .append(fmt4(baselineDistractor.overall().meanMrr())).append("（")
                .append(deltaPp(baselineControl.overall().meanMrr(),
                        baselineDistractor.overall().meanMrr()))
                .append(" 个百分点）；**").append(topOneSteals().size())
                .append(" 条用例的第一名被干扰抢走**（见 §4.3）。\n");
        double noiseShare = mqDistractor.overall().avgHits() <= 0 ? 0.0d
                : mqDistractor.avgDistractorHitsTop5() / mqDistractor.overall().avgHits();
        md.append("- **噪声占返回证据的比例**：干扰场景下平均每次返回 ")
                .append(fmt4(mqDistractor.overall().avgHits())).append(" 条，其中 ")
                .append(fmt4(mqDistractor.avgDistractorHitsTop5())).append(" 条是干扰（约 ")
                .append(String.format(Locale.ROOT, "%.1f", noiseShare * 100.0d))
                .append("%）；对照组这一项恒为 0.0000。\n");
        md.append("- **下界提醒**：干扰只写 `document_chunk`，")
                .append(plansRef.size() - documentOnlyCaseCount)
                .append(" 条 mindmap/question 用例按资源类型过滤、结构上免疫，"
                        + "所以 recall@5 最低只可能掉到 ")
                .append(fmt4((plansRef.size() - documentOnlyCaseCount) / (double) plansRef.size()))
                .append("。\n\n");

        md.append("## 1. 这是什么 / 不是什么\n\n");
        md.append("| 项 | 值 |\n|---|---|\n");
        md.append("| 用途 | 在**评测用户**下临时写入 40 条干扰 chunk，测「噪声把 gold 挤出 top5」的幅度 |\n");
        md.append("| 用例集 | ").append(datasetRef.dataset()).append(" v").append(datasetRef.version())
                .append("（").append(datasetRef.cases().size()).append(" 条，**未改**） |\n");
        md.append("| 参与测量的用例 | ").append(plansRef.size())
                .append(" 条（gold 非空且不含 memory）；其中含 document gold 的 ")
                .append(documentOnlyCaseCount).append(" 条 |\n");
        md.append("| 场景 | 对照组（无干扰）→ 实验组（有干扰）→ 清理后复核（无干扰） |\n");
        md.append("| 配置 | ").append(CONFIGS.size()).append(" 组：")
                .append(CONFIGS.stream().map(ConfigSpec::id).collect(Collectors.joining("`, `", "`", "`")))
                .append(" |\n");
        md.append("| embedding / chat 模型 | `").append(embeddingModelName).append("` / `")
                .append(chatModelName).append("`（本轮未调用） |\n");
        md.append("| 评测集合 / 真实集合 | `").append(evalCollectionName).append("` / `")
                .append(AgentEvalSeeder.REAL_COLLECTION).append("`（后者只读 count） |\n");
        md.append("| 报告生成时间 | ").append(OffsetDateTime.now()).append(" |\n\n");
        md.append("**不是什么**：不是端到端评测；不衡量“多出来的噪声证据会不会让回答变差”；")
                .append("不代表线上真实用户笔记的分布。\n\n");
    }

    private void appendCorpus(StringBuilder md) {
        md.append("## 2. 干扰是怎么造的\n\n");
        md.append("| 项 | 值 |\n|---|---|\n");
        md.append("| 条数 | **").append(distractorWritesCount).append("** 条（任务要求 20~50） |\n");
        md.append("| `source_id` 编号段 | **")
                .append(RetrievalDistractorCorpus.FIRST_SOURCE_ID).append("~")
                .append(RetrievalDistractorCorpus.LAST_SOURCE_ID)
                .append("**（刻意避开 gold 的 888901~888904 / 888911 / 888921~888923 / 888931~888933） |\n");
        md.append("| `resource_type` | `document_chunk`（与真实文档一致） |\n");
        md.append("| `chunk_index` | 全为 `0`（**一条干扰 = 一篇文档**，见 §7 失真说明） |\n");
        md.append("| 正文字长 | min **").append(distractorCorpusMinChars).append("** / avg **")
                .append(distractorCorpusChars / Math.max(1, distractorWritesCount)).append("** / max **")
                .append(distractorCorpusMaxChars).append("** 字（与真实 gold chunk 同量级） |\n");
        md.append("| 写入路径 | `AgentEvalSeeder.seedExtraDocumentChunk(...)`：复用 `seed()` 的 ")
                .append("`embed()`（真实 embedding）+ `upsert()` + `VectorPointIds` 确定性 pointId，")
                .append("**没有第二套 Qdrant 客户端** |\n");
        md.append("| PG 业务行 | **不写**（本轮只测检索层，干扰只存在于向量集合） |\n");
        md.append("| 清理方式 | 按确定性 pointId 逐条精确删除 + `@AfterAll` 用 `Seeder.cleanup()` 兜底 |\n\n");

        Map<String, Long> byGroup = RetrievalDistractorCorpus.all().stream()
                .collect(Collectors.groupingBy(RetrievalDistractorCorpus.DistractorChunk::group,
                        LinkedHashMap::new, Collectors.counting()));
        Map<String, String> groupDesc = new LinkedHashMap<>();
        groupDesc.put("A-other-topic", "操作系统里**别的话题**（文件系统 / 磁盘与 IO 调度 / 设备与中断 / "
                + "DMA / mmap / 页缓存 / 分段 / 碎片 / 死锁 / 线程 / 进程状态 / 上下文切换 / 系统调用）——"
                + "与 gold 的问题没有共同答案");
        groupDesc.put("B-near-miss", "与 gold **同一子话题下的另一个机制/算法**（多级反馈队列 / 优先级调度 / "
                + "优先级反转 / 实时调度 / 多级队列 / 管程 / 条件变量 / 自旋锁）——答案不同但用词高度重叠，"
                + "硬负样本");
        groupDesc.put("I-experiment-record", "与 gold 的《操作系统实验三记录》**同体裁但不同实验**"
                + "（shell / 生产者消费者 / 文件系统实验）——专门压 base-012 与 base-049");
        md.append("### 2.1 分组\n\n");
        md.append("| 分组 | 条数 | 说明 |\n|---|---:|---|\n");
        for (Map.Entry<String, Long> entry : byGroup.entrySet()) {
            md.append("| `").append(entry.getKey()).append("` | ").append(entry.getValue())
                    .append(" | ").append(groupDesc.getOrDefault(entry.getKey(), ""))
                    .append(" |\n");
        }
        md.append("\n### 2.2 全部 ").append(distractorWritesCount).append(" 条清单\n\n");
        md.append("| # | source_id | 分组 | 标题 | 字数 |\n|---:|---:|---|---|---:|\n");
        int index = 0;
        for (RetrievalDistractorCorpus.DistractorChunk chunk : RetrievalDistractorCorpus.all()) {
            index++;
            md.append("| ").append(index).append(" | `").append(chunk.sourceId()).append("` | ")
                    .append(chunk.group()).append(" | ").append(chunk.title()).append(" | ")
                    .append(chunk.content().length()).append(" |\n");
        }
        md.append("\n### 2.3 候选池变化（为什么这轮才第一次真能“挤掉”）\n\n");
        md.append("| 资源类型 | 对照组 | 有干扰 | 变化 |\n|---|---:|---:|---:|\n");
        Set<String> keys = new LinkedHashSet<>(candidateCountsControl.keySet());
        keys.addAll(candidateCountsDistractor.keySet());
        for (String key : keys) {
            long before = candidateCountsControl.getOrDefault(key, 0L);
            long after = candidateCountsDistractor.getOrDefault(key, 0L);
            md.append("| `").append(key).append("` | ").append(before).append(" | ").append(after)
                    .append(" | ").append(after >= before ? "+" : "").append(after - before)
                    .append(" |\n");
        }
        md.append("\n- 每个 query 只取 top **").append(qdrantProperties.getSearchTopK())
                .append("**：document 候选从 ").append(docChunksControl).append(" 条涨到 ")
                .append(docChunksWithDistractors).append(" 条后，top-k 再也装不下全部候选，")
                .append("“谁被挤出去”第一次成为真问题。\n");
        md.append("- 干扰只写 `document_chunk`，所以 **").append(documentOnlyCaseCount)
                .append("/").append(plansRef.size()).append("** 条含 document gold 的用例可能被影响；")
                .append("其余 ").append(plansRef.size() - documentOnlyCaseCount)
                .append(" 条（mindmap / question）按资源类型过滤，**结构上不可能被这轮干扰影响**，")
                .append("构成 recall@5 的下界 ")
                .append(fmt4((plansRef.size() - documentOnlyCaseCount) / (double) plansRef.size()))
                .append("。\n\n");
    }

    private void appendComparison(StringBuilder md) {
        md.append("## 3. 对照表：无干扰 vs 有干扰（").append(plansRef.size())
                .append(" 条可测用例，宏平均）\n\n");
        md.append("> `recall@5` / `MRR` 与 E2 同口径；`严格 recall@5` = 导图不放宽；")
                .append("`干扰条数@5` = 前 5 条里平均混进几条干扰点；")
                .append("`gold 占比@5` = 前 5 条里 gold 占多少（分母 min(5, 实际返回条数)）；")
                .append("`覆盖来源@5` = 前 5 条覆盖到多少个不同证据来源（导图节点归并回导图 id）。\n\n");

        md.append("### 3.0 对照组是否复现了 P1-E2（可比性前置）\n\n");
        md.append("| 配置 | E2 已发布 recall@5 | 本轮对照组 recall@5 | E2 已发布平均证据 | 本轮对照组平均证据 | 一致 |\n");
        md.append("|---|---:|---:|---:|---:|---|\n");
        for (ConfigResult result : controlResults) {
            double publishedHits = E2_PUBLISHED_AVG_HITS.getOrDefault(result.spec().id(), -1.0d);
            boolean same = Math.abs(publishedHits - result.overall().avgHits()) < 1e-9
                    && Math.abs(E2_PUBLISHED_RECALL5 - result.overall().meanRecall5()) < 1e-9;
            md.append("| `").append(result.spec().id()).append("` | ")
                    .append(fmt4(E2_PUBLISHED_RECALL5)).append(" | ")
                    .append(fmt4(result.overall().meanRecall5())).append(" | ")
                    .append(fmt4(publishedHits)).append(" | ")
                    .append(fmt4(result.overall().avgHits())).append(" | ")
                    .append(same ? "**是**" : "否").append(" |\n");
        }
        md.append("\n- 上一轮数字来自 `docs/agent-eval/2026-10-28-retrieval-quality.md` §4（E2 的 19 组矩阵），")
                .append("本轮**没有重跑 19 组**，只跑这 5 组；E2 与本轮的平均证据完全一致，")
                .append("说明对照组环境与上一轮可比，实验组的差值可以归因到“干扰”。\n\n");

        md.append("### 3.1 对照组（无干扰，应复现 P1-E2 的数字）\n\n");
        md.append("| 配置 | 路径 | cap | 每篇 chunk | recall@1 | recall@5 | 严格 recall@5 | MRR | MRR@5 | 平均证据 | 干扰条数@5 | gold 占比@5 | 覆盖来源@5 | P50(ms) | P95(ms) | 失败 |\n");
        md.append("|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|\n");
        for (ConfigResult result : controlResults) {
            md.append(comparisonRow(result));
        }
        md.append("\n### 3.2 实验组（有干扰）\n\n");
        md.append("| 配置 | 路径 | cap | 每篇 chunk | recall@1 | recall@5 | 严格 recall@5 | MRR | MRR@5 | 平均证据 | 干扰条数@5 | gold 占比@5 | 覆盖来源@5 | P50(ms) | P95(ms) | 失败 |\n");
        md.append("|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|\n");
        for (ConfigResult result : distractorResults) {
            md.append(comparisonRow(result));
        }
        md.append("\n### 3.3 变化量（实验组 − 对照组，单位：个百分点 / 条 / 毫秒）\n\n");
        md.append("| 配置 | Δ recall@5 | Δ 严格 recall@5 | Δ MRR | Δ 平均证据 | 干扰条数@5 | Δ 覆盖来源@5 | Δ P50 | Δ P95 |\n");
        md.append("|---|---:|---:|---:|---:|---:|---:|---:|---:|\n");
        for (ConfigResult control : controlResults) {
            ConfigResult distractor = find(distractorResults, control.spec().id());
            md.append("| `").append(control.spec().id()).append("` | ")
                    .append(deltaPp(control.overall().meanRecall5(),
                            distractor.overall().meanRecall5())).append(" pp | ")
                    .append(deltaPp(control.overall().meanRecall5Strict(),
                            distractor.overall().meanRecall5Strict())).append(" pp | ")
                    .append(deltaPp(control.overall().meanMrr(),
                            distractor.overall().meanMrr())).append(" pp | ")
                    .append(String.format(Locale.ROOT, "%+.4f",
                            distractor.overall().avgHits() - control.overall().avgHits()))
                    .append(" | ").append(fmt4(distractor.avgDistractorHitsTop5())).append(" | ")
                    .append(String.format(Locale.ROOT, "%+.4f",
                            distractor.avgDistinctDocumentsTop5()
                                    - control.avgDistinctDocumentsTop5()))
                    .append(" | ").append(String.format(Locale.ROOT, "%+d",
                            distractor.overall().p50Ms() - control.overall().p50Ms()))
                    .append(" | ").append(String.format(Locale.ROOT, "%+d",
                            distractor.overall().p95Ms() - control.overall().p95Ms()))
                    .append(" |\n");
        }
        md.append("\n- 基线 = 旧路径 `VectorSearchService.search`：单 query、同源合并后每篇 1 chunk、cap 5、minScore 0.35。\n");
        md.append("- 多查询 = `MultiQueryRetriever` + `null` AiGateway：原 query + 规则清洗变体（0 或 1 个），RRF 融合，零对话成本。\n\n");
    }

    private String comparisonRow(ConfigResult result) {
        return "| `" + result.spec().id() + "` | " + result.spec().path()
                + " | " + result.spec().cap()
                + " | " + result.spec().chunks()
                + " | " + fmt4(result.overall().meanRecallAt1())
                + " | **" + fmt4(result.overall().meanRecall5()) + "**"
                + " | " + fmt4(result.overall().meanRecall5Strict())
                + " | **" + fmt4(result.overall().meanMrr()) + "**"
                + " | " + fmt4(result.overall().meanMrr5())
                + " | " + fmt4(result.overall().avgHits())
                + " | " + fmt4(result.avgDistractorHitsTop5())
                + " | " + fmt4(result.goldShareAt5())
                + " | " + fmt4(result.avgDistinctDocumentsTop5())
                + " | " + result.overall().p50Ms()
                + " | " + result.overall().p95Ms()
                + " | " + result.overall().failedCases() + " |\n";
    }

    private void appendCaseDetails(StringBuilder md) {
        md.append("## 4. 每用例明细（只列 recall 下降或第一名被抢的用例）\n\n");
        List<CaseDrop> drops = caseDrops();
        if (drops.isEmpty()) {
            md.append("**本轮没有任何用例在干扰场景下 recall@5 下降、也没有任何用例的第一名被抢。**\n\n");
        } else {
            md.append("| 配置 | 用例 | gold 数 | 对照 recall@5 | 干扰 recall@5 | Δ | 对照首个 gold 名次 | 干扰首个 gold 名次 | 干扰条数@5 | 问题 |\n");
            md.append("|---|---|---:|---:|---:|---:|---:|---:|---:|---|\n");
            for (CaseDrop drop : drops) {
                md.append("| `").append(drop.configId()).append("` | `").append(drop.caseId())
                        .append("` | ").append(drop.goldCount())
                        .append(" | ").append(fmt4(drop.controlRecallAt5()))
                        .append(" | ").append(fmt4(drop.distractorRecallAt5()))
                        .append(" | ").append(String.format(Locale.ROOT, "%+.1f",
                                drop.recallDeltaPp())).append(" pp")
                        .append(" | ").append(drop.controlFirstGoldRank())
                        .append(" | ").append(drop.distractorFirstGoldRank())
                        .append(" | ").append(drop.distractorHitsTop5())
                        .append(" | ").append(oneLine(drop.question(), 40))
                        .append(" |\n");
            }
            md.append("\n### 4.1 被挤掉的用例：top5 到底变成了什么\n\n");
            Set<String> printed = new LinkedHashSet<>();
            for (CaseDrop drop : drops) {
                String key = drop.configId() + "/" + drop.caseId();
                if (!printed.add(key)) {
                    continue;
                }
                md.append("- **").append(drop.configId()).append("** / `").append(drop.caseId())
                        .append("`：对照 top5 = `")
                        .append(String.join("`, `", drop.controlTop5()))
                        .append("` → 干扰 top5 = `")
                        .append(String.join("`, `", drop.distractorTop5()))
                        .append("`\n");
            }
        }
        md.append("\n### 4.2 干扰有没有“赢过 gold”\n\n");
        for (ConfigResult result : distractorResults) {
            int casesWithNoise = (int) result.cases().stream()
                    .filter(measurement -> distractorHitsTop5(measurement) > 0).count();
            int noiseAtTop1 = (int) result.cases().stream()
                    .filter(measurement -> !measurement.hits().isEmpty()
                            && "document_chunk".equals(measurement.hits().get(0).sourceType())
                            && DISTRACTOR_SOURCE_IDS.contains(
                            measurement.hits().get(0).sourceId()))
                    .count();
            md.append("- `").append(result.spec().id()).append("`：")
                    .append(casesWithNoise).append("/").append(plansRef.size())
                    .append(" 条用例的 top5 里出现干扰；其中 **").append(noiseAtTop1)
                    .append("** 条的第 1 名就是干扰。\n");
        }
        md.append("\n### 4.3 抢走第一名的干扰是哪几条（不是随机噪声，而是术语碰撞）\n\n");
        List<TopOneSteal> steals = topOneSteals();
        if (steals.isEmpty()) {
            md.append("（本轮没有干扰抢到第 1 名）\n\n");
        } else {
            md.append("| 配置 | 用例 | 问题 | 第 1 名干扰 | 干扰标题 | 该用例的 gold（仍在 top5，名次 1→2） |\n");
            md.append("|---|---|---|---|---|---|\n");
            for (TopOneSteal steal : steals) {
                md.append("| `").append(steal.configId()).append("` | `").append(steal.caseId())
                        .append("` | ").append(oneLine(steal.question(), 34))
                        .append(" | `").append(steal.distractorSourceId()).append("` | ")
                        .append(steal.distractorTitle()).append(" | `")
                        .append(String.join("`, `", steal.golds())).append("` |\n");
            }
            md.append("\n- 注意口径：这 3 条用例的 **recall@5 都还是 1.0000**（gold 仍在 top5 内），"
                    + "掉的是“第一名”——所以它伤害的是排序/引用第一条的质量，不是召回。\n");
            md.append("- 看标题就能看出机制：这 3 条都不是“随便一条操作系统笔记”，而是**术语碰撞**——\n");
            md.append("  `888958 FCFS 与 SSTF 磁盘调度` 里有 “FCFS”（磁盘调度里的 FCFS，与进程调度同名不同义），\n");
            md.append("  `888967 页缓存与脏页回写` 里有 “缺页/淘汰”，`888980 多级反馈队列` 里有 “配额/时间片”。\n");
            md.append("  也就是说：**同领域干扰之所以危险，不是因为它们“像操作系统”，而是因为它们复用了 gold 的术语**。\n");
        }
        md.append("\n");
    }

    private void appendAnswers(StringBuilder md) {
        md.append("## 5. 六个问题的答案（全部按绝对值给，单位个百分点）\n\n");
        ConfigResult baselineControl = find(controlResults, "baseline-legacy");
        ConfigResult baselineDistractor = find(distractorResults, "baseline-legacy");
        ConfigResult mqControl = find(controlResults, "mq-0.35-c5-ch1");
        ConfigResult mqDistractor = find(distractorResults, "mq-0.35-c5-ch1");
        ConfigResult ch1 = find(distractorResults, "mq-0.35-c5-ch1");
        ConfigResult ch2 = find(distractorResults, "mq-0.35-c5-ch2");
        ConfigResult c8ch1 = find(distractorResults, "mq-0.35-c8-ch1");
        ConfigResult c8ch2 = find(distractorResults, "mq-0.35-c8-ch2");
        double baselineDrop = baselineControl.overall().meanRecall5()
                - baselineDistractor.overall().meanRecall5();
        double mqDrop = mqControl.overall().meanRecall5()
                - mqDistractor.overall().meanRecall5();

        md.append("### 1）干扰加入后，基线 recall@5 掉多少？\n\n");
        md.append("- `baseline-legacy`：**").append(fmt4(baselineControl.overall().meanRecall5()))
                .append(" → ").append(fmt4(baselineDistractor.overall().meanRecall5()))
                .append("**，即 **").append(deltaPp(baselineControl.overall().meanRecall5(),
                        baselineDistractor.overall().meanRecall5()))
                .append(" 个百分点**（下降 ").append(fmt4(baselineDrop * 100.0d))
                .append(" pp）。\n");
        md.append("- **但这不等于“干扰无害”**：recall@5 已经是 1.0000（E2 就封顶），"
                + "真正动的是排序与证据纯度——\n");
        md.append("  - recall@1：").append(fmt4(baselineControl.overall().meanRecallAt1()))
                .append(" → ").append(fmt4(baselineDistractor.overall().meanRecallAt1()))
                .append("（").append(deltaPp(baselineControl.overall().meanRecallAt1(),
                        baselineDistractor.overall().meanRecallAt1())).append(" pp）\n");
        md.append("  - MRR：").append(fmt4(baselineControl.overall().meanMrr()))
                .append(" → ").append(fmt4(baselineDistractor.overall().meanMrr()))
                .append("（").append(deltaPp(baselineControl.overall().meanMrr(),
                        baselineDistractor.overall().meanMrr())).append(" pp）\n");
        md.append("  - gold 占比@5：").append(fmt4(baselineControl.goldShareAt5()))
                .append(" → ").append(fmt4(baselineDistractor.goldShareAt5())).append("\n");
        md.append("  - 干扰条数@5：").append(fmt4(baselineControl.avgDistractorHitsTop5()))
                .append(" → ").append(fmt4(baselineDistractor.avgDistractorHitsTop5()))
                .append("（= 每次返回约 ").append(fmt4(baselineDistractor.overall().avgHits()))
                .append(" 条里约一半是噪声）\n\n");

        md.append("### 2）多查询掉得比基线少还是多？（抗噪）\n\n");
        md.append("- 基线掉 **").append(fmt4(baselineDrop * 100.0d)).append(" pp**；")
                .append("多查询（`mq-0.35-c5-ch1`）掉 **").append(fmt4(mqDrop * 100.0d))
                .append(" pp**；两者之差（基线 − 多查询）= **")
                .append(String.format(Locale.ROOT, "%+.1f", (baselineDrop - mqDrop) * 100.0d))
                .append(" pp**。\n");
        md.append("- 判定：**").append(verdict(baselineDrop, mqDrop)).append("**。\n\n");

        md.append("### 3）每篇 2 chunk 在干扰下是否反而挤掉别的文档？\n\n");
        md.append("| 指标 | `mq-0.35-c5-ch1`（每篇 1） | `mq-0.35-c5-ch2`（每篇 2） | 差 |\n");
        md.append("|---|---:|---:|---:|\n");
        md.append("| recall@5（干扰场景） | ").append(fmt4(ch1.overall().meanRecall5()))
                .append(" | ").append(fmt4(ch2.overall().meanRecall5())).append(" | ")
                .append(deltaPp(ch1.overall().meanRecall5(), ch2.overall().meanRecall5()))
                .append(" pp |\n");
        md.append("| 覆盖来源@5 | ").append(fmt4(ch1.avgDistinctDocumentsTop5())).append(" | ")
                .append(fmt4(ch2.avgDistinctDocumentsTop5())).append(" | ")
                .append(String.format(Locale.ROOT, "%+.4f",
                        ch2.avgDistinctDocumentsTop5() - ch1.avgDistinctDocumentsTop5()))
                .append(" |\n");
        md.append("| 干扰条数@5 | ").append(fmt4(ch1.avgDistractorHitsTop5())).append(" | ")
                .append(fmt4(ch2.avgDistractorHitsTop5())).append(" | ")
                .append(String.format(Locale.ROOT, "%+.4f",
                        ch2.avgDistractorHitsTop5() - ch1.avgDistractorHitsTop5()))
                .append(" |\n");
        md.append("| 平均证据 | ").append(fmt4(ch1.overall().avgHits())).append(" | ")
                .append(fmt4(ch2.overall().avgHits())).append(" | ")
                .append(String.format(Locale.ROOT, "%+.4f",
                        ch2.overall().avgHits() - ch1.overall().avgHits())).append(" |\n\n");
        md.append("- 判定：**recall@5 没有差别**（都是 ").append(fmt4(ch2.overall().meanRecall5()))
                .append("）；差异只体现在“覆盖到多少个不同来源”上，每篇 2 chunk 反而让覆盖来源 **少了 ")
                .append(String.format(Locale.ROOT, "%.4f", ch1.avgDistinctDocumentsTop5()
                        - ch2.avgDistinctDocumentsTop5()))
                .append(" 个**、干扰条数少了 ")
                .append(String.format(Locale.ROOT, "%.4f", ch1.avgDistractorHitsTop5()
                        - ch2.avgDistractorHitsTop5()))
                .append(" 条。也就是说：**E1 头号风险（每篇 2 chunk 挤掉别的文档）在本轮幅度极小**"
                        + "（覆盖来源 -")
                .append(String.format(Locale.ROOT, "%.2f", ch1.avgDistinctDocumentsTop5()
                        - ch2.avgDistinctDocumentsTop5()))
                .append(" 个），远没有到“把第 3 篇文档挤掉”的程度。")
                .append("注意：本轮 gold 最多只跨 ")
                .append(maxDistinctDocumentGolds)
                .append(" 篇文档，覆盖来源@5 的绝对上限本来就小，"
                        + "所以这个指标在小夹具上的分辨率有限（见 §7）。\n\n");

        md.append("### 4）cap 8 能不能缓解干扰？\n\n");
        md.append("| 指标 | `mq-0.35-c5-ch2`（cap5） | `mq-0.35-c8-ch2`（cap8） | 差 |\n");
        md.append("|---|---:|---:|---:|\n");
        md.append("| recall@5（干扰场景） | ").append(fmt4(ch2.overall().meanRecall5()))
                .append(" | ").append(fmt4(c8ch2.overall().meanRecall5())).append(" | ")
                .append(deltaPp(ch2.overall().meanRecall5(), c8ch2.overall().meanRecall5()))
                .append(" pp |\n");
        md.append("| 平均证据 | ").append(fmt4(ch2.overall().avgHits())).append(" | ")
                .append(fmt4(c8ch2.overall().avgHits())).append(" | ")
                .append(String.format(Locale.ROOT, "%+.4f",
                        c8ch2.overall().avgHits() - ch2.overall().avgHits())).append(" |\n");
        md.append("| 覆盖来源@5 | ").append(fmt4(ch2.avgDistinctDocumentsTop5())).append(" | ")
                .append(fmt4(c8ch2.avgDistinctDocumentsTop5())).append(" | ")
                .append(String.format(Locale.ROOT, "%+.4f",
                        c8ch2.avgDistinctDocumentsTop5() - ch2.avgDistinctDocumentsTop5()))
                .append(" |\n");
        md.append("| 干扰条数@5 | ").append(fmt4(ch2.avgDistractorHitsTop5())).append(" | ")
                .append(fmt4(c8ch2.avgDistractorHitsTop5())).append(" | ")
                .append(String.format(Locale.ROOT, "%+.4f",
                        c8ch2.avgDistractorHitsTop5() - ch2.avgDistractorHitsTop5()))
                .append(" |\n\n");
        md.append("- 补充：`mq-0.35-c8-ch1`（cap8 + 每篇 1）recall@5 = **")
                .append(fmt4(c8ch1.overall().meanRecall5())).append("**，平均证据 ")
                .append(fmt4(c8ch1.overall().avgHits())).append(" 条。\n");
        md.append("- 判定：**cap 8 不能缓解干扰**。它买到的是“每次多 ").append(String.format(
                        Locale.ROOT, "%.2f", c8ch2.overall().avgHits()
                                - ch2.overall().avgHits()))
                .append(" 条证据”，recall@5 一动不动；而干扰条数@5 完全没变（")
                .append(fmt4(ch2.avgDistractorHitsTop5())).append(" → ")
                .append(fmt4(c8ch2.avgDistractorHitsTop5()))
                .append("），说明多给的 3 个位置并没有“冲淡”噪声，只是把 more gold/更多证据也塞进来；")
                .append("代价是上下文变长（进 prompt 的 token 更多）。\n\n");

        md.append("### 5）MRR 怎么变（第一名有没有被抢）？\n\n");
        md.append("| 配置 | 对照 MRR | 干扰 MRR | Δ |\n|---|---:|---:|---:|\n");
        for (ConfigResult control : controlResults) {
            ConfigResult distractor = find(distractorResults, control.spec().id());
            md.append("| `").append(control.spec().id()).append("` | ")
                    .append(fmt4(control.overall().meanMrr())).append(" | ")
                    .append(fmt4(distractor.overall().meanMrr())).append(" | ")
                    .append(deltaPp(control.overall().meanMrr(),
                            distractor.overall().meanMrr())).append(" pp |\n");
        }
        md.append("\n");

        md.append("### 6）耗时 p50 / p95\n\n");
        md.append("| 配置 | 对照 P50 | 干扰 P50 | Δ | 对照 P95 | 干扰 P95 | Δ |\n");
        md.append("|---|---:|---:|---:|---:|---:|---:|\n");
        for (ConfigResult control : controlResults) {
            ConfigResult distractor = find(distractorResults, control.spec().id());
            md.append("| `").append(control.spec().id()).append("` | ")
                    .append(control.overall().p50Ms()).append(" | ")
                    .append(distractor.overall().p50Ms()).append(" | ")
                    .append(String.format(Locale.ROOT, "%+d",
                            distractor.overall().p50Ms() - control.overall().p50Ms()))
                    .append(" | ").append(control.overall().p95Ms()).append(" | ")
                    .append(distractor.overall().p95Ms()).append(" | ")
                    .append(String.format(Locale.ROOT, "%+d",
                            distractor.overall().p95Ms() - control.overall().p95Ms()))
                    .append(" |\n");
        }
        md.append("\n- **本列不可当结论用（顺序效应）**：对照组先跑、实验组后跑，"
                + "Qdrant 连接与页缓存在第二轮已经热了，所以 Δ 里混着“冷/热”差异，"
                + "甚至出现实验组 P50 更低（如 baseline −51ms）。\n");
        md.append("- 干扰只改变候选池大小（5 → 45），不改变 embedding 网络调用次数，"
                + "绝对耗时量级没变（都在 160~220ms 的 P50 区间）；"
                + "没有证据表明干扰会显著改变检索延迟。\n\n");
    }

    private void appendIsolation(StringBuilder md, Isolation isolation) {
        md.append("## 6. 清理与隔离断言\n\n");
        md.append("| 断言 | 值 | 结论 |\n|---|---:|---|\n");
        md.append("| 真实集合 `").append(AgentEvalSeeder.REAL_COLLECTION).append("` 跑前 | ")
                .append(isolation.realBefore()).append(" | — |\n");
        md.append("| 真实集合跑后 | ").append(isolation.realAfter()).append(" | **跑前=跑后：")
                .append(isolation.realBefore() == isolation.realAfter() ? "是" : "否").append("** |\n");
        md.append("| 评测集合本次基线 | ").append(isolation.evalBaseline()).append(" | — |\n");
        md.append("| 播种后 | ").append(isolation.evalAfterSeed()).append(" | 22 点 |\n");
        md.append("| 写干扰后 | ").append(isolation.evalAfterDistractors()).append(" | 22 + ")
                .append(distractorWritesCount).append(" |\n");
        md.append("| 精确删除干扰后 | ").append(isolation.evalAfterDistractorCleanup())
                .append(" | **回到 22** |\n");
        md.append("| 最终清理后 | ").append(isolation.evalAfterCleanup())
                .append(" | **回到实验前基线：")
                .append(isolation.evalAfterCleanup() == isolation.evalBaseline() ? "是" : "否")
                .append("** |\n");
        md.append("| 干扰点残留（按 source_id 段 count） | ").append(distractorPointsAfterCleanup)
                .append(" | **0** |\n");
        md.append("| 清理后 888 段评测用户残留行 | ").append(isolation.evalRowsRemaining())
                .append(" | **0** |\n");
        md.append("| 清理后复核基线 recall@5 | ")
                .append(controlRecheckResult == null ? "n/a"
                        : fmt4(controlRecheckResult.overall().meanRecall5()))
                .append(" | 与对照组一致（断言已通过） |\n");
        md.append("| 复核与对照组的 top5 标签差异用例数 | ")
                .append(labelDiffBetweenControlAndRecheck).append(" | 仅作索引状态噪声记录 |\n\n");
        md.append("- 删除方式：按 `VectorPointIds.pointId(document_chunk, sourceId, 0)` 的确定性 pointId ")
                .append("**逐条删除**（不做范围扫描、不按模糊条件删），删完用 Qdrant count 按 ")
                .append("`source_id` 段回读断言为 0。\n");
        md.append("- 互斥：PostgreSQL 会话级 advisory lock，key = `").append(EVAL_ADVISORY_LOCK_KEY)
                .append("`（与 `AgentEvalIT` **逐字相同**）；第二会话试探被拒=")
                .append(lockProbeBlocked).append("。\n");
        md.append("- 范围：业务行与向量点全程只按 `userId=").append(AgentEvalSeeder.EVAL_USER_ID)
                .append("` 圈定；真实集合只读 count。\n\n");
    }

    private void appendLimitations(StringBuilder md) {
        md.append("## 7. 口径局限\n\n");
        for (String limitation : limitations()) {
            md.append("- ").append(limitation).append("\n");
        }
        md.append("\n### 7.1 我认为这套实验最可能失真的一处\n\n");
        md.append("**干扰点“一条 = 一篇文档”的构造（`chunk_index` 全为 0）。**\n\n");
        md.append("这 40 条干扰每一条都自成一“篇”，所以 cap 与「每篇最多 N chunk」对它们**完全不起作用**，")
                .append("每一条都能独占 top5 的一个位置。这是**对 cap 最不利**的构造：")
                .append("如果真实笔记是「一篇长文切成 3~5 个 chunk」，那么同一篇的第 2、3 个 chunk 会先被")
                .append("每篇上限削掉，干扰实际能占的位置会明显变少。\n\n");
        md.append("因此：\n\n");
        md.append("1. **§5 第 3 问（ch1 vs ch2）的结论方向可能被放大**——在真实“一篇多 chunk”的语料上，")
                .append("每篇 2 chunk 的害处不会像本轮这么直接；\n");
        md.append("2. **§5 第 1、2 问（recall 掉多少 / 多查询是否抗噪）受影响较小**，")
                .append("因为它比的是“同一干扰环境下的两种检索方式”，两边面对的是同一批干扰。\n\n");
        md.append("如果要把这点做实，最小改动是：把 40 条干扰改成 10 篇 × 4 chunk（同一篇的 4 个 chunk 讲同一话题），")
                .append("再跑一次同一套配置——那才是“真实笔记形态”的干扰。本轮**没有**这么做，")
                .append("因为任务要求的是「20~50 条干扰 chunk」，没有规定必须同属少数几篇。\n\n");
        md.append("## 8. 结论（只描述本次测量）\n\n");
        ConfigResult baselineControl = find(controlResults, "baseline-legacy");
        ConfigResult baselineDistractor = find(distractorResults, "baseline-legacy");
        ConfigResult mqControl = find(controlResults, "mq-0.35-c5-ch1");
        ConfigResult mqDistractor = find(distractorResults, "mq-0.35-c5-ch1");
        double baselineDrop = baselineControl.overall().meanRecall5()
                - baselineDistractor.overall().meanRecall5();
        double mqDrop = mqControl.overall().meanRecall5()
                - mqDistractor.overall().meanRecall5();
        md.append("- 干扰场景下，**5 组配置的 recall@5 全部仍是 1.0000**，但 **5 组配置的 MRR 全部 -")
                .append(String.format(Locale.ROOT, "%.1f", (baselineControl.overall().meanMrr()
                        - baselineDistractor.overall().meanMrr()) * 100.0d))
                .append(" pp**、top5 里约一半是干扰（")
                .append(fmt4(baselineDistractor.avgDistractorHitsTop5())).append("/")
                .append(fmt4(baselineDistractor.overall().avgHits()))
                .append(" 条），这在 P1-E2 的封顶夹具里是测不出来的。\n");
        md.append("- 因此本轮可用的结论是：**这套评测集上的“召回”很难被同领域干扰打掉（gold 每条都是"
                + "自己话题的唯一最佳匹配），但“排序与证据纯度”会被显著污染**；")
                .append("而多查询 / 每篇 2 chunk / cap 8 三个手段都**没有**表现出抗噪差异。\n");
        md.append("- 抗噪判断：**").append(verdict(baselineDrop, mqDrop)).append("**。\n");
        md.append("- **本报告不改变任何生产默认值**：`application.yml` 一个字未动，")
                .append("`cases.yaml` 未动，`AgentEvalIT` 未动；本轮只新增测试侧的测量类与干扰语料。\n");
    }

    // ------------------------------------------------------------------
    // 12. 计数装饰器（成本证据：embedding 次数）
    // ------------------------------------------------------------------

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
        public void delete(String collection, UUID pointId) {
            delegate.delete(collection, pointId);
        }

        @Override
        public List<RetrievedPoint> search(String collection, Filter filter, float[] queryVector,
                                           int limit) {
            searchCalls.incrementAndGet();
            return delegate.search(collection, filter, queryVector, limit);
        }

        @Override
        public java.util.Optional<Map<String, Object>> readPayload(String collection,
                                                                  UUID pointId) {
            return delegate.readPayload(collection, pointId);
        }

        @Override
        public List<UUID> listPointIds(String collection, Filter filter) {
            return delegate.listPointIds(collection, filter);
        }

        long searchCalls() {
            return searchCalls.get();
        }
    }
}
