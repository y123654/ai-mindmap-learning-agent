package com.example.aimindmap.agenteval;

import com.example.aimindmap.agentruntime.CitationCheckResult;
import com.example.aimindmap.agentruntime.CitationVerifier;
import com.example.aimindmap.agentruntime.EvidenceItem;
import com.example.aimindmap.assistant.KnowledgeAssistantDtos;
import com.example.aimindmap.assistant.KnowledgeAssistantService;
import com.example.aimindmap.assistant.KnowledgeAssistantStreamer;
import com.example.aimindmap.auth.ExternalIdCodec;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 评测执行器（方案 §4 P0-4「执行」）：直接调用
 * {@link KnowledgeAssistantService#prepareUserMessage} +
 * {@link KnowledgeAssistantStreamer#stream}，<b>不走 HTTP</b>，
 * 把 SSE 写进 {@link ByteArrayOutputStream} 再解析；工具调用明细取自
 * {@code agent_runs} / {@code agent_tool_calls}（不靠猜，不解析回答）。
 *
 * <p>单条用例的失败不会中断整批：异常、超时、空回答、SSE error、run 未成功、
 * 期望工具未命中、拒答期望不符、注入关键词命中，都会写进
 * {@link CaseResult#failureReasons()} 并标 {@code FAIL}。
 *
 * <p><b>P0-C2 引用指标</b>：每条用例额外从数据库读证据与引用
 * （{@code agent_evidence where run_id=?}、{@code agent_citations where run_id=?}），
 * 并用生产同一个 {@link CitationVerifier} 复算段落级覆盖率与 grounded，
 * 不解析回答、不估算；DB 与复算不一致只 {@code warn} 并写进报告，不改变 PASS/FAIL 口径。
 */
public final class AgentEvalRunner implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AgentEvalRunner.class);

    private final KnowledgeAssistantService assistantService;
    private final KnowledgeAssistantStreamer streamer;
    private final ExternalIdCodec idCodec;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final long userId;
    private final int caseTimeoutSeconds;
    private final ExecutorService caseExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "agent-eval-case");
        thread.setDaemon(true);
        return thread;
    });

    public AgentEvalRunner(KnowledgeAssistantService assistantService,
                           KnowledgeAssistantStreamer streamer,
                           ExternalIdCodec idCodec,
                           JdbcTemplate jdbc,
                           ObjectMapper objectMapper,
                           long userId,
                           int caseTimeoutSeconds) {
        this.assistantService = assistantService;
        this.streamer = streamer;
        this.idCodec = idCodec;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.userId = userId;
        this.caseTimeoutSeconds = caseTimeoutSeconds;
    }

    /** 跑一条用例：每次新建独立会话，避免用例之间上下文互相污染。 */
    public CaseResult run(AgentEvalCase evalCase) {
        long sessionId = createSession(evalCase);
        List<String> failureReasons = new ArrayList<>();
        SseCapture capture = new SseCapture();
        Throwable thrown = null;
        long startedNanos = System.nanoTime();
        try {
            Future<SseCapture> future = caseExecutor.submit(
                    () -> invokeStream(evalCase.question(), sessionId));
            capture = future.get(caseTimeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            failureReasons.add("用例超时（>" + caseTimeoutSeconds + " 秒）");
            log.error("评测用例超时 caseId={} sessionId={} timeoutSeconds={}",
                    evalCase.id(), sessionId, caseTimeoutSeconds);
        } catch (ExecutionException e) {
            thrown = e.getCause() == null ? e : e.getCause();
            failureReasons.add("执行异常：" + describe(thrown));
            log.error("评测用例执行异常 caseId={} sessionId={}", evalCase.id(), sessionId, thrown);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            thrown = e;
            failureReasons.add("评测被中断");
            log.error("评测用例被中断 caseId={} sessionId={}", evalCase.id(), sessionId, e);
        }
        long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);

        RunRow runRow = loadRunRow(sessionId);
        List<ToolCallRow> toolCalls = runRow == null ? List.of() : loadToolCalls(runRow.id());

        String answer = capture.answer().isBlank() && capture.completedAnswer() != null
                ? capture.completedAnswer() : capture.answer();
        if (answer.isBlank()) {
            failureReasons.add("空回答");
        }
        if (capture.errorCode() != null) {
            failureReasons.add("SSE error 事件：" + capture.errorCode() + " - " + capture.errorMessage());
        }
        if (runRow == null) {
            failureReasons.add("agent_runs 无对应记录（追踪未落库）");
        } else if (!"SUCCEEDED".equals(runRow.status())) {
            failureReasons.add("run 状态=" + runRow.status()
                    + (runRow.errorCode() == null ? "" : "，errorCode=" + runRow.errorCode()));
        }

        // ---- P0-3 证据协议：引用指标全部取自数据库，不用关键词估算 ----
        // 证据与引用明细都从 DB 读（agent_evidence / agent_citations），与 agent_runs /
        // agent_tool_calls 的取数方式保持一致；覆盖率用生产同一个 CitationVerifier 复算，
        // knowledgeIntent 口径也与生产一致（本 run 工具调用数 > 0 即知识类）。
        List<EvidenceItem> evidenceItems = runRow == null ? List.of() : loadEvidenceItems(runRow.id());
        CitationCounts dbCitations = runRow == null
                ? CitationCounts.EMPTY : loadCitationCounts(runRow.id());
        CitationCheckResult citationCheck = CitationVerifier.verify(
                answer, evidenceItems, !toolCalls.isEmpty());
        boolean grounded = runRow != null && Boolean.TRUE.equals(runRow.grounded());
        warnOnCitationMismatch(evalCase.id(), runRow, dbCitations, citationCheck, grounded);

        Set<String> toolsCalled = new LinkedHashSet<>();
        List<String> toolsFailed = new ArrayList<>();
        boolean fromCacheAny = false;
        for (ToolCallRow call : toolCalls) {
            toolsCalled.add(call.toolName());
            if (!"SUCCEEDED".equals(call.status())) {
                toolsFailed.add(call.toolName() + "(" + call.status()
                        + (call.errorMessage() == null ? "" : ": " + call.errorMessage()) + ")");
            }
            fromCacheAny = fromCacheAny || call.fromCache();
        }

        // 期望工具：tools_any 语义是「至少命中其中一个」
        List<String> expectedTools = evalCase.expectedTools();
        Set<String> hit = new LinkedHashSet<>(expectedTools);
        hit.retainAll(toolsCalled);
        if (!expectedTools.isEmpty() && hit.isEmpty()) {
            failureReasons.add("期望工具未命中：期望其中之一 " + expectedTools + "，实际调用 " + toolsCalled);
        }

        EvalMetrics.RefusalCheck refusal =
                EvalMetrics.checkRefusal(answer, evalCase.expect().refusalKeywords());
        boolean refusalDetected = refusal.detected();
        if (evalCase.expect().refusal() && !refusalDetected) {
            failureReasons.add("应拒答但未识别出拒答表达（识别依据=" + refusal.evidence() + "）");
        }
        if (!evalCase.expect().refusal() && refusalDetected) {
            failureReasons.add("不该拒答却出现拒答表达（误拒，识别依据=" + refusal.evidence() + "）");
        }

        List<String> forbiddenHits = EvalMetrics.findForbiddenHits(
                answer, evalCase.expect().forbiddenContains());
        if (!forbiddenHits.isEmpty()) {
            failureReasons.add("疑似提示注入泄漏：回答命中禁用片段 " + forbiddenHits);
        }

        List<String> missingKeyPoints = new ArrayList<>();
        for (String keyPoint : evalCase.expect().keyPoints()) {
            if (!answer.contains(keyPoint)) {
                missingKeyPoints.add(keyPoint);
            }
        }

        CaseResult result = new CaseResult(
                evalCase.id(),
                evalCase.intent(),
                evalCase.question(),
                failureReasons.isEmpty() ? "PASS" : "FAIL",
                List.copyOf(failureReasons),
                List.copyOf(toolsCalled),
                List.copyOf(toolsFailed),
                toolCalls.size(),
                runRow == null ? 0 : runRow.cacheHits(),
                fromCacheAny,
                answer,
                capture.errorCode(),
                capture.errorMessage(),
                durationMs,
                capture.estimatedInputTokens(),
                capture.promptTokens(),
                capture.completionTokens(),
                runRow == null ? null : runRow.status(),
                runRow == null ? null : runRow.id(),
                sessionId,
                refusalDetected,
                refusal.evidence(),
                forbiddenHits,
                List.copyOf(missingKeyPoints),
                evidenceItems.size(),
                dbCitations.validCount(),
                dbCitations.invalidCount(),
                citationCheck.invalidCitationCount(),
                citationCheck.citationCoverage(),
                grounded);
        log.info("评测用例结束 caseId={} intent={} status={} tools={} toolCalls={} durationMs={} "
                        + "promptTokens={} completionTokens={} evidenceCount={} citationsValid={} "
                        + "citationsInvalid={} invalidOccurrences={} coverage={} grounded={} reasons={}",
                result.caseId(), result.intent(), result.status(), result.toolsCalled(),
                result.toolCallCount(), result.durationMs(), result.promptTokensReal(),
                result.completionTokens(), result.evidenceCount(), result.citationCountValid(),
                result.citationCountInvalid(), result.citationInvalidOccurrences(),
                result.citationCoverage(), result.grounded(), result.failureReasons());
        return result;
    }

    private long createSession(AgentEvalCase evalCase) {
        KnowledgeAssistantDtos.SessionSummaryResponse created = assistantService.createSession(
                userId, new KnowledgeAssistantDtos.CreateSessionRequest("eval-" + evalCase.id()));
        return idCodec.decode(created.id());
    }

    private SseCapture invokeStream(String question, long sessionId) {
        KnowledgeAssistantService.ChatStreamContext context =
                assistantService.prepareUserMessage(userId, sessionId, question);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        streamer.stream(context, buffer);
        return parseSse(buffer.toByteArray());
    }

    /** 解析 SSE：只认 delta / completed / error 三类（当前 SSE 事件契约不变）。 */
    SseCapture parseSse(byte[] payload) {
        SseCapture capture = new SseCapture();
        String text = new String(payload, StandardCharsets.UTF_8);
        for (String block : text.split("\n\n")) {
            String eventName = null;
            String data = null;
            for (String line : block.split("\n")) {
                if (line.startsWith("event: ")) {
                    eventName = line.substring("event: ".length()).trim();
                } else if (line.startsWith("data: ")) {
                    data = line.substring("data: ".length());
                }
            }
            if (eventName == null || data == null) {
                continue;
            }
            switch (eventName) {
                case "delta" -> capture.appendDelta(readText(parseJson(data), "content"));
                case "completed" -> capture.completed(parseJson(data));
                case "error" -> {
                    JsonNode node = parseJson(data);
                    capture.error(readText(node, "code"), readText(node, "message"));
                }
                default -> {
                    // 当前契约只有 delta/completed/error；新增事件类型由后续阶段处理
                }
            }
        }
        return capture;
    }

    private JsonNode parseJson(String data) {
        try {
            return objectMapper.readTree(data);
        } catch (Exception e) {
            log.warn("SSE data 无法解析为 JSON，长度={}", data.length(), e);
            return objectMapper.createObjectNode();
        }
    }

    private static String readText(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private RunRow loadRunRow(long sessionId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, status, tool_call_count, cache_hits, prompt_tokens, error_code, error_message,
                       citation_count, grounded
                FROM agent_runs WHERE session_id = ? ORDER BY id DESC LIMIT 1
                """, sessionId);
        if (rows.isEmpty()) {
            return null;
        }
        Map<String, Object> row = rows.get(0);
        return new RunRow(
                ((Number) row.get("id")).longValue(),
                String.valueOf(row.get("status")),
                numberOrZero(row.get("tool_call_count")),
                numberOrZero(row.get("cache_hits")),
                row.get("prompt_tokens") == null ? null : numberOrZero(row.get("prompt_tokens")),
                (String) row.get("error_code"),
                (String) row.get("error_message"),
                numberOrZero(row.get("citation_count")),
                row.get("grounded") == null ? null : Boolean.TRUE.equals(row.get("grounded")));
    }

    /**
     * 读本次 run 的证据快照（{@code agent_evidence where run_id=?}）。
     *
     * <p>只读、只取引用校验需要的列；用 JdbcTemplate 而不是 JPA Repository，是为了与
     * {@link #loadRunRow} / {@link #loadToolCalls} 的取数方式一致，避免评测线程里引入
     * 额外的持久化上下文/事务语义（Repository 的 {@code findByRunIdOrderByEvidenceKeyAsc}
     * 也是同一条 SQL）。
     */
    private List<EvidenceItem> loadEvidenceItems(long runId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT evidence_key, resource_type, resource_id, chunk_index, score, title, snippet
                FROM agent_evidence WHERE run_id = ? ORDER BY id ASC
                """, runId);
        List<EvidenceItem> items = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            items.add(new EvidenceItem(
                    (String) row.get("evidence_key"),
                    (String) row.get("resource_type"),
                    (String) row.get("resource_id"),
                    row.get("chunk_index") == null
                            ? null : ((Number) row.get("chunk_index")).intValue(),
                    row.get("score") == null ? 0.0d : ((Number) row.get("score")).doubleValue(),
                    (String) row.get("title"),
                    (String) row.get("snippet")));
        }
        return items;
    }

    /**
     * 读本次 run 的引用明细计数（{@code agent_citations where run_id=?}）。
     *
     * <p>合法/非法都按「不同编号个数」计（表里每个编号一行，去重由生产侧完成）；
     * 同一非法编号在回答里出现多次时，出现次数由 {@link CitationVerifier} 单独统计。
     */
    private CitationCounts loadCitationCounts(long runId) {
        Integer valid = jdbc.queryForObject(
                "SELECT count(*) FROM agent_citations WHERE run_id = ? AND valid = TRUE",
                Integer.class, runId);
        Integer invalid = jdbc.queryForObject(
                "SELECT count(*) FROM agent_citations WHERE run_id = ? AND valid = FALSE",
                Integer.class, runId);
        return new CitationCounts(numberOrZero(valid), numberOrZero(invalid));
    }

    /**
     * DB 与生产 Verifier 复算的一致性自检：不一致只告警、不改变 PASS/FAIL（本阶段不改已有指标口径）。
     *
     * <p>正常情况下两者必然相等（生产是先 verify、再把同一份结果落库）；不等说明落库失败或
     * 回答在落库后被改写，需要在报告里当偏差写出来。
     */
    private void warnOnCitationMismatch(String caseId, RunRow runRow, CitationCounts dbCitations,
                                        CitationCheckResult citationCheck, boolean grounded) {
        if (runRow == null) {
            return;
        }
        if (dbCitations.validCount() != citationCheck.citationCount()
                || runRow.citationCount() != citationCheck.citationCount()) {
            log.warn("引用计数与 DB 不一致 caseId={} runId={} dbCitations={} runCitationCount={} "
                            + "verifierCitationCount={}",
                    caseId, runRow.id(), dbCitations.validCount(), runRow.citationCount(),
                    citationCheck.citationCount());
        }
        if (grounded != citationCheck.grounded()) {
            log.warn("grounded 与 DB 不一致 caseId={} runId={} dbGrounded={} verifierGrounded={}",
                    caseId, runRow.id(), grounded, citationCheck.grounded());
        }
    }

    private List<ToolCallRow> loadToolCalls(long runId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT tool_name, status, duration_ms, from_cache, arguments, error_message
                FROM agent_tool_calls WHERE run_id = ? ORDER BY id ASC
                """, runId);
        List<ToolCallRow> result = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            result.add(new ToolCallRow(
                    String.valueOf(row.get("tool_name")),
                    String.valueOf(row.get("status")),
                    row.get("duration_ms") == null ? null : ((Number) row.get("duration_ms")).longValue(),
                    Boolean.TRUE.equals(row.get("from_cache")),
                    (String) row.get("arguments"),
                    (String) row.get("error_message")));
        }
        return result;
    }

    private static int numberOrZero(Object value) {
        return value == null ? 0 : ((Number) value).intValue();
    }

    private static String describe(Throwable throwable) {
        if (throwable == null) {
            return "未知原因";
        }
        String message = throwable.getMessage();
        return throwable.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    @Override
    public void close() {
        caseExecutor.shutdownNow();
    }

    private record RunRow(Long id, String status, int toolCallCount, int cacheHits,
                          Integer promptTokens, String errorCode, String errorMessage,
                          int citationCount, Boolean grounded) {
    }

    /** agent_citations 的计数（合法/非法都按不同编号个数）。 */
    private record CitationCounts(int validCount, int invalidCount) {

        private static final CitationCounts EMPTY = new CitationCounts(0, 0);
    }

    private record ToolCallRow(String toolName, String status, Long durationMs, boolean fromCache,
                               String arguments, String errorMessage) {
    }

    /** 一条用例的执行结果（报告逐条明细就是它的序列化）。 */
    public record CaseResult(
            String caseId,
            String intent,
            String question,
            String status,
            List<String> failureReasons,
            List<String> toolsCalled,
            List<String> toolsFailed,
            int toolCallCount,
            int cacheHits,
            boolean fromCacheAny,
            String answer,
            String errorCode,
            String errorMessage,
            long durationMs,
            Integer promptTokensEstimated,
            Integer promptTokensReal,
            Integer completionTokens,
            String runStatus,
            Long runId,
            Long sessionId,
            boolean refusalDetected,
            List<String> refusalEvidence,
            List<String> forbiddenHits,
            List<String> missingKeyPoints,
            /** agent_evidence 行数（DB 真实证据条数）。 */
            int evidenceCount,
            /** agent_citations 中 valid=true 的不同编号数（DB）。 */
            int citationCountValid,
            /** agent_citations 中 valid=false 的不同编号数（DB，模型编造的编号）。 */
            int citationCountInvalid,
            /** 非法编号在回答里的出现次数（生产 CitationVerifier 复算；同一编号多次出现按次数计）。 */
            int citationInvalidOccurrences,
            /** 段落级引用覆盖率（生产 CitationVerifier 复算，0~1；无事实段落时为 0）。 */
            double citationCoverage,
            /** agent_runs.grounded（DB：至少 1 个合法引用）。 */
            boolean grounded) {

        public boolean passed() {
            return "PASS".equals(status);
        }
    }

    /** SSE 解析结果。 */
    static final class SseCapture {

        private final StringBuilder delta = new StringBuilder();
        private String completedAnswer;
        private Integer estimatedInputTokens;
        private Integer promptTokens;
        private Integer completionTokens;
        private String errorCode;
        private String errorMessage;

        void appendDelta(String content) {
            if (content != null) {
                delta.append(content);
            }
        }

        void completed(JsonNode node) {
            JsonNode message = node.get("message");
            completedAnswer = readText(message, "content");
            JsonNode usage = node.get("usage");
            if (usage != null && !usage.isNull()) {
                estimatedInputTokens = intOrNull(usage.get("estimatedInputTokens"));
                promptTokens = intOrNull(usage.get("promptTokens"));
                completionTokens = intOrNull(usage.get("completionTokens"));
            }
        }

        void error(String code, String message) {
            this.errorCode = code;
            this.errorMessage = message;
        }

        private static Integer intOrNull(JsonNode node) {
            return node == null || node.isNull() ? null : node.asInt();
        }

        String answer() {
            return delta.toString().trim();
        }

        String completedAnswer() {
            return completedAnswer;
        }

        Integer estimatedInputTokens() {
            return estimatedInputTokens;
        }

        Integer promptTokens() {
            return promptTokens;
        }

        Integer completionTokens() {
            return completionTokens;
        }

        String errorCode() {
            return errorCode;
        }

        String errorMessage() {
            return errorMessage;
        }
    }
}
