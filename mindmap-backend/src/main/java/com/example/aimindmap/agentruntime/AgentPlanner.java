package com.example.aimindmap.agentruntime;

import com.example.aimindmap.ai.AiGateway;
import com.example.aimindmap.ai.AiJsonRequest;
import com.example.aimindmap.ai.AiJsonResult;
import com.example.aimindmap.ai.AiUsage;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * P1-C1：Plan-and-Execute 的 Planner（方案 §P1-2 第 474~489 行）。
 *
 * <p>用 {@link AiGateway#completeJsonForResult} 让模型输出一份 JSON 计划，做<b>严格校验</b>后
 * 产出 {@link AgentPlan}；<b>任何</b>非法/异常/超时/预算不足都降级成单查询计划（原始用户消息当
 * query、只检索 1 次），绝不向外抛异常——计划失败不应该让一次问答失败。
 *
 * <p><b>Planner 自己的上限定为 20 秒</b>，硬编码在 {@link #PLANNER_TIMEOUT_MS}：
 * 方案 §P1-2 没有给 Planner 单独预算，只规定整轮 75s；20s 是“最多等 1/4 总预算”的工程取值，
 * <b>刻意不进配置文件</b>（可配置化属于 P1-D 的预算统一治理）。实际生效超时 =
 * min(20s, 本轮剩余预算)。
 *
 * <p>校验规则（与方案 §P1-2 原文一致）：
 * <ol>
 *   <li>{@code subQuestions} 数量 1~3；超过 3 个<b>截断为前 3 个</b>并打 warn（不是整份降级，
 *       因为多出来的子问题属于“计划过度拆分”，截断仍然是一次可用计划；见交付汇报的取舍说明）；</li>
 *   <li>每个 {@code query} 非空；</li>
 *   <li>{@code resources} 只允许 {@link AgentPlan#canonicalResource(String)} 认可的
 *       {@code document / mindmap / question}（兼容真实 wire 名 {@code document_chunk /
 *       mindmap_node}）；缺省时补 {@code [document]}；出现不允许的值 → 整份降级。</li>
 * </ol>
 *
 * <p><b>不落用户原文</b>：本类只把“用户问题：xxx”拼进一次 AI 请求，不写库、不写日志正文；
 * 日志只打长度与子问题数量。计划 JSON 由调用方（Streamer）落 {@code agent_runs.plan_json}。
 */
@Component
public class AgentPlanner {

    private static final Logger log = LoggerFactory.getLogger(AgentPlanner.class);

    /** Planner 自身硬上限（毫秒）：20 秒，硬编码、不进配置（理由见类注释）。 */
    static final long PLANNER_TIMEOUT_MS = 20_000L;

    /** 剩余预算低于该值时不值得再调一次 Planner，直接降级单查询。 */
    static final long MIN_PLANNER_BUDGET_MS = 1_000L;

    /** 子问题数量上限（方案原文 ≤ 3）。 */
    static final int MAX_SUB_QUESTIONS = 3;

    /** 送进 Planner 的用户消息最长保留字符数（计划只需要问题意图，不需要全文）。 */
    static final int LLM_INPUT_MAX_CHARS = 2000;

    /** 计划 JSON 的输出上限：3 个子问题足够，避免模型啰嗦导致截断。 */
    static final int PLANNER_MAX_OUTPUT_TOKENS = 700;

    /** 降级错误码（落 {@code agent_steps.error_code}，同时写进 plan_json.degradeReason）。 */
    public static final String DEGRADE_BUDGET_EXHAUSTED = "PLAN_BUDGET_EXHAUSTED";
    public static final String DEGRADE_TIMEOUT = "PLAN_TIMEOUT";
    public static final String DEGRADE_CALL_FAILED = "PLAN_CALL_FAILED";
    public static final String DEGRADE_INVALID_JSON = "PLAN_INVALID_JSON";
    public static final String DEGRADE_EMPTY_SUB_QUESTIONS = "PLAN_EMPTY_SUB_QUESTIONS";
    public static final String DEGRADE_INVALID_SUB_QUESTION = "PLAN_INVALID_SUB_QUESTION";
    public static final String DEGRADE_INVALID_RESOURCE = "PLAN_INVALID_RESOURCE";

    /** 规划提示词：字段与方案 §P1-2 示例完全对齐，只多一句“不需要多跳也给 1 个”。 */
    private static final String PLANNER_SYSTEM_PROMPT = """
            你是学习 Agent 的任务规划器。把用户问题拆成 1~3 个子问题，并为每个子问题写一条用于
            资料检索的查询。只输出 JSON，不要解释，格式如下：
            {"complexity":"MULTI_HOP","subQuestions":[{"id":"q1","question":"...","resources":["document"],"query":"..."}],"needsMemory":false,"answerShape":"comparison"}
            要求：
            1. subQuestions 数量 1~3，子问题之间不要重复；
            2. 每个 query 必须非空，写成适合语义检索的关键词短语，不要抄整段问题；
            3. resources 只允许三种取值：document（课程笔记）、mindmap（思维导图）、question（已有题目），可多选；
            4. needsMemory 只有在必须结合用户长期记忆时才是 true；
            5. 如果问题其实不需要拆，仍然输出 1 个子问题。
            """;

    private final AiGateway aiGateway;

    public AgentPlanner(AiGateway aiGateway) {
        this.aiGateway = aiGateway;
    }

    /**
     * 生成计划；<b>永不抛异常</b>。
     *
     * @param userMessage         原始用户消息（会被截断后放进一次 AI 请求，不落库）
     * @param remainingBudgetMs   本轮剩余预算（毫秒），由 Streamer 用共享 deadline 算出
     * @return 计划 + Planner 真实 token 用量（失败时为空用量，如实不估）
     */
    public Outcome plan(String userMessage, long remainingBudgetMs) {
        long startedNanos = System.nanoTime();
        if (remainingBudgetMs < MIN_PLANNER_BUDGET_MS) {
            log.warn("Planner 跳过：剩余预算 {}ms < {}ms，直接降级单查询",
                    remainingBudgetMs, MIN_PLANNER_BUDGET_MS);
            return degrade(userMessage, DEGRADE_BUDGET_EXHAUSTED, AiUsage.empty(), startedNanos);
        }
        if (aiGateway == null) {
            log.warn("Planner 未接线 AI 网关，降级单查询");
            return degrade(userMessage, DEGRADE_CALL_FAILED, AiUsage.empty(), startedNanos);
        }

        String message = userMessage == null ? "" : userMessage.strip();
        AiJsonRequest request = new AiJsonRequest(
                PLANNER_SYSTEM_PROMPT,
                "用户问题：" + truncate(message, LLM_INPUT_MAX_CHARS),
                true,
                PLANNER_MAX_OUTPUT_TOKENS,
                0.0d);
        long timeoutMs = Math.min(PLANNER_TIMEOUT_MS, remainingBudgetMs);

        AiJsonResult<JsonNode> result;
        try {
            result = callWithTimeout(request, timeoutMs);
        } catch (TimeoutException e) {
            log.warn("Planner 超时（{}ms），降级单查询 queryLength={}", timeoutMs, message.length());
            return degrade(userMessage, DEGRADE_TIMEOUT, AiUsage.empty(), startedNanos);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Planner 被中断，降级单查询 queryLength={}", message.length(), e);
            return degrade(userMessage, DEGRADE_CALL_FAILED, AiUsage.empty(), startedNanos);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            log.warn("Planner 调用失败（cause={}），降级单查询 queryLength={}",
                    cause.getClass().getSimpleName(), message.length(), cause);
            return degrade(userMessage, DEGRADE_CALL_FAILED, AiUsage.empty(), startedNanos);
        } catch (RuntimeException e) {
            // 走到这里说明是本类没预料到的问题（网关异常已由 ExecutionException 覆盖）
            log.error("Planner 出现未预期异常，降级单查询 queryLength={}", message.length(), e);
            return degrade(userMessage, DEGRADE_CALL_FAILED, AiUsage.empty(), startedNanos);
        }

        AiUsage usage = result.usage() == null ? AiUsage.empty() : result.usage();
        try {
            AgentPlan plan = parsePlan(result.value(), message);
            log.info("Planner 产出计划 subQuestions={} needsMemory={} answerShape={} "
                            + "plannerPromptTokens={} plannerCompletionTokens={} elapsedMs={}",
                    plan.subQuestions().size(), plan.needsMemory(), plan.answerShape(),
                    usage.promptTokens(), usage.completionTokens(), elapsedMillis(startedNanos));
            return new Outcome(plan, usage, elapsedMillis(startedNanos));
        } catch (InvalidPlanException e) {
            log.warn("Planner 计划非法（{}: {}），降级单查询 queryLength={}",
                    e.code, e.detail, message.length());
            return degrade(userMessage, e.code, usage, startedNanos);
        } catch (RuntimeException e) {
            log.error("Planner 计划解析出现未预期异常，降级单查询 queryLength={}",
                    message.length(), e);
            return degrade(userMessage, DEGRADE_INVALID_JSON, usage, startedNanos);
        }
    }

    /** 校验并解析 JSON → {@link AgentPlan}；任何不合规都抛 {@link InvalidPlanException}。 */
    private AgentPlan parsePlan(JsonNode root, String userMessage) {
        if (root == null || !root.isObject()) {
            throw new InvalidPlanException(DEGRADE_INVALID_JSON, "返回不是 JSON 对象");
        }
        JsonNode subQuestionsNode = root.get("subQuestions");
        if (subQuestionsNode == null || !subQuestionsNode.isArray() || subQuestionsNode.isEmpty()) {
            throw new InvalidPlanException(DEGRADE_EMPTY_SUB_QUESTIONS, "subQuestions 缺失或为空");
        }

        int total = subQuestionsNode.size();
        int count = Math.min(total, MAX_SUB_QUESTIONS);
        if (total > MAX_SUB_QUESTIONS) {
            // 规格允许“截断成 3 个”或“降级”，这里选截断：计划本身可用，没必要整份丢掉
            log.warn("Planner 返回 {} 个子问题，超过上限 {}，只取前 {} 个",
                    total, MAX_SUB_QUESTIONS, count);
        }

        List<AgentPlan.SubQuestion> subQuestions = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            JsonNode node = subQuestionsNode.get(i);
            if (node == null || !node.isObject()) {
                throw new InvalidPlanException(DEGRADE_INVALID_SUB_QUESTION,
                        "第 " + (i + 1) + " 个子问题不是 JSON 对象");
            }
            String query = text(node.get("query"));
            if (query == null || query.isBlank()) {
                throw new InvalidPlanException(DEGRADE_INVALID_SUB_QUESTION,
                        "第 " + (i + 1) + " 个子问题缺少 query");
            }
            String question = text(node.get("question"));
            if (question == null || question.isBlank()) {
                question = query;
            }
            String id = text(node.get("id"));
            if (id == null || id.isBlank()) {
                id = "q" + (i + 1);
            }
            List<String> resources = parseResources(node.get("resources"), i);
            subQuestions.add(new AgentPlan.SubQuestion(id.strip(), question.strip(), resources,
                    query.strip()));
        }

        boolean needsMemory = root.path("needsMemory").asBoolean(false);
        String answerShape = text(root.get("answerShape"));
        if (answerShape == null || answerShape.isBlank()) {
            answerShape = "answer";
        }
        return AgentPlan.multiHop(subQuestions, needsMemory, answerShape.strip());
    }

    /**
     * 校验 resources：缺省（null / 非数组 / 空数组）补 {@code [document]}；
     * 出现不允许的值 → 整份计划降级（规格原文“resources 只允许 …”）。
     */
    private List<String> parseResources(JsonNode node, int index) {
        if (node == null || !node.isArray() || node.isEmpty()) {
            log.debug("Planner 第 {} 个子问题未给 resources，默认 [document]", index + 1);
            return List.of(AgentPlan.RESOURCE_DOCUMENT);
        }
        Set<String> canonical = new LinkedHashSet<>();
        for (JsonNode element : node) {
            Optional<String> value = AgentPlan.canonicalResource(element == null ? null : element.asText());
            if (value.isEmpty()) {
                throw new InvalidPlanException(DEGRADE_INVALID_RESOURCE,
                        "第 " + (index + 1) + " 个子问题的 resources 含不允许的取值：" + element);
            }
            canonical.add(value.get());
        }
        return List.copyOf(canonical);
    }

    /** 起一个虚拟线程跑阻塞的网关调用；超时就中断并抛 {@link TimeoutException}。 */
    private AiJsonResult<JsonNode> callWithTimeout(AiJsonRequest request, long timeoutMs)
            throws TimeoutException, InterruptedException, ExecutionException {
        FutureTask<AiJsonResult<JsonNode>> task = new FutureTask<>(
                () -> aiGateway.completeJsonForResult(request, JsonNode.class));
        Thread worker = Thread.ofVirtual().name("agent-planner").start(task);
        try {
            return task.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            worker.interrupt();
            task.cancel(true);
            throw e;
        }
    }

    private static Outcome degrade(String userMessage, String reason, AiUsage usage,
                                   long startedNanos) {
        return new Outcome(AgentPlan.singleQuery(userMessage, reason), usage,
                elapsedMillis(startedNanos));
    }

    private static String text(JsonNode node) {
        return node == null || node.isNull() ? null : node.asText();
    }

    private static String truncate(String text, int maxChars) {
        return text.length() <= maxChars ? text : text.substring(0, maxChars);
    }

    private static long elapsedMillis(long startedNanos) {
        return Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
    }

    /**
     * 单次规划结果。
     *
     * @param plan     校验后（或降级）的计划，永不为 null
     * @param usage    Planner 的真实 token 用量；失败/未调用时为空用量（0，不估算）
     * @param elapsedMs 本阶段耗时（毫秒）
     */
    public record Outcome(AgentPlan plan, AiUsage usage, long elapsedMs) {
    }

    /** 计划不合法：code 是稳定错误码，detail 只用于日志（不含用户消息正文）。 */
    private static final class InvalidPlanException extends RuntimeException {

        private final String code;
        private final String detail;

        private InvalidPlanException(String code, String detail) {
            super(detail, null, false, false);
            this.code = code;
            this.detail = detail;
        }
    }
}
