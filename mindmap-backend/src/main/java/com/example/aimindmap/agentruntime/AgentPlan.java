package com.example.aimindmap.agentruntime;

import com.example.aimindmap.vectorindex.VectorResourceType;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * P1-C1：Planner 校验后的单次多跳计划（只存在于内存与 {@code agent_runs.plan_json}）。
 *
 * <p>字段与方案 §P1-2 的 Planner 输出一一对应：{@code complexity / subQuestions / needsMemory /
 * answerShape}；额外两个字段是<b>降级标记</b>：
 * <ul>
 *   <li>{@link #degraded} 为 true 表示本次是“降级单查询”（Planner 非法/异常/超时/预算不足）；</li>
 *   <li>{@link #degradeReason} 是稳定错误码（如 {@code PLAN_INVALID_SUB_QUESTION}），用于落
 *       {@code agent_steps.error_code} 与离线分析；正常计划为 null。</li>
 * </ul>
 * 这两个字段只会进 {@code plan_json}，不会出现在任何对外 DTO（{@code GET /api/agent/runs*} 不暴露 plan）。
 *
 * <p><b>资源类型映射</b>：Planner 提示词里让模型输出 {@code document / mindmap / question}
 * （方案 §P1-2 示例的写法），而 {@code VectorSearchService} 的真实枚举是
 * {@code document_chunk / mindmap_node / question}（见 {@link VectorResourceType}）。
 * 因此校验时把短名规范化成短名，执行时再映射到真实枚举；同时也接受真实 wire 名作为同义别名，
 * 避免模型照抄向量库字段名时整份计划被判非法。{@code memory} 不在允许集合内（本轮不做记忆检索）。
 *
 * @param complexity   {@code MULTI_HOP}（正常计划）或 {@code SINGLE_QUERY}（降级计划）
 * @param subQuestions 1~3 个子问题（执行时按此顺序串行成段、检索时并发）
 * @param needsMemory  Planner 的建议；P1-C1 只记录不执行（记忆检索不在本轮范围）
 * @param answerShape  Planner 的回答形状建议（如 comparison）；P1-C1 只记录
 * @param degraded     是否降级单查询
 * @param degradeReason 降级错误码；正常计划为 null
 */
public record AgentPlan(
        String complexity,
        List<SubQuestion> subQuestions,
        boolean needsMemory,
        String answerShape,
        boolean degraded,
        String degradeReason) {

    /** 正常计划的复杂度取值（与意图名一致）。 */
    public static final String COMPLEXITY_MULTI_HOP = "MULTI_HOP";
    /** 降级单查询计划的复杂度取值。 */
    public static final String COMPLEXITY_SINGLE_QUERY = "SINGLE_QUERY";

    /** 允许的资料类型短名（方案 §P1-2 的写法）。 */
    public static final String RESOURCE_DOCUMENT = "document";
    public static final String RESOURCE_MINDMAP = "mindmap";
    public static final String RESOURCE_QUESTION = "question";

    public AgentPlan {
        subQuestions = subQuestions == null ? List.of() : List.copyOf(subQuestions);
    }

    /** 单个子问题。 */
    public record SubQuestion(String id, String question, List<String> resources, String query) {

        public SubQuestion {
            resources = resources == null ? List.of() : List.copyOf(resources);
        }

        /**
         * 把计划里的短名映射成 {@code VectorSearchService.search} 需要的真实枚举。
         *
         * <p>调用前必须已经过 {@link AgentPlan#canonicalResource(String)} 校验，因此这里
         * 遇到未知值只可能是代码 bug；仍然按“空列表 = 不限资料类型”兜底，不抛异常。
         */
        public List<VectorResourceType> vectorResourceTypes() {
            List<VectorResourceType> types = new ArrayList<>(resources.size());
            for (String resource : resources) {
                Optional<VectorResourceType> type = vectorResourceType(resource);
                if (type.isPresent()) {
                    types.add(type.get());
                }
            }
            return List.copyOf(types);
        }
    }

    /** 正常计划。 */
    public static AgentPlan multiHop(List<SubQuestion> subQuestions, boolean needsMemory,
                                     String answerShape) {
        return new AgentPlan(COMPLEXITY_MULTI_HOP, subQuestions, needsMemory, answerShape,
                false, null);
    }

    /**
     * 降级单查询计划：query 固定为原始用户消息，只检索 1 次、只含 1 个子问题。
     *
     * <p>{@code resources} 固定 {@code [document]}：这是“宁查勿编”的兜底，
     * 与 B2 零调用补检只查笔记的口径一致。
     */
    public static AgentPlan singleQuery(String userMessage, String degradeReason) {
        String query = userMessage == null ? "" : userMessage.strip();
        SubQuestion only = new SubQuestion("q1", query, List.of(RESOURCE_DOCUMENT), query);
        return new AgentPlan(COMPLEXITY_SINGLE_QUERY, List.of(only), false, "answer",
                true, degradeReason);
    }

    /** 打上降级标记（Planner 校验失败时用，保留已解析出的状态无关部分）。 */
    public AgentPlan withDegrade(String reason) {
        return new AgentPlan(complexity, subQuestions, needsMemory, answerShape, true, reason);
    }

    /**
     * 把模型/别名给的资料类型规范化成允许的短名；不允许返回空。
     *
     * <p>接受：{@code document} / {@code document_chunk} / {@code mindmap} /
     * {@code mindmap_node} / {@code question}（大小写不敏感）。
     */
    public static Optional<String> canonicalResource(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String value = raw.strip().toLowerCase(Locale.ROOT);
        return switch (value) {
            case RESOURCE_DOCUMENT, "document_chunk" -> Optional.of(RESOURCE_DOCUMENT);
            case RESOURCE_MINDMAP, "mindmap_node" -> Optional.of(RESOURCE_MINDMAP);
            case RESOURCE_QUESTION -> Optional.of(RESOURCE_QUESTION);
            default -> Optional.empty();
        };
    }

    /** 短名 → 真实向量资料类型。 */
    public static Optional<VectorResourceType> vectorResourceType(String canonicalResource) {
        if (canonicalResource == null) {
            return Optional.empty();
        }
        return switch (canonicalResource.strip().toLowerCase(Locale.ROOT)) {
            case RESOURCE_DOCUMENT, "document_chunk" -> Optional.of(VectorResourceType.DOCUMENT_CHUNK);
            case RESOURCE_MINDMAP, "mindmap_node" -> Optional.of(VectorResourceType.MINDMAP_NODE);
            case RESOURCE_QUESTION -> Optional.of(VectorResourceType.QUESTION);
            default -> Optional.empty();
        };
    }
}
