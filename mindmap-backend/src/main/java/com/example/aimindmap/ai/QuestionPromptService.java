package com.example.aimindmap.ai;

import com.example.aimindmap.mindmap.graph.MindMapGraph;
import com.example.aimindmap.mindmap.graph.MindMapNode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/** v1.1 §12.3：根据最新思维导图生成学科题目的提示词。 */
@Service
public class QuestionPromptService {

    public static final int QUESTION_OUTPUT_TOKENS = 6000;
    private static final int MAX_PROMPT_NODES = 60;

    private static final String SYSTEM_PROMPT = """
            你是一个严谨的学科出题老师。请根据给定思维导图出题，不能超纲。

            硬性规则：
            1. 只能返回 JSON，不能返回 Markdown 代码块或解释文字。
            2. 每道题必须来自输入思维导图节点，不能编造节点中没有的知识。
            3. 题型只能是 single_choice 或 short_answer。
            4. 难度只能是 easy、medium、hard，并且必须等于用户请求的难度。
            5. 单选题必须正好有 A/B/C/D 四个选项，且只能有一个正确答案。
            6. 简答题必须提供 referenceAnswer 参考答案和 explanation 解析。
            7. 题干要清楚，语言适合用户指定年级，不能包含 HTML、脚本或 Markdown 代码块。
            8. 每道题至少关联一个、最多关联三个思维导图节点，nodeId 必须来自输入节点。
            9. 题目数量必须严格等于用户请求数量；不同题目不要重复。
            10. 风险、条件、限制类知识点可以出题，但不能淡化关键前提。

            返回格式：
            {
              "questions": [
                {
                  "questionType": "single_choice | short_answer",
                  "difficulty": "easy | medium | hard",
                  "stem": "题干",
                  "options": [
                    {"key": "A", "text": "选项 A"},
                    {"key": "B", "text": "选项 B"},
                    {"key": "C", "text": "选项 C"},
                    {"key": "D", "text": "选项 D"}
                  ],
                  "answer": {"correctKeys": ["A"]},
                  "explanation": "解析",
                  "nodeIds": ["node_001"]
                },
                {
                  "questionType": "short_answer",
                  "difficulty": "medium",
                  "stem": "题干",
                  "options": null,
                  "answer": {"referenceAnswer": "参考答案要点"},
                  "explanation": "解析",
                  "nodeIds": ["node_001"]
                }
              ]
            }
            """;

    private final ObjectMapper objectMapper;
    private final AiProperties aiProperties;

    public QuestionPromptService(ObjectMapper objectMapper, AiProperties aiProperties) {
        this.objectMapper = objectMapper;
        this.aiProperties = aiProperties;
    }

    public AiJsonRequest buildRequest(String subject,
                                      String grade,
                                      List<String> questionTypes,
                                      String difficulty,
                                      int count,
                                      MindMapGraph graph,
                                      List<String> selectedNodeIds) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("subject", subject);
        input.put("grade", grade);
        input.put("questionTypes", questionTypes);
        input.put("difficulty", difficulty);
        input.put("count", count);
        input.put("mindmap", compactGraph(graph, selectedNodeIds));

        String userPrompt = toJson(input);
        if (userPrompt.length() > aiProperties.getMaxPromptChars()) {
            throw new AiGatewayException("AI_INPUT_TOO_LARGE",
                    "出题所选知识点超过单次上限 " + aiProperties.getMaxPromptChars()
                            + " 字符，请减少关联节点数量后重试。");
        }
        return new AiJsonRequest(SYSTEM_PROMPT, userPrompt, true,
                QUESTION_OUTPUT_TOKENS, aiProperties.getTemperature());
    }

    private List<Map<String, Object>> compactGraph(MindMapGraph graph, List<String> selectedNodeIds) {
        if (graph == null || graph.getTree() == null) {
            return List.of();
        }
        List<MindMapNode> selected = selectedNodeIds == null || selectedNodeIds.isEmpty()
                ? defaultNodes(graph)
                : filterNodes(graph, selectedNodeIds);

        List<Map<String, Object>> result = new ArrayList<>();
        for (MindMapNode node : selected) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", node.getId());
            item.put("label", node.getLabel());
            if (node.getSummary() != null && !node.getSummary().isBlank()) {
                item.put("summary", abbreviate(node.getSummary(), 60));
            }
            if (node.getSourcePath() != null && !node.getSourcePath().isEmpty()) {
                item.put("path", node.getSourcePath());
            }
            result.add(item);
        }
        return result;
    }

    private List<MindMapNode> defaultNodes(MindMapGraph graph) {
        List<MindMapNode> all = new ArrayList<>();
        collect(graph.getTree(), all);
        return all.stream()
                .filter(node -> "active".equals(node.getStatus()))
                .limit(MAX_PROMPT_NODES)
                .toList();
    }

    private List<MindMapNode> filterNodes(MindMapGraph graph, List<String> ids) {
        Map<String, MindMapNode> byId = new LinkedHashMap<>();
        List<MindMapNode> all = new ArrayList<>();
        collect(graph.getTree(), all);
        all.forEach(node -> byId.put(node.getId(), node));
        List<MindMapNode> result = new ArrayList<>();
        for (String id : ids.stream().distinct().toList()) {
            MindMapNode node = byId.get(id);
            if (node != null && "active".equals(node.getStatus())) {
                result.add(node);
            }
        }
        return result;
    }

    private void collect(MindMapNode node, List<MindMapNode> output) {
        output.add(node);
        node.getChildren().forEach(child -> collect(child, output));
    }

    private static String abbreviate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("题目提示词 JSON 序列化失败", e);
        }
    }
}

