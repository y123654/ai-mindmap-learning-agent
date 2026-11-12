package com.example.aimindmap.agenttracing;

import com.example.aimindmap.auth.ExternalIdCodec;
import com.example.aimindmap.common.error.ApiException;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * P0-E1：Agent run 观测查询（方案 §4 P0-5 / §6 接口表）。
 *
 * <p>两条硬约束：</p>
 * <ol>
 *   <li><b>归属校验</b>：run 的 {@code user_id} 必须等于当前登录用户，否则按 404 处理
 *       （与"记录不存在"不可区分，不泄露别人的 run 是否存在）。</li>
 *   <li><b>列表只返回本人</b>：查询条件自带 {@code userId}，别人的 sessionId 只会得到空数组。</li>
 * </ol>
 *
 * <p>ID 一律走 {@link ExternalIdCodec}：非法/伪造的外部 ID 直接 404（与其它接口一致）。
 * 本类只读，不写任何表。</p>
 */
@Service
public class AgentRunQueryService {

    /** 列表默认条数。 */
    static final int DEFAULT_LIMIT = 20;
    /** 列表条数上限（超过按上限返回，不报错）。 */
    static final int MAX_LIMIT = 50;

    private static final Logger log = LoggerFactory.getLogger(AgentRunQueryService.class);

    private final AgentRunRepository runRepository;
    private final AgentToolCallRepository toolCallRepository;
    private final AgentEvidenceRepository evidenceRepository;
    private final AgentCitationRepository citationRepository;
    private final AgentStepRepository stepRepository;
    private final ExternalIdCodec idCodec;

    /** Spring 装配用：带 agent_steps 仓储（P1-B2）。 */
    @Autowired
    public AgentRunQueryService(AgentRunRepository runRepository,
                                AgentToolCallRepository toolCallRepository,
                                AgentEvidenceRepository evidenceRepository,
                                AgentCitationRepository citationRepository,
                                AgentStepRepository stepRepository,
                                ExternalIdCodec idCodec) {
        this.runRepository = runRepository;
        this.toolCallRepository = toolCallRepository;
        this.evidenceRepository = evidenceRepository;
        this.citationRepository = citationRepository;
        this.stepRepository = stepRepository;
        this.idCodec = idCodec;
    }

    /**
     * 兼容旧调用方/单测的 5 参构造（P0-E1）：step 仓储为 null，steps 退化为空数组。
     * 生产装配永远走 6 参构造。
     */
    public AgentRunQueryService(AgentRunRepository runRepository,
                                AgentToolCallRepository toolCallRepository,
                                AgentEvidenceRepository evidenceRepository,
                                AgentCitationRepository citationRepository,
                                ExternalIdCodec idCodec) {
        this(runRepository, toolCallRepository, evidenceRepository, citationRepository, null, idCodec);
    }

    /**
     * 单 run 完整链路：run + agent_steps + agent_evidence + agent_citations + agent_tool_calls。
     *
     * @param userId        当前登录用户（来自 {@code @AuthenticationPrincipal}）
     * @param externalRunId 对外混淆的 runId
     */
    @Transactional(readOnly = true)
    public AgentRunDtos.RunDetailResponse getRun(Long userId, String externalRunId) {
        long runId = idCodec.decode(externalRunId);
        AgentRunEntity run = runRepository.findById(runId)
                .orElseThrow(() -> ApiException.notFound("运行记录不存在"));
        requireOwnership(run, userId, "读取");

        List<AgentRunDtos.EvidenceResponse> evidence =
                evidenceRepository.findByRunIdOrderByEvidenceKeyAsc(runId).stream()
                        .map(AgentRunDtos.EvidenceResponse::from)
                        .toList();
        List<AgentRunDtos.CitationResponse> citations =
                citationRepository.findByRunIdOrderByEvidenceKeyAsc(runId).stream()
                        .map(AgentRunDtos.CitationResponse::from)
                        .toList();
        List<AgentRunDtos.ToolCallResponse> toolCalls =
                toolCallRepository.findByRunIdOrderByIdAsc(runId).stream()
                        .map(AgentRunDtos.ToolCallResponse::from)
                        .toList();
        // P1-B2：agent_steps 从空数组改为真实读取，按 step_index 升序（再在内存排序兜底）。
        List<AgentRunDtos.RunStepResponse> steps = stepRepository == null
                ? List.of()
                : stepRepository.findByRunIdOrderByStepIndexAsc(runId).stream()
                        .map(AgentRunDtos.RunStepResponse::from)
                        .sorted(Comparator.comparingInt(AgentRunDtos.RunStepResponse::stepIndex))
                        .toList();

        log.debug("Agent run 详情查询 userId={} runId={} stepCount={} evidenceCount={} "
                        + "citationCount={} toolCallCount={}",
                userId, runId, steps.size(), evidence.size(), citations.size(), toolCalls.size());
        return new AgentRunDtos.RunDetailResponse(
                AgentRunDtos.RunResponse.from(run, idCodec), steps, evidence, citations, toolCalls);
    }

    /**
     * 会话内 run 列表（当前用户 + 指定 session，按时间倒序）。
     *
     * @param userId          当前登录用户
     * @param externalSessionId 对外混淆的 sessionId（空 → 400）
     * @param limit           条数（null/≤0 → 默认 20，超过 50 → 50）
     */
    @Transactional(readOnly = true)
    public List<AgentRunDtos.RunSummaryResponse> listRuns(Long userId, String externalSessionId,
                                                          Integer limit) {
        if (externalSessionId == null || externalSessionId.isBlank()) {
            log.warn("Agent run 列表缺少 sessionId userId={}", userId);
            throw ApiException.badRequest("AGENT_SESSION_REQUIRED", "缺少 sessionId");
        }
        long sessionId = idCodec.decode(externalSessionId);
        int size = normalizeLimit(limit);
        List<AgentRunDtos.RunSummaryResponse> runs = runRepository
                .findByUserIdAndSessionIdOrderByStartedAtDescIdDesc(
                        userId, sessionId, PageRequest.of(0, size))
                .stream()
                .map(entity -> AgentRunDtos.RunSummaryResponse.from(entity, idCodec))
                .toList();
        log.debug("Agent run 列表查询 userId={} sessionId={} limit={} returned={}",
                userId, sessionId, size, runs.size());
        return runs;
    }

    /** limit 归一：缺省/非正数 → 默认 20；超过上限 → 截到 50（不报错，避免前端传大值直接 400）。 */
    static int normalizeLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    private static void requireOwnership(AgentRunEntity run, Long userId, String action) {
        if (!Objects.equals(run.getUserId(), userId)) {
            // 只打 id，不打用户输入内容；越权与不存在对外不可区分
            log.warn("拒绝越权{} agent run runId={} ownerUserId={} currentUserId={}",
                    action, run.getId(), run.getUserId(), userId);
            throw ApiException.notFound("运行记录不存在");
        }
    }
}
