package com.example.aimindmap.agentruntime;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.aimindmap.agenteval.EvalConfigSnapshot;
import com.example.aimindmap.importpdf.PdfImportWorker;
import com.example.aimindmap.vectorindex.VectorIndexWorker;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * P1-F 开工前必办②⑤：<b>用真实历史回答离线抽检</b> D2（引用自检）与 E3b（回答侧泄露检查）。
 *
 * <p>为什么要有它：D2 的关键结论句判定是纯规则（数字 / 定义词「是·指」/ 因果词），
 * E3b 的泄露规则也是正则，两者的<b>误判率都没有真实数据</b>，只用构造样本的单测覆盖过。
 * 本类拿开发库里已有的历史回答跑一遍生产代码，量化「规则会命中多少」，<b>不是</b>造样本。
 *
 * <h2>只读与隐私红线（硬约束）</h2>
 * <ul>
 *   <li>连的是<b>当前 datasource</b>（本机 5432/aimindmap，与全量测试同一个库），
 *       不启 Testcontainers、不新建库、不写任何表——只有 SELECT；</li>
 *   <li>输出<b>只允许</b>数量、占比、规则名、内部 runId；<b>不输出</b>回答正文、提问正文、
 *       证据标题/片段，一行都不写进日志与报告（{@link #ANSWER_TEXT_KEYS} 是给报告字段用的白名单）；</li>
 *   <li>抽样上限 {@link #MAX_RUNS} 条，只做 verify/check 这类纯 CPU 操作，不调模型、不花钱、不耗时。</li>
 * </ul>
 *
 * <h2>能证明什么、不能证明什么（如实写）</h2>
 * <ul>
 *   <li><b>能</b>：在真实回答上量出「有多少条回答会被规则标出 unsupported 关键结论句」、
 *       「平均几条」、「按触发原因（数字/定义词/因果词）分组各多少」；</li>
 *   <li><b>不能</b>：当前 {@code agent_evidence} 是 <b>0 行</b>，历史回答没有任何证据台账，
 *       所有 {@code [E#]} 都无法判为合法 → {@code grounded} 恒为 false → D2 会走 P0 的
 *       「整体无依据」提示、<b>不会</b>追加逐句「补充说明」。所以本类测到的是
 *       <b>「关键结论句规则的触发面」</b>，<b>不是</b>补充说明的真实误报率；</li>
 *   <li><b>不能</b>：{@code answer_summary} 是被截断的摘要（本库最长 501 字），不是完整回答，
 *       句界可能被截断，数字只能当上界看；</li>
 *   <li><b>不能</b>：E3b 笔记侧（{@code EvidenceSanitizer} 的 suspicious 误报）没有历史数据可抽
 *       （{@code agent_evidence} 0 行），如实记「笔记侧注入误报率未验证」，不用构造样本冒充。</li>
 * </ul>
 *
 * <p>运行方式（默认门禁不含本类：{@code @Tag("eval")} 被 surefire excludedGroups 排除）：
 * <pre>
 * mvn.cmd -f mindmap-backend/pom.xml -B -Peval test -Dtest=AgentOfflineAuditIT \
 *     '-Djdk.net.unixdomain.tmpdir=D:\tmp' '-DargLine=-Djdk.net.unixdomain.tmpdir=D:\tmp'
 * </pre>
 */
@Tag("eval")
@SpringBootTest(properties = {
        // 只读审计：关掉 MQ，避免任何后台 worker 去"直接认领"真实任务
        "app.mq.enabled=false",
        // 后台轮询周期拉到 1 天：本类只读 DB，绝不让 worker 碰任何行
        "app.mq.requeue-scan-delay-ms=86400000",
        "app.qdrant.worker-fixed-delay-ms=86400000"
})
class AgentOfflineAuditIT {

    private static final Logger log = LoggerFactory.getLogger(AgentOfflineAuditIT.class);

    /** 抽样上限（开发库当前正好 45 条有回答的 run，全跑）。 */
    private static final int MAX_RUNS = 45;

    /** 与需求一致：`answer_summary` 长度 &gt; 20 才算"有回答"。 */
    private static final int MIN_ANSWER_CHARS = 20;

    /** 报告名（不覆盖也可指定）。 */
    private static final String REPORT_BASENAME =
            System.getProperty("audit.report.name", LocalDate.now() + "-offline-audit");

    /** 报告里允许出现的键（白名单，防止以后有人顺手把正文塞进报告）。 */
    static final Set<String> ANSWER_TEXT_KEYS = Set.of(
            "answer", "question", "snippet", "title", "userMessage", "answerSummary");

    // 下面三个判定只用于「归因统计」：句子是否算关键结论句仍以 CitationVerifier 为准。
    // 关键字与 CitationVerifier 的 DEFINITION_WORDS / CAUSAL_WORDS 保持一致（漂移只会影响归因，不影响结论）。
    private static final List<String> DEFINITION_WORDS = List.of("是", "指", "称为", "定义", "叫做");
    private static final List<String> CAUSAL_WORDS =
            List.of("因为", "所以", "因此", "由于", "导致", "从而", "也就是说");
    private static final Pattern ARABIC_NUMBER = Pattern.compile("[0-9０-９]");
    private static final Pattern CHINESE_NUMBER_WITH_UNIT = Pattern.compile(
            "(?<!第)[零一二三四五六七八九十百千万两几数]+"
                    + "[个种条次类项倍层段篇页章节天年周月分秒名位份组轮](?!性)");
    private static final Pattern CITATION_PATTERN = Pattern.compile("\\[E(\\d+)]");

    /** 两个后台轮询 worker 换成 mock：与 AgentEvalIT 同一理由（关 MQ 时它们会直接认领真实任务）。 */
    @MockBean private VectorIndexWorker vectorIndexWorker;
    @MockBean private PdfImportWorker pdfImportWorker;

    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private Environment environment;

    @Test
    void auditHistoricalAnswers() throws Exception {
        Map<String, String> configSnapshot = EvalConfigSnapshot.capture(environment);
        log.info("==== 离线审计开关快照（与 P1-F 评测同一套键）==== {}",
                EvalConfigSnapshot.oneLine(configSnapshot));

        // ---- 0. 库内现状（只读计数） ----
        long runsTotal = count("agent_runs");
        long runsWithAnswer = count(
                "agent_runs WHERE length(coalesce(answer_summary, '')) > " + MIN_ANSWER_CHARS);
        long evidenceRows = count("agent_evidence");
        long suspiciousEvidenceRows = count("agent_evidence WHERE suspicious = TRUE");
        long citationRows = count("agent_citations");
        log.info("库内现状：agent_runs 总行数={} 有回答(>{}字)={} agent_evidence={}（suspicious={}）"
                        + " agent_citations={}",
                runsTotal, MIN_ANSWER_CHARS, runsWithAnswer, evidenceRows, suspiciousEvidenceRows,
                citationRows);

        // ---- 1. 抽样（只读；上限 45，按开始时间倒序，结果稳定） ----
        List<RunRow> runs = jdbc.query("""
                SELECT id, user_message, answer_summary, tool_call_count
                FROM agent_runs
                WHERE length(coalesce(answer_summary, '')) > ?
                ORDER BY started_at DESC, id DESC
                LIMIT ?
                """, (rs, rowNum) -> new RunRow(
                        rs.getLong("id"),
                        rs.getString("user_message"),
                        rs.getString("answer_summary"),
                        rs.getInt("tool_call_count")),
                MIN_ANSWER_CHARS, MAX_RUNS);
        assertThat(runs).as("至少要有一条有回答的历史 run 才能抽检").isNotEmpty();
        assertThat(runs).as("抽样上限 %s 条（不做全表扫描）", MAX_RUNS).hasSizeLessThanOrEqualTo(MAX_RUNS);

        // ---- 2. 逐条跑生产规则（输入只在内存里，输出只有计数） ----
        List<AnswerAudit> audits = new ArrayList<>(runs.size());
        for (RunRow run : runs) {
            List<EvidenceItem> evidence = loadEvidence(run.id());
            boolean knowledgeIntent = run.toolCallCount() > 0;
            CitationCheckResult citationCheck =
                    CitationVerifier.verify(run.answer(), evidence, knowledgeIntent);
            List<String> keySentences =
                    CitationVerifier.conclusionSentenceCandidates(run.answer()).stream()
                            .filter(CitationVerifier::isKeyConclusionSentence)
                            .toList();
            Set<String> knownKeys = evidence.stream()
                    .map(EvidenceItem::key)
                    .filter(key -> key != null && !key.isBlank())
                    .map(key -> key.toUpperCase(Locale.ROOT))
                    .collect(Collectors.toSet());

            int unsupported = 0;
            int numberTriggered = 0;
            int definitionTriggered = 0;
            int causalTriggered = 0;
            int definitionOnly = 0;
            for (String sentence : keySentences) {
                if (hasValidCitation(sentence, knownKeys)) {
                    continue;
                }
                unsupported++;
                boolean number = hasNumber(sentence);
                boolean definition = containsAny(sentence, DEFINITION_WORDS);
                boolean causal = containsAny(sentence, CAUSAL_WORDS);
                if (number) {
                    numberTriggered++;
                }
                if (definition) {
                    definitionTriggered++;
                }
                if (causal) {
                    causalTriggered++;
                }
                if (definition && !number && !causal) {
                    definitionOnly++;
                }
            }
            // 自检：我们按同一套切句规则独立数出来的条数，必须与生产 verify 的结果一致。
            // 不一致说明两边口径漂移（例如 MAX_SENTENCES 截断），报告里的分母就不能用。
            assertThat(citationCheck.unsupportedSentenceCount())
                    .as("生产 CitationVerifier 的 unsupported 句数必须等于本次独立统计 runId=%s", run.id())
                    .isEqualTo(unsupported);

            AnswerLeakChecker.Result leak = AnswerLeakChecker.check(run.answer(), run.question());
            audits.add(new AnswerAudit(run.id(), run.toolCallCount(), evidence.size(),
                    keySentences.size(), unsupported, knowledgeIntent, citationCheck.grounded(),
                    citationCheck.citationCount(), citationCheck.invalidCitationCount(),
                    numberTriggered, definitionTriggered, causalTriggered, definitionOnly,
                    leak.leaked(), leak.noticeRuleNames(), leak.keywordRuleNames()));
            log.info("历史回答抽检 runId={} 工具调用={} 证据={} 关键结论句={} unsupported={} "
                            + "grounded={} 合法引用={} 非法引用次数={} 泄露命中={} notice规则={} 关键词规则={}",
                    run.id(), run.toolCallCount(), evidence.size(), keySentences.size(), unsupported,
                    citationCheck.grounded(), citationCheck.citationCount(),
                    citationCheck.invalidCitationCount(), leak.leaked(),
                    leak.noticeRuleNames(), leak.keywordRuleNames());
        }

        // ---- 3. 汇总（只有数量/占比/规则名/runId） ----
        int audited = audits.size();
        int answersWithUnsupported = (int) audits.stream().filter(a -> a.unsupported() > 0).count();
        int unsupportedTotal = audits.stream().mapToInt(AnswerAudit::unsupported).sum();
        int keySentencesTotal = audits.stream().mapToInt(AnswerAudit::keySentences).sum();
        double averageUnsupported = audited == 0 ? 0.0d : unsupportedTotal / (double) audited;
        int medianUnsupported = median(audits.stream().map(AnswerAudit::unsupported).toList());
        int maxUnsupported = audits.stream().mapToInt(AnswerAudit::unsupported).max().orElse(0);
        double answerHitRate = audited == 0 ? 0.0d : answersWithUnsupported / (double) audited;
        double sentenceHitRate = keySentencesTotal == 0
                ? 0.0d : unsupportedTotal / (double) keySentencesTotal;

        Map<String, Integer> distribution = new LinkedHashMap<>();
        distribution.put("0", 0);
        distribution.put("1", 0);
        distribution.put("2", 0);
        distribution.put("3-5", 0);
        distribution.put("6+", 0);
        for (AnswerAudit audit : audits) {
            distribution.merge(bucket(audit.unsupported()), 1, Integer::sum);
        }

        int groundedAnswers = (int) audits.stream().filter(AnswerAudit::grounded).count();
        int knowledgeAnswers = (int) audits.stream().filter(AnswerAudit::knowledgeIntent).count();
        // D2 补充说明的真实触发条件（见 KnowledgeAssistantStreamer.buildCitationSupplement）：
        // runtime.enabled=true && !ungrounded（=知识类且至少 1 个合法引用） && unsupported>0。
        int supplementTriggersIfRuntimeOn = (int) audits.stream()
                .filter(a -> a.knowledgeIntent() && a.grounded() && a.unsupported() > 0)
                .count();
        boolean runtimeEnabled = "true".equalsIgnoreCase(
                configSnapshot.getOrDefault("app.agent.runtime.enabled", ""));

        int numberTriggered = audits.stream().mapToInt(AnswerAudit::numberTriggered).sum();
        int definitionTriggered = audits.stream().mapToInt(AnswerAudit::definitionTriggered).sum();
        int causalTriggered = audits.stream().mapToInt(AnswerAudit::causalTriggered).sum();
        int definitionOnly = audits.stream().mapToInt(AnswerAudit::definitionOnly).sum();

        List<AnswerAudit> topUnsupported = audits.stream()
                .sorted(Comparator.comparingInt(AnswerAudit::unsupported).reversed()
                        .thenComparingLong(AnswerAudit::runId))
                .limit(5)
                .toList();

        // ---- 4. E3b：回答侧泄露检查汇总（只记规则名与数量） ----
        int leakedAnswers = (int) audits.stream().filter(AnswerAudit::leaked).count();
        int noticeAnswers = (int) audits.stream().filter(a -> !a.noticeRules().isEmpty()).count();
        int keywordAnswers = (int) audits.stream().filter(a -> !a.keywordRules().isEmpty()).count();
        Map<String, Integer> noticeRuleCounts = new LinkedHashMap<>();
        Map<String, Integer> keywordRuleCounts = new LinkedHashMap<>();
        for (String rule : AnswerLeakChecker.ruleNames()) {
            noticeRuleCounts.put(rule, 0);
            keywordRuleCounts.put(rule, 0);
        }
        for (AnswerAudit audit : audits) {
            for (String rule : audit.noticeRules()) {
                noticeRuleCounts.merge(rule, 1, Integer::sum);
            }
            for (String rule : audit.keywordRules()) {
                keywordRuleCounts.merge(rule, 1, Integer::sum);
            }
        }

        log.info("==== D2 抽检汇总 ==== 抽样={} 条；有 unsupported 句的回答={} 条（{}，答案级命中率）；"
                        + "unsupported 句总数={}，平均每条={}，中位数={}，最多={}；"
                        + "关键结论句总数={}，句级命中率={}；分布={}",
                audited, answersWithUnsupported, percent(answerHitRate), unsupportedTotal,
                String.format("%.2f", averageUnsupported), medianUnsupported, maxUnsupported,
                keySentencesTotal, percent(sentenceHitRate), distribution);
        log.info("==== D2 触发原因（对 unsupported 句归因，可多选）==== 含数字={} 含定义词={} 含因果词={} "
                        + "只靠定义词（无数字无因果）={}",
                numberTriggered, definitionTriggered, causalTriggered, definitionOnly);
        log.info("==== D2 补充说明真实触发面 ==== 知识类回答={} 有合法引用(grounded)={} "
                        + "runtime.enabled={}；按当前证据（{} 行）若打开 runtime 会追加补充说明的={} 条",
                knowledgeAnswers, groundedAnswers, runtimeEnabled, evidenceRows,
                supplementTriggersIfRuntimeOn);
        log.info("==== E3b 回答侧抽检汇总 ==== 抽样={} leaked={}（{}） 会追加用户可见提示={} 只告警的关键词命中={}；"
                        + "notice规则计数={}；关键词规则计数={}",
                audited, leakedAnswers, percent(audited == 0 ? 0 : leakedAnswers / (double) audited),
                noticeAnswers, keywordAnswers, noticeRuleCounts, keywordRuleCounts);
        log.info("==== E3b 笔记侧 ==== agent_evidence={} 行、suspicious={} 行 → "
                        + "笔记侧注入误报率未验证（无历史证据可抽；构造样本单测见 EvidenceSanitizerTest）",
                evidenceRows, suspiciousEvidenceRows);

        // ---- 5. 断言（只钉"数据可算"，不改任何生产通过标准） ----
        assertThat(audits).hasSize(audited);
        assertThat(answersWithUnsupported).isBetween(0, audited);
        assertThat(distribution.values().stream().mapToInt(Integer::intValue).sum()).isEqualTo(audited);
        assertThat(leakedAnswers).isBetween(0, audited);
        assertThat(noticeAnswers).isLessThanOrEqualTo(leakedAnswers);
        assertThat(keywordAnswers).isLessThanOrEqualTo(leakedAnswers);

        // ---- 6. 落盘（只含数量/占比/规则名/runId；白名单见 ANSWER_TEXT_KEYS） ----
        writeReport(configSnapshot, runsTotal, runsWithAnswer, evidenceRows, suspiciousEvidenceRows,
                citationRows, audited, answersWithUnsupported, unsupportedTotal, keySentencesTotal,
                averageUnsupported, medianUnsupported, maxUnsupported, answerHitRate, sentenceHitRate,
                distribution, groundedAnswers, knowledgeAnswers, supplementTriggersIfRuntimeOn,
                runtimeEnabled, numberTriggered, definitionTriggered, causalTriggered, definitionOnly,
                topUnsupported, leakedAnswers, noticeAnswers, keywordAnswers, noticeRuleCounts,
                keywordRuleCounts);
    }

    // ------------------------------------------------------------------
    // 取数（全部只读）
    // ------------------------------------------------------------------

    private long count(String fromClause) {
        Long value = jdbc.queryForObject("SELECT count(*) FROM " + fromClause, Long.class);
        return value == null ? 0L : value;
    }

    private List<EvidenceItem> loadEvidence(long runId) {
        return jdbc.query("""
                SELECT evidence_key, resource_type, resource_id, chunk_index, score, title, snippet,
                       suspicious
                FROM agent_evidence WHERE run_id = ? ORDER BY id ASC
                """, (rs, rowNum) -> new EvidenceItem(
                rs.getString("evidence_key"),
                rs.getString("resource_type"),
                rs.getString("resource_id"),
                rs.getObject("chunk_index") == null ? null : rs.getInt("chunk_index"),
                rs.getObject("score") == null ? 0.0d : rs.getDouble("score"),
                rs.getString("title"),
                rs.getString("snippet"),
                rs.getBoolean("suspicious")), runId);
    }

    // ------------------------------------------------------------------
    // 判定辅助（只用于归因统计，不改生产口径）
    // ------------------------------------------------------------------

    private static boolean hasValidCitation(String sentence, Set<String> knownKeys) {
        Matcher matcher = CITATION_PATTERN.matcher(sentence);
        while (matcher.find()) {
            if (knownKeys.contains("E" + matcher.group(1))) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasNumber(String sentence) {
        return ARABIC_NUMBER.matcher(sentence).find()
                || CHINESE_NUMBER_WITH_UNIT.matcher(sentence).find();
    }

    private static boolean containsAny(String sentence, List<String> words) {
        for (String word : words) {
            if (sentence.contains(word)) {
                return true;
            }
        }
        return false;
    }

    static String bucket(int unsupported) {
        if (unsupported <= 0) {
            return "0";
        }
        if (unsupported == 1) {
            return "1";
        }
        if (unsupported == 2) {
            return "2";
        }
        return unsupported <= 5 ? "3-5" : "6+";
    }

    private static int median(List<Integer> values) {
        if (values.isEmpty()) {
            return 0;
        }
        List<Integer> sorted = new ArrayList<>(values);
        sorted.sort(Integer::compareTo);
        int middle = sorted.size() / 2;
        return sorted.size() % 2 == 1
                ? sorted.get(middle)
                : (sorted.get(middle - 1) + sorted.get(middle)) / 2;
    }

    private static String percent(double rate) {
        return String.format("%.2f%%", rate * 100.0d);
    }

    // ------------------------------------------------------------------
    // 报告（只含数量/占比/规则名/runId）
    // ------------------------------------------------------------------

    private void writeReport(Map<String, String> configSnapshot,
                             long runsTotal, long runsWithAnswer, long evidenceRows,
                             long suspiciousEvidenceRows, long citationRows, int audited,
                             int answersWithUnsupported, int unsupportedTotal,
                             int keySentencesTotal, double averageUnsupported, int medianUnsupported,
                             int maxUnsupported, double answerHitRate, double sentenceHitRate,
                             Map<String, Integer> distribution, int groundedAnswers,
                             int knowledgeAnswers, int supplementTriggersIfRuntimeOn,
                             boolean runtimeEnabled, int numberTriggered, int definitionTriggered,
                             int causalTriggered, int definitionOnly,
                             List<AnswerAudit> topUnsupported, int leakedAnswers, int noticeAnswers,
                             int keywordAnswers, Map<String, Integer> noticeRuleCounts,
                             Map<String, Integer> keywordRuleCounts) throws IOException {
        Path reportDir = resolveDocsDir().resolve("agent-eval");
        Files.createDirectories(reportDir);
        Path jsonPath = reportDir.resolve(REPORT_BASENAME + ".json");

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("reportType", "offline-audit");
        root.put("reportName", REPORT_BASENAME);
        root.put("generatedAt", OffsetDateTime.now().toString());
        root.put("purpose",
                "P1-F 开工前②⑤：用开发库真实历史回答离线抽检 CitationVerifier（D2）与 "
                        + "AnswerLeakChecker（E3b 回答侧），量化规则触发面，不调模型、不花钱。");
        root.put("privacy",
                "只含数量/占比/规则名/内部 runId；不含任何回答正文、提问正文、证据标题/正文。"
                        + "允许键白名单=" + ANSWER_TEXT_KEYS);
        root.put("reportIsReadOnly", true);
        root.put("configSnapshot", new LinkedHashMap<>(configSnapshot));
        root.put("configSnapshotNote",
                "与 AgentEvalIT 同一套键、同一个来源（Spring Environment）；该快照决定「指标属于哪套口径」。");

        Map<String, Object> dataset = new LinkedHashMap<>();
        dataset.put("source", "agent_runs.answer_summary（当前 datasource，只读）");
        dataset.put("runsTotalInDb", runsTotal);
        dataset.put("runsWithAnswerText", runsWithAnswer);
        dataset.put("sampledRuns", audited);
        dataset.put("samplingRule", "answer_summary 长度 > " + MIN_ANSWER_CHARS
                + "，按 started_at DESC / id DESC 取前 " + MAX_RUNS + " 条");
        dataset.put("answerSummaryIsTruncated", true);
        dataset.put("answerSummaryNote", "answer_summary 是截断摘要（本库最长 501 字），不是完整回答；"
                + "句界可能被截断，句数只能当上界看。");
        root.put("dataset", dataset);

        Map<String, Object> d2 = new LinkedHashMap<>();
        d2.put("auditedRuns", audited);
        d2.put("answersWithUnsupportedSentences", answersWithUnsupported);
        d2.put("answerHitRate", answerHitRate);
        d2.put("answerHitRateDefinition", "有 unsupported 句的回答数 / 抽检回答数");
        d2.put("unsupportedSentencesTotal", unsupportedTotal);
        d2.put("averageUnsupportedPerAnswer", averageUnsupported);
        d2.put("medianUnsupportedPerAnswer", medianUnsupported);
        d2.put("maxUnsupportedPerAnswer", maxUnsupported);
        d2.put("keyConclusionSentencesTotal", keySentencesTotal);
        d2.put("sentenceHitRate", sentenceHitRate);
        d2.put("sentenceHitRateDefinition", "unsupported 句数 / 关键结论句总数（无证据时恒为 100%）");
        d2.put("unsupportedDistribution", distribution);
        d2.put("triggerReasons", Map.of(
                "withNumber", numberTriggered,
                "withDefinitionWord", definitionTriggered,
                "withCausalWord", causalTriggered,
                "definitionOnly", definitionOnly,
                "note", "一条句子可同时命中多个原因；definitionOnly = 只靠定义词、无数字无因果"));
        d2.put("groundedAnswers", groundedAnswers);
        d2.put("knowledgeIntentAnswers", knowledgeAnswers);
        d2.put("supplementWouldTriggerIfRuntimeEnabled", supplementTriggersIfRuntimeOn);
        d2.put("runtimeEnabled", runtimeEnabled);
        d2.put("supplementNote",
                "D2 补充说明的真实触发条件是 runtime.enabled=true 且「知识类 + 至少 1 个合法引用 + "
                        + "有 unsupported 句」。当前 agent_evidence=0 行 → 所有回答 grounded=false → "
                        + "即使是知识类也走 P0 整体提示，不追加逐句补充说明。");
        d2.put("topRunsByUnsupported", topUnsupported.stream().map(audit -> Map.of(
                "runId", audit.runId(),
                "unsupportedSentences", audit.unsupported(),
                "keyConclusionSentences", audit.keySentences())).toList());
        root.put("citationVerifierAudit", d2);

        Map<String, Object> e3b = new LinkedHashMap<>();
        e3b.put("auditedRuns", audited);
        e3b.put("leakedAnswers", leakedAnswers);
        e3b.put("leakedRate", audited == 0 ? 0.0d : leakedAnswers / (double) audited);
        e3b.put("answersNeedingUserVisibleNotice", noticeAnswers);
        e3b.put("answersWithKeywordOnly", keywordAnswers);
        e3b.put("noticeRuleCounts", noticeRuleCounts);
        e3b.put("keywordRuleCounts", keywordRuleCounts);
        e3b.put("rules", AnswerLeakChecker.ruleNames());
        e3b.put("limitation", "只覆盖回答侧（AnswerLeakChecker.check(answer, userQuestion)）；"
                + "笔记侧（EvidenceSanitizer 的 suspicious 标记）没有历史证据可抽，误报率未验证。");
        root.put("answerLeakAudit", e3b);

        Map<String, Object> evidenceSide = new LinkedHashMap<>();
        evidenceSide.put("agentEvidenceRows", evidenceRows);
        evidenceSide.put("suspiciousEvidenceRows", suspiciousEvidenceRows);
        evidenceSide.put("agentCitationRows", citationRows);
        evidenceSide.put("note", "笔记侧注入误报率未验证：agent_evidence 当前 0 行，没有历史证据可抽；"
                + "不用构造样本冒充真实抽检（构造样本的单测已存在，见 EvidenceSanitizerTest）。");
        root.put("evidenceSide", evidenceSide);

        root.put("limitations", List.of(
                "agent_evidence 为 0 行 → 历史回答没有证据台账，引用全部判非法（grounded 恒 false），"
                        + "所以测到的是「关键结论句规则的触发面」，不是补充说明的真实误报率。",
                "answer_summary 是截断摘要（最长 501 字），不是完整回答；句数与字数只能当上界。",
                "D2 补充说明只在 app.agent.runtime.enabled=true 时执行；当前取值为 "
                        + runtimeEnabled + "，默认路径下该能力不生效。",
                "笔记侧（EvidenceSanitizer / agent_evidence.suspicious）误报率未验证。",
                "未做人工语义复核：规则命中的句子是否真的是「结论句」需要人读原文；按隐私红线"
                        + "本报告不导出正文，因此只给数量与规则分组。"));

        Files.writeString(jsonPath, objectMapper.writerWithDefaultPrettyPrinter()
                .writeValueAsString(root), StandardCharsets.UTF_8);
        log.info("离线审计报告已落盘 json={}（只含数量/占比/规则名/runId）", jsonPath.toAbsolutePath());
        assertThat(jsonPath).exists();
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
    // 数据载体
    // ------------------------------------------------------------------

    /** 一条历史 run（只取审计需要的列；answer/question 只在内存里用）。 */
    private record RunRow(long id, String question, String answer, int toolCallCount) {
    }

    /** 单条回答的审计结果（全是计数/布尔/规则名，没有任何正文）。 */
    private record AnswerAudit(long runId, int toolCallCount, int evidenceRows, int keySentences,
                               int unsupported, boolean knowledgeIntent, boolean grounded,
                               int validCitations, int invalidCitations, int numberTriggered,
                               int definitionTriggered, int causalTriggered, int definitionOnly,
                               boolean leaked, List<String> noticeRules,
                               List<String> keywordRules) {
    }
}
