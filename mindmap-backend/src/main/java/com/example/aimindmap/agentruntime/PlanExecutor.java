package com.example.aimindmap.agentruntime;

import com.example.aimindmap.agenttools.AgentToolFailureException;
import com.example.aimindmap.agenttracing.AgentTracingProperties;
import com.example.aimindmap.vectorindex.VectorIndexExternalException;
import com.example.aimindmap.vectorindex.VectorSearchService;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * P1-C1：Plan-and-Execute 的 Executor（方案 §P1-2 第 491~495 行）。
 *
 * <p>每个子问题<b>并发</b>调一次 {@link VectorSearchService#search}（虚拟线程执行器，每次执行新建、
 * 用完即关，不占公共线程池），每个子问题最多取 {@value #MAX_HITS_PER_SUB_QUESTION} 条，
 * 全部登记到本 run 的 {@link EvidenceLedger}（复用现有 {@code register}，编号仍是 E1、E2…）。
 *
 * <p><b>总预算 15 秒硬编码在 {@link #SEARCH_BUDGET_MS}</b>：方案 §P1-2 只给了 Executor 总预算 15s，
 * 没有给单子问题超时；实际生效预算 = min(15s, 本轮剩余预算)，单子问题最多等到剩余预算耗尽。
 * 与 Planner 的 20s 一样<b>不进配置</b>（可配置化留 P1-D 统一治理）。
 *
 * <p><b>失败隔离</b>：单个子问题抛异常/超时只记一条日志 + 一条 FAILED 结果，其它子问题照常；
 * 本方法<b>不向外抛异常</b>（并发执行器本身创建失败这种极端情况除外，由调用方兜底）。
 *
 * <p><b>证据上限</b>：本类不另设上限，完全复用 {@link EvidenceLedger} 的
 * {@link EvidenceLedger#DEFAULT_MAX_ITEMS}（12 条，超出丢弃最低分）；因此并发登记也不会突破 12。
 *
 * <p><b>无命中的子问题</b>：status={@code FAILED}、errorCode={@code PLAN_RETRIEVE_NO_EVIDENCE}，
 * 供 {@code agent_steps} 直接反映；按所有者 2026-10-27 的决定已缩减为「迷你 Plan」，
 * <b>不做</b>充分性判定 / 查询改写 / 补充轮（原 P1-C2 已取消），因此这里只记录、不再规划第二轮。
 */
@Component
public class PlanExecutor {

    private static final Logger log = LoggerFactory.getLogger(PlanExecutor.class);

    /** Executor 总预算（毫秒）：15 秒，硬编码、不进配置（理由见类注释）。 */
    static final long SEARCH_BUDGET_MS = 15_000L;

    /** 每个子问题最多登记的证据条数（方案原文 5 条）。 */
    static final int MAX_HITS_PER_SUB_QUESTION = 5;

    /** 无命中 / 超时 / 预算耗尽的状态与错误码（落 {@code agent_steps}）。 */
    public static final String STATUS_SUCCEED = "SUCCEED";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_SKIPPED = "SKIPPED";
    public static final String ERROR_NO_EVIDENCE = "PLAN_RETRIEVE_NO_EVIDENCE";
    public static final String ERROR_TIMEOUT = "PLAN_RETRIEVE_TIMEOUT";
    public static final String ERROR_BUDGET_EXHAUSTED = "PLAN_BUDGET_EXHAUSTED";

    private final VectorSearchService vectorSearchService;
    /**
     * P1-E3b：计划路径的证据同样要过 {@code EvidenceSanitizer}（合成阶段会把它们包成
     * {@code <evidence>} 块直接交给模型）。为 null 表示“未接线”（旧单测），按防护开处理。
     */
    private final AgentTracingProperties properties;

    /** 旧装配（只注入检索服务）：等价于“防护开”，与新行为一致，保留给既有单测。 */
    public PlanExecutor(VectorSearchService vectorSearchService) {
        this(vectorSearchService, null);
    }

    /** 生产装配：读 {@code app.agent.security.evidence-guard-enabled}。 */
    @Autowired
    public PlanExecutor(VectorSearchService vectorSearchService, AgentTracingProperties properties) {
        this.vectorSearchService = vectorSearchService;
        this.properties = properties;
    }

    /**
     * 并发执行计划里的全部子问题检索。
     *
     * @param runId              本 run id（只用于日志）
     * @param userId             当前用户（检索 filter 必带）
     * @param plan               已校验的计划
     * @param ledger             本 run 的证据台账（并发登记，内部已同步）
     * @param remainingBudgetMs  本轮剩余预算（毫秒），由 Streamer 用共享 deadline 算出
     * @return 每个子问题的结果 + 汇总；<b>不抛异常</b>
     */
    public Outcome execute(Long runId, Long userId, AgentPlan plan, EvidenceLedger ledger,
                           long remainingBudgetMs) {
        long startedNanos = System.nanoTime();
        List<AgentPlan.SubQuestion> subQuestions = plan == null ? List.of() : plan.subQuestions();
        if (subQuestions.isEmpty()) {
            return new Outcome(List.of(), true, false, elapsedMillis(startedNanos));
        }
        long budgetMs = Math.min(SEARCH_BUDGET_MS, Math.max(0L, remainingBudgetMs));
        if (budgetMs <= 0L) {
            // 停止条件 3：总预算耗尽 —— 不再检索，但调用方仍会用已有证据（可能为 0 条）合成
            log.warn("计划检索跳过：预算已耗尽 runId={} subQuestions={} remainingBudgetMs={}",
                    runId, subQuestions.size(), remainingBudgetMs);
            return new Outcome(skipped(subQuestions), true, true, elapsedMillis(startedNanos));
        }

        List<Future<SubQuestionResult>> futures = new ArrayList<>(subQuestions.size());
        List<SubQuestionResult> results = new ArrayList<>(subQuestions.size());
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            for (AgentPlan.SubQuestion subQuestion : subQuestions) {
                futures.add(executor.submit(() -> searchOne(runId, userId, subQuestion, ledger)));
            }
            for (int i = 0; i < futures.size(); i++) {
                AgentPlan.SubQuestion subQuestion = subQuestions.get(i);
                long remaining = budgetMs - elapsedMillis(startedNanos);
                try {
                    results.add(futures.get(i).get(Math.max(0L, remaining), TimeUnit.MILLISECONDS));
                } catch (TimeoutException e) {
                    futures.get(i).cancel(true);
                    log.warn("子问题检索超时 runId={} subId={} queryLength={} 预算剩余={}ms",
                            runId, subQuestion.id(), length(subQuestion.query()), remaining);
                    results.add(new SubQuestionResult(subQuestion.id(), subQuestion.query(),
                            STATUS_FAILED, 0, List.of(), ERROR_TIMEOUT,
                            elapsedMillis(startedNanos)));
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause() == null ? e : e.getCause();
                    log.warn("子问题检索失败 runId={} subId={} queryLength={} cause={}",
                            runId, subQuestion.id(), length(subQuestion.query()),
                            cause.getClass().getSimpleName(), cause);
                    results.add(new SubQuestionResult(subQuestion.id(), subQuestion.query(),
                            STATUS_FAILED, 0, List.of(), errorCodeOf(cause),
                            elapsedMillis(startedNanos)));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("子问题检索被中断 runId={} subId={}", runId, subQuestion.id(), e);
                    results.add(new SubQuestionResult(subQuestion.id(), subQuestion.query(),
                            STATUS_FAILED, 0, List.of(), ERROR_TIMEOUT,
                            elapsedMillis(startedNanos)));
                    break;
                }
            }
        } finally {
            executor.shutdownNow();
        }

        // 证据台账可能因 12 条上限丢弃低分条目：步骤里只保留仍存活（可被落库/引用）的编号
        Set<String> aliveKeys = aliveEvidenceKeys(ledger);
        List<SubQuestionResult> reconciled = new ArrayList<>(results.size());
        int totalHits = 0;
        int succeeded = 0;
        for (SubQuestionResult result : results) {
            List<String> keys = result.evidenceKeys().stream()
                    .filter(aliveKeys::contains)
                    .toList();
            reconciled.add(new SubQuestionResult(result.subQuestionId(), result.query(),
                    result.status(), result.hitCount(), keys, result.errorCode(),
                    result.durationMs()));
            totalHits += result.hitCount();
            if (STATUS_SUCCEED.equals(result.status())) {
                succeeded++;
            }
        }
        boolean allEmpty = totalHits == 0;
        log.info("计划检索完成 runId={} subQuestions={} succeeded={} totalHits={} evidenceCount={} "
                        + "elapsedMs={}",
                runId, reconciled.size(), succeeded, totalHits, ledger == null ? 0 : ledger.size(),
                elapsedMillis(startedNanos));
        return new Outcome(List.copyOf(reconciled), allEmpty, false, elapsedMillis(startedNanos));
    }

    /** 单个子问题：检索 → 取前 5 条登记台账；异常只降级成 FAILED 结果。 */
    private SubQuestionResult searchOne(Long runId, Long userId, AgentPlan.SubQuestion subQuestion,
                                        EvidenceLedger ledger) {
        long startedNanos = System.nanoTime();
        VectorSearchService.SearchOutcome outcome;
        try {
            outcome = vectorSearchService.search(userId, subQuestion.query(),
                    subQuestion.vectorResourceTypes());
        } catch (RuntimeException e) {
            log.warn("子问题检索异常 runId={} subId={} queryLength={} error={}",
                    runId, subQuestion.id(), length(subQuestion.query()), e.toString(), e);
            return new SubQuestionResult(subQuestion.id(), subQuestion.query(), STATUS_FAILED,
                    0, List.of(), errorCodeOf(e), elapsedMillis(startedNanos));
        }
        List<VectorSearchService.VectorHit> hits = outcome == null || outcome.hits() == null
                ? List.of() : outcome.hits();
        int limited = Math.min(hits.size(), MAX_HITS_PER_SUB_QUESTION);
        List<String> evidenceKeys = new ArrayList<>(limited);
        for (int i = 0; i < limited; i++) {
            VectorSearchService.VectorHit hit = hits.get(i);
            if (hit == null) {
                continue;
            }
            // P1-E3b：与工具输出用同一套扫描；命中只“标记 + 日志”，不丢资料、不改检索结果
            boolean guard = properties == null || properties.isEvidenceGuardEnabled();
            EvidenceSanitizer.ScanResult scan = guard
                    ? EvidenceSanitizer.scan(hit.title(), hit.content())
                    : EvidenceSanitizer.ScanResult.clean();
            String key = ledger == null ? null : ledger.register(hit.sourceType(), hit.sourceId(),
                    hit.chunkIndex(), hit.score(), hit.title(), hit.content(), scan.suspicious());
            if (scan.suspicious()) {
                // 只打 runId / 证据号 / 资料坐标 / 规则名，绝不打资料正文
                log.warn("计划检索证据含疑似指令文本 runId={} evidenceKey={} resourceType={} "
                                + "resourceId={} chunkIndex={} rules={}",
                        runId, key, hit.sourceType(), hit.sourceId(), hit.chunkIndex(),
                        String.join(",", scan.ruleNames()));
            }
            if (key != null) {
                evidenceKeys.add(key);
            }
        }
        long durationMs = elapsedMillis(startedNanos);
        if (limited == 0 || evidenceKeys.isEmpty()) {
            log.info("子问题检索无命中 runId={} subId={} queryLength={} hits={} durationMs={}",
                    runId, subQuestion.id(), length(subQuestion.query()), hits.size(), durationMs);
            return new SubQuestionResult(subQuestion.id(), subQuestion.query(), STATUS_FAILED,
                    limited, List.copyOf(evidenceKeys), ERROR_NO_EVIDENCE, durationMs);
        }
        log.debug("子问题检索命中 runId={} subId={} hits={} registered={} durationMs={}",
                runId, subQuestion.id(), limited, evidenceKeys.size(), durationMs);
        return new SubQuestionResult(subQuestion.id(), subQuestion.query(), STATUS_SUCCEED,
                limited, List.copyOf(evidenceKeys), null, durationMs);
    }

    private static List<SubQuestionResult> skipped(List<AgentPlan.SubQuestion> subQuestions) {
        List<SubQuestionResult> skipped = new ArrayList<>(subQuestions.size());
        for (AgentPlan.SubQuestion subQuestion : subQuestions) {
            skipped.add(new SubQuestionResult(subQuestion.id(), subQuestion.query(), STATUS_SKIPPED,
                    0, List.of(), ERROR_BUDGET_EXHAUSTED, 0L));
        }
        return List.copyOf(skipped);
    }

    private static Set<String> aliveEvidenceKeys(EvidenceLedger ledger) {
        if (ledger == null) {
            return Set.of();
        }
        Set<String> keys = new LinkedHashSet<>();
        for (EvidenceItem item : ledger.items()) {
            if (item.key() != null) {
                keys.add(item.key());
            }
        }
        return keys;
    }

    private static String errorCodeOf(Throwable throwable) {
        if (throwable instanceof AgentToolFailureException toolFailure) {
            return toolFailure.code();
        }
        if (throwable instanceof VectorIndexExternalException) {
            return "AGENT_RETRIEVAL_FAILED";
        }
        return throwable == null ? "PLAN_RETRIEVE_FAILED" : throwable.getClass().getSimpleName();
    }

    private static int length(String text) {
        return text == null ? 0 : text.length();
    }

    private static long elapsedMillis(long startedNanos) {
        return Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
    }

    /**
     * 单次执行结果。
     *
     * @param subResults       每个子问题一行（顺序与计划一致）
     * @param allEmpty         所有子问题都没有命中（按缩减决定不做充分性/补充轮，仅打日志 + 落步骤）
     * @param budgetExhausted  是否因预算耗尽而整段跳过
     * @param elapsedMs        本阶段耗时
     */
    public record Outcome(List<SubQuestionResult> subResults, boolean allEmpty,
                          boolean budgetExhausted, long elapsedMs) {
    }

    /**
     * 单子问题结果（Streamer 据此写一条 {@code agent_steps.step_type=RETRIEVE}）。
     *
     * @param subQuestionId 子问题 id（q1/q2…）
     * @param query         实际检索用的 query（落 input_summary）
     * @param status        SUCCEED / FAILED / SKIPPED
     * @param hitCount      命中并尝试登记的条数（≤5）
     * @param evidenceKeys  仍存活于台账的证据编号（去重/上限淘汰后）
     * @param errorCode     失败原因；成功为 null
     * @param durationMs    本子问题耗时
     */
    public record SubQuestionResult(String subQuestionId, String query, String status, int hitCount,
                                    List<String> evidenceKeys, String errorCode, long durationMs) {

        public SubQuestionResult {
            evidenceKeys = evidenceKeys == null ? List.of() : List.copyOf(evidenceKeys);
        }
    }
}
