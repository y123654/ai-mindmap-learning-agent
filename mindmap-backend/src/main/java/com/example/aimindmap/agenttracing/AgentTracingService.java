package com.example.aimindmap.agenttracing;

import com.example.aimindmap.agentruntime.AgentIntent;
import com.example.aimindmap.agentruntime.CitationCheckResult;
import com.example.aimindmap.agentruntime.EvidenceItem;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Agent 追踪落库服务（方案 §10.3 / §10.4）。
 * 每个方法独立事务、立即提交；只存摘要，不存完整提示词正文与密钥。
 */
@Service
public class AgentTracingService {

    private static final Logger log = LoggerFactory.getLogger(AgentTracingService.class);

    /**
     * P1-C1：run 内第一条步骤的序号。每个 run 的 {@code step_index} 从 1 开始递增，
     * 由 {@link #nextStepIndex(Long)} 依据当前最大序号 +1 得到（不再固定写 1，避免多步骤撞号）。
     */
    static final int FIRST_STEP_INDEX = 1;
    static final String RETRIEVE_FALLBACK_STEP_TYPE = "RETRIEVE_FALLBACK";

    /** P1-C1：{@code agent_runs.plan_json} 的硬截断长度（方案原文“截断到 8000 字符”）。 */
    static final int PLAN_JSON_MAX_CHARS = 8000;

    private final AgentRunRepository runRepository;
    private final AgentToolCallRepository toolCallRepository;
    private final AgentEvidenceRepository evidenceRepository;
    private final AgentCitationRepository citationRepository;
    private final AgentStepRepository stepRepository;
    private final AgentTracingProperties properties;
    private final AgentMetrics metrics;

    /** Spring 装配用：带 Micrometer 指标（P0-E1）与 agent_steps 仓储（P1-B2）。 */
    @Autowired
    public AgentTracingService(AgentRunRepository runRepository,
                               AgentToolCallRepository toolCallRepository,
                               AgentEvidenceRepository evidenceRepository,
                               AgentCitationRepository citationRepository,
                               AgentStepRepository stepRepository,
                               AgentTracingProperties properties,
                               AgentMetrics metrics) {
        this.runRepository = runRepository;
        this.toolCallRepository = toolCallRepository;
        this.evidenceRepository = evidenceRepository;
        this.citationRepository = citationRepository;
        this.stepRepository = stepRepository;
        this.properties = properties;
        this.metrics = metrics;
    }

    /**
     * 兼容旧调用方/单测的 6 参构造（P0-E1）：step 仓储传 null（旧构造不写 agent_steps）。
     * 生产装配永远走 7 参构造。
     */
    public AgentTracingService(AgentRunRepository runRepository,
                               AgentToolCallRepository toolCallRepository,
                               AgentEvidenceRepository evidenceRepository,
                               AgentCitationRepository citationRepository,
                               AgentTracingProperties properties,
                               AgentMetrics metrics) {
        this(runRepository, toolCallRepository, evidenceRepository, citationRepository,
                null, properties, metrics);
    }

    /**
     * 兼容旧调用方/单测的 5 参构造（P0-E1 之前就存在）：
     * 指标走空实现、step 仓储为 null，落库行为与旧版一致。
     */
    public AgentTracingService(AgentRunRepository runRepository,
                               AgentToolCallRepository toolCallRepository,
                               AgentEvidenceRepository evidenceRepository,
                               AgentCitationRepository citationRepository,
                               AgentTracingProperties properties) {
        this(runRepository, toolCallRepository, evidenceRepository, citationRepository,
                null, properties, AgentMetrics.noop());
    }

    /**
     * 旧签名（P1-B1 之前就存在）：不写意图，{@code agent_runs.intent} 保持 null。
     * 保留它是为了旧调用方与旧单测一行都不用改；内部委托 7 参重载。
     */
    @Transactional
    public AgentRunEntity startRun(Long userId, Long sessionId, String model,
                                   String userMessage, Integer promptChars, Integer promptTokens) {
        return startRun(userId, sessionId, model, userMessage, promptChars, promptTokens, null);
    }

    /**
     * P1-B1：带意图的 run 创建。{@code intent} 为 null（开关关 / 未接线 / 分类失败降级）时
     * {@code agent_runs.intent} 写 null，与旧行为一致；非 null 时写枚举名（如 {@code RETRIEVE}）。
     *
     * <p>枚举名是离线分析“路由准确率”的契约，见 {@link AgentIntent}。
     */
    @Transactional
    public AgentRunEntity startRun(Long userId, Long sessionId, String model,
                                   String userMessage, Integer promptChars, Integer promptTokens,
                                   AgentIntent intent) {
        AgentRunEntity run = new AgentRunEntity();
        run.setUserId(userId);
        run.setSessionId(sessionId);
        run.setStatus(AgentRunStatus.RUNNING);
        run.setModel(model);
        run.setUserMessage(summarize(userMessage, properties.getUserMessageSummaryChars()));
        run.setPromptChars(promptChars);
        run.setPromptTokens(promptTokens);
        run.setToolCallCount(0);
        run.setIntent(intent == null ? null : intent.name());
        run.setStartedAt(Instant.now());
        AgentRunEntity saved = runRepository.save(run);
        log.info("Agent run 开始 runId={} sessionId={} userId={} model={} promptChars={} "
                        + "promptTokens={} intent={}",
                saved.getId(), sessionId, userId, model, promptChars, promptTokens,
                saved.getIntent());
        return saved;
    }

    @Transactional
    public void succeedRun(Long runId, String answerSummary, int toolCallCount, int cacheHits) {
        AgentRunEntity run = requireRun(runId);
        run.setStatus(AgentRunStatus.SUCCEEDED);
        run.setAnswerSummary(summarize(answerSummary, properties.getAnswerSummaryChars()));
        run.setToolCallCount(toolCallCount);
        run.setCacheHits(cacheHits);
        run.setCompletedAt(Instant.now());
        runRepository.save(run);
        log.info("Agent run 成功 runId={} toolCallCount={} cacheHits={}", runId, toolCallCount, cacheHits);
    }

    @Transactional
    public void failRun(Long runId, String errorCode, String errorMessage,
                        int toolCallCount, int cacheHits) {
        AgentRunEntity run = requireRun(runId);
        run.setStatus(AgentRunStatus.FAILED);
        run.setErrorCode(truncate(errorCode, 64));
        run.setErrorMessage(summarize(errorMessage, properties.getErrorMessageChars()));
        run.setToolCallCount(toolCallCount);
        run.setCacheHits(cacheHits);
        run.setCompletedAt(Instant.now());
        runRepository.save(run);
        log.warn("Agent run 失败 runId={} errorCode={} toolCallCount={} cacheHits={}",
                runId, errorCode, toolCallCount, cacheHits);
    }

    /**
     * P0-E1：把平台回传的<b>真实</b> token 用量写回 run，并按 {@code app.agent.cost.*} 折算估算成本。
     *
     * <ul>
     *   <li>真实 promptTokens 存在（&gt;0）时写入 {@code prompt_tokens}，覆盖 startRun 写的估算值；
     *       真实值缺失时保留估算值不覆盖（估算口径与 {@code grounded} 均不受影响）。</li>
     *   <li>真实 completionTokens 存在（&gt;0）时写入 {@code completion_tokens}；否则保持 null。</li>
     *   <li>{@code estimated_cost} 只用真实 prompt+completion 计算；任一缺失即 null，
     *       不拿估算值凑成本（避免把"没有真实用量"记成 0 元）。</li>
     * </ul>
     *
     * <p>{@code intent} 列由 {@code startRun} 写入（P1-B1 起），本方法不触碰它，保持已有值。
     */
    @Transactional
    public void recordTokenUsage(Long runId, Integer promptTokens, Integer completionTokens) {
        AgentRunEntity run = requireRun(runId);
        Integer realPromptTokens = positiveOrNull(promptTokens);
        Integer realCompletionTokens = positiveOrNull(completionTokens);
        if (realPromptTokens != null) {
            run.setPromptTokens(realPromptTokens);
        }
        if (realCompletionTokens != null) {
            run.setCompletionTokens(realCompletionTokens);
        }
        BigDecimal estimatedCost = AgentCostCalculator.estimate(
                realPromptTokens, realCompletionTokens, properties.getCost());
        run.setEstimatedCost(estimatedCost);
        runRepository.save(run);
        log.info("Agent run token 与成本落库 runId={} realPromptTokens={} realCompletionTokens={} "
                        + "storedPromptTokens={} storedCompletionTokens={} estimatedCost={}",
                runId, realPromptTokens, realCompletionTokens, run.getPromptTokens(),
                run.getCompletionTokens(), estimatedCost);
    }

    private static Integer positiveOrNull(Integer value) {
        return value == null || value <= 0 ? null : value;
    }

    /**
     * P0-3：把本次回答的引用统计写回 {@code agent_runs}（citation_count / grounded）。
     * 只更新这两列，不触碰已有列语义。
     */
    @Transactional
    public void recordCitationSummary(Long runId, int citationCount, boolean grounded) {
        AgentRunEntity run = requireRun(runId);
        run.setCitationCount(citationCount);
        run.setGrounded(grounded);
        runRepository.save(run);
        log.info("Agent run 引用统计 runId={} citationCount={} grounded={}", runId, citationCount, grounded);
    }

    /**
     * P0-3：证据快照与引用明细一起落库（{@code agent_evidence} / {@code agent_citations}）。
     * 一个事务写完；调用方负责把异常降级成日志，不影响已经完成的流式回答。
     */
    @Transactional
    public void recordEvidenceAndCitations(Long runId, List<EvidenceItem> evidence,
                                           List<CitationCheckResult.Citation> citations) {
        List<EvidenceItem> evidenceItems = evidence == null ? List.of() : evidence;
        List<CitationCheckResult.Citation> citationItems = citations == null ? List.of() : citations;

        if (!evidenceItems.isEmpty()) {
            List<AgentEvidenceEntity> entities = new ArrayList<>(evidenceItems.size());
            for (EvidenceItem item : evidenceItems) {
                AgentEvidenceEntity entity = new AgentEvidenceEntity();
                entity.setRunId(runId);
                entity.setEvidenceKey(truncateForColumn(item.key(), 16, runId, "evidence_key"));
                entity.setResourceType(truncateForColumn(item.resourceType(), 32, runId, "resource_type"));
                entity.setResourceId(truncateForColumn(item.resourceId(), 64, runId, "resource_id"));
                entity.setChunkIndex(item.chunkIndex());
                entity.setScore((float) item.score());
                entity.setTitle(truncateForColumn(item.title(), 255, runId, "title"));
                entity.setSnippet(item.snippet());
                // P1-E3b：可疑标记来自 EvidenceSanitizer 的扫描结果（列早已存在，不新增迁移）
                entity.setSuspicious(item.suspicious());
                entity.setCreatedAt(Instant.now());
                entities.add(entity);
            }
            evidenceRepository.saveAll(entities);
        }

        if (!citationItems.isEmpty()) {
            List<AgentCitationEntity> entities = new ArrayList<>(citationItems.size());
            for (CitationCheckResult.Citation citation : citationItems) {
                AgentCitationEntity entity = new AgentCitationEntity();
                entity.setRunId(runId);
                entity.setEvidenceKey(truncateForColumn(citation.key(), 16, runId, "evidence_key"));
                entity.setValid(citation.valid());
                entity.setCreatedAt(Instant.now());
                entities.add(entity);
            }
            citationRepository.saveAll(entities);
        }

        long invalidCount = citationItems.stream().filter(citation -> !citation.valid()).count();
        log.info("Agent 证据与引用入库 runId={} evidenceCount={} citationCount={} invalidCitationCount={}",
                runId, evidenceItems.size(), citationItems.size(), invalidCount);
    }

    /**
     * P1-B2：补检轮次 +1（只加 1，不覆盖其它列）。重复调用会累加，调用方负责“每次 run 最多一次”。
     *
     * <p>与 {@code recordCitationSummary} 一样独立事务、立即提交；落库失败由调用方降级成日志。
     */
    @Transactional
    public void incrementRepairRound(Long runId) {
        AgentRunEntity run = requireRun(runId);
        int current = run.getRepairRound() == null ? 0 : run.getRepairRound();
        run.setRepairRound(current + 1);
        runRepository.save(run);
        log.info("Agent 补检轮次 +1 runId={} repairRound={}", runId, run.getRepairRound());
    }

    /**
     * P1-C1：写一条任意类型的 {@code agent_steps} 记录（PLAN / RETRIEVE / SYNTH …）。
     *
     * <ul>
     *   <li>{@code step_index} 由 {@link #nextStepIndex(Long)} 计算：run 内从 1 开始严格递增；</li>
     *   <li>{@code input_summary} / {@code output_summary} 只存摘要，硬截断到 1000 字符，不存完整提示词；</li>
     *   <li>本方法只写步骤，不改 {@code agent_runs} 的任何状态列。</li>
     * </ul>
     *
     * <p>旧构造（step 仓储为 null）不写库，只记 warn —— 生产装配永远有该仓储。
     */
    @Transactional
    public void recordStep(Long runId, String stepType, String name, String status,
                           String inputSummary, String outputSummary, String errorCode,
                           Long durationMs) {
        AgentStepEntity step = persistStep(runId, stepType, name, status, inputSummary,
                outputSummary, errorCode, durationMs);
        if (step == null) {
            log.warn("未装配 agent_steps 仓储，跳过步骤落库 runId={} stepType={} status={}",
                    runId, stepType, status);
            return;
        }
        log.info("Agent 步骤落库 runId={} stepIndex={} stepType={} status={} durationMs={}",
                runId, step.getStepIndex(), step.getStepType(), step.getStatus(), durationMs);
    }

    /**
     * P1-C1：把校验后的计划 JSON 写进 {@code agent_runs.plan_json}（硬截断到
     * {@value #PLAN_JSON_MAX_CHARS} 字符）。
     *
     * <p>{@code plan_json} <b>不对外暴露</b>：{@code GET /api/agent/runs*} 的 DTO 不变。
     * 只记录长度与降级标记，不把计划正文（可能含用户原话）打进日志。
     */
    @Transactional
    public void recordPlanJson(Long runId, String planJson) {
        if (planJson == null || planJson.isBlank()) {
            log.warn("计划 JSON 为空，跳过落库 runId={}", runId);
            return;
        }
        AgentRunEntity run = requireRun(runId);
        String stored = truncateForColumn(planJson, PLAN_JSON_MAX_CHARS, runId, "plan_json");
        run.setPlanJson(stored);
        runRepository.save(run);
        log.info("Agent 计划落库 runId={} planJsonLength={} truncated={}", runId, stored.length(),
                stored.length() != planJson.length());
    }

    /**
     * P1-B2：写一条 {@code agent_steps} 记录用于 {@code RETRIEVE_FALLBACK}。
     *
     * <ul>
     *   <li>P1-C1 起 {@code step_index} 不再固定为 1，而是接着本 run 已有序号往后排
     *       （PLAN / RETRIEVE / SYNTH 之后再补检也不会撞号）；</li>
     *   <li>{@code input_summary} 只存本次补检的查询，硬截断到 1000 字符以内（不存完整提示词）；</li>
     *   <li>{@code output_summary} 存命中条数与证据编号；</li>
     *   <li>{@code duration_ms} 存确定性检索耗时。</li>
     * </ul>
     *
     * <p>旧构造（step 仓储为 null）不写库，只记 warn —— 生产装配永远有该仓储。
     */
    @Transactional
    public void recordRetrieveFallbackStep(Long runId, String status, String query,
                                           String outputSummary, String errorCode, long durationMs) {
        AgentStepEntity step = persistStep(runId, RETRIEVE_FALLBACK_STEP_TYPE, "确定性检索补检",
                status, query, outputSummary, errorCode, durationMs);
        if (step == null) {
            log.warn("未装配 agent_steps 仓储，跳过 RETRIEVE_FALLBACK 落库 runId={} status={}",
                    runId, status);
            return;
        }
        log.info("Agent 补检步骤落库 runId={} stepIndex={} stepType={} status={} durationMs={} "
                        + "addedEvidence={}",
                runId, step.getStepIndex(), step.getStepType(), status, durationMs, outputSummary);
    }

    /**
     * 写步骤的公共实现：序号 = 当前 run 最大 step_index + 1（无步骤时从 1 开始）。
     *
     * @return 落库后的实体；step 仓储未装配时返回 null
     */
    private AgentStepEntity persistStep(Long runId, String stepType, String name, String status,
                                        String inputSummary, String outputSummary,
                                        String errorCode, Long durationMs) {
        if (stepRepository == null) {
            return null;
        }
        AgentStepEntity step = new AgentStepEntity();
        step.setRunId(runId);
        step.setStepIndex(nextStepIndex(runId));
        step.setStepType(truncate(stepType, 24));
        step.setName(truncate(name, 128));
        step.setStatus(truncate(status, 16));
        step.setInputSummary(truncateForColumn(inputSummary, 1000, runId, "input_summary"));
        step.setOutputSummary(truncateForColumn(outputSummary, 1000, runId, "output_summary"));
        step.setErrorCode(truncate(errorCode, 64));
        step.setDurationMs(durationMs);
        step.setCreatedAt(Instant.now());
        stepRepository.save(step);
        return step;
    }

    /** run 内下一个步骤序号：当前最大 {@code step_index} + 1，没有步骤时从 1 开始。 */
    private int nextStepIndex(Long runId) {
        Integer max = stepRepository.findMaxStepIndexByRunId(runId);
        return max == null ? FIRST_STEP_INDEX : max + 1;
    }

    @Transactional
    public Long startToolCall(Long runId, Long userId, String toolName, String arguments) {
        AgentToolCallEntity toolCall = new AgentToolCallEntity();
        toolCall.setRunId(runId);
        toolCall.setUserId(userId);
        toolCall.setToolName(toolName);
        toolCall.setArguments(summarize(arguments, properties.getArgumentSummaryChars()));
        toolCall.setStatus(AgentToolCallStatus.RUNNING);
        toolCall.setCreatedAt(Instant.now());
        return toolCallRepository.save(toolCall).getId();
    }

    /**
     * 缓存命中直接落一条 SUCCEEDED 记录（from_cache=true、duration_ms=0）。
     * 只存参数/结果摘要，不存正文；不经 startToolCall 两阶段更新。
     */
    @Transactional
    public void recordCacheHitToolCall(Long runId, Long userId, String toolName,
                                       String arguments, String cachedResult) {
        AgentToolCallEntity toolCall = new AgentToolCallEntity();
        toolCall.setRunId(runId);
        toolCall.setUserId(userId);
        toolCall.setToolName(toolName);
        toolCall.setArguments(summarize(arguments, properties.getArgumentSummaryChars()));
        toolCall.setResultSummary(summarize(cachedResult, properties.getResultSummaryChars()));
        toolCall.setStatus(AgentToolCallStatus.SUCCEEDED);
        toolCall.setDurationMs(0L);
        toolCall.setFromCache(true);
        toolCall.setCreatedAt(Instant.now());
        toolCallRepository.save(toolCall);
        // P0-E1：缓存命中同样是一次"成功"的工具调用（tool 维度成功率不受缓存影响）
        metrics.recordToolCall(toolName, true);
    }

    @Transactional
    public void succeedToolCall(Long toolCallId, String resultSummary, long durationMs) {
        AgentToolCallEntity toolCall = requireToolCall(toolCallId);
        toolCall.setStatus(AgentToolCallStatus.SUCCEEDED);
        toolCall.setResultSummary(summarize(resultSummary, properties.getResultSummaryChars()));
        toolCall.setDurationMs(durationMs);
        toolCallRepository.save(toolCall);
        // P0-E1：agent.tool.calls{tool,phase=success}，每个工具调用只记一次
        metrics.recordToolCall(toolCall.getToolName(), true);
    }

    @Transactional
    public void failToolCall(Long toolCallId, String errorMessage, long durationMs) {
        AgentToolCallEntity toolCall = requireToolCall(toolCallId);
        toolCall.setStatus(AgentToolCallStatus.FAILED);
        toolCall.setErrorMessage(summarize(errorMessage, properties.getErrorMessageChars()));
        toolCall.setDurationMs(durationMs);
        toolCallRepository.save(toolCall);
        // P0-E1：agent.tool.calls{tool,phase=failed}，每个工具调用只记一次
        metrics.recordToolCall(toolCall.getToolName(), false);
    }

    private AgentRunEntity requireRun(Long runId) {
        return runRepository.findById(runId)
                .orElseThrow(() -> new IllegalStateException("Agent run 记录不存在：" + runId));
    }

    private AgentToolCallEntity requireToolCall(Long toolCallId) {
        return toolCallRepository.findById(toolCallId)
                .orElseThrow(() -> new IllegalStateException("Agent 工具调用记录不存在：" + toolCallId));
    }

    /** 写入 VARCHAR 列前的硬截断：返回长度不超过 maxChars；发生截断时打 warn（带 runId/列名）。 */
    private static String truncateForColumn(String text, int maxChars, Long runId, String column) {
        if (text == null || text.length() <= maxChars) {
            return text;
        }
        log.warn("证据字段超长截断 runId={} column={} originalLength={} maxLength={}",
                runId, column, text.length(), maxChars);
        return text.substring(0, maxChars - 1) + "…";
    }

    private static String summarize(String text, int maxChars) {
        if (text == null) {
            return null;
        }
        String normalized = text.replaceAll("\\s+", " ").trim();
        return truncate(normalized, maxChars);
    }

    private static String truncate(String text, int maxChars) {
        if (text == null || text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars) + "…";
    }
}
