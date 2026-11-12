package com.example.aimindmap.agenttracing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.Set;

/**
 * 工具调用的中文展示映射（P0-2，方案 §5.2.2）：前端只负责渲染，文案以后端为单一事实源。
 *
 * <p>安全白名单：{@code detail} 只允许两类内容——检索类工具的参数 {@code query}（截断 30 字），
 * 读取类工具的固定文案。严禁输出资源内部数字 ID、userId、工具结果正文。
 * 参数不是合法 JSON 或字段缺失时降级为空字符串。</p>
 */
public final class AgentToolDisplay {

    /** detail 最大展示字数（方案 §5.2.2：query 截断 30 字）。 */
    static final int DETAIL_MAX_CHARS = 30;

    private static final String UNKNOWN_LABEL = "查询学习资料";
    private static final String NO_DETAIL = "";

    private static final Map<String, String> LABELS = Map.of(
            "search_my_documents", "检索课程笔记",
            "read_my_document", "读取笔记全文",
            "search_mindmap_nodes", "检索思维导图",
            "read_mindmap", "读取思维导图",
            "search_my_questions", "检索习题库",
            "search_my_memory", "检索长期记忆");

    /** 读取类工具：detail 固定文案，绝不回显入参里的 ID。 */
    private static final Map<String, String> FIXED_DETAILS = Map.of(
            "read_my_document", "读取笔记全文",
            "read_mindmap", "读取思维导图完整结构");

    /** 检索类工具：detail 取参数 query（截断 30 字）。 */
    private static final Set<String> QUERY_TOOLS = Set.of(
            "search_my_documents", "search_mindmap_nodes",
            "search_my_questions", "search_my_memory");

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private AgentToolDisplay() {
    }

    /** 工具名 → 中文动作名；未知工具名统一降级为「查询学习资料」。 */
    public static String label(String toolName) {
        if (toolName == null) {
            return UNKNOWN_LABEL;
        }
        return LABELS.getOrDefault(toolName, UNKNOWN_LABEL);
    }

    /** 工具名 + 原始参数 → 可安全展示的 detail；没有安全内容时返回空字符串。 */
    public static String detail(String toolName, String toolInput) {
        if (toolName == null) {
            return NO_DETAIL;
        }
        String fixed = FIXED_DETAILS.get(toolName);
        if (fixed != null) {
            return fixed;
        }
        if (!QUERY_TOOLS.contains(toolName) || toolInput == null || toolInput.isBlank()) {
            return NO_DETAIL;
        }
        String query = readQuery(toolInput);
        return truncate(query);
    }

    private static String readQuery(String toolInput) {
        try {
            JsonNode node = OBJECT_MAPPER.readTree(toolInput);
            if (node == null || !node.isObject()) {
                return NO_DETAIL;
            }
            JsonNode query = node.get("query");
            return query == null || !query.isTextual() ? NO_DETAIL : query.asText();
        } catch (Exception e) {
            // 参数不是合法 JSON 时降级为空 detail，绝不把原始串透给前端
            return NO_DETAIL;
        }
    }

    private static String truncate(String text) {
        if (text == null) {
            return NO_DETAIL;
        }
        String normalized = text.replaceAll("\\s+", " ").trim();
        if (normalized.isEmpty()) {
            return NO_DETAIL;
        }
        return normalized.length() <= DETAIL_MAX_CHARS
                ? normalized
                : normalized.substring(0, DETAIL_MAX_CHARS);
    }
}
