package com.example.aimindmap.question;

import com.example.aimindmap.ai.AiGatewayException;
import com.example.aimindmap.ai.dto.AiQuestion;
import com.example.aimindmap.ai.dto.AiQuestionOption;
import com.example.aimindmap.ai.dto.AiQuestionResponse;
import com.example.aimindmap.common.error.ApiException;
import com.example.aimindmap.mindmap.graph.GraphIndex;
import com.example.aimindmap.mindmap.graph.MindMapGraph;
import com.example.aimindmap.mindmap.graph.MindMapNode;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/** v1.1 §12.4：题目请求、AI 输出和手动编辑题目的业务校验。 */
@Component
public class QuestionValidator {

    public static final Set<String> QUESTION_TYPES = Set.of("single_choice", "short_answer");
    public static final Set<String> DIFFICULTIES = Set.of("easy", "medium", "hard");
    private static final int MAX_COUNT = 10;
    private static final int MAX_STEM_LENGTH = 1000;
    private static final int MAX_OPTION_LENGTH = 500;
    private static final int MAX_ANSWER_LENGTH = 2000;
    private static final int MAX_EXPLANATION_LENGTH = 2000;
    private static final int MAX_NODE_IDS_PER_QUESTION = 3;

    public QuestionDtos.GenerateQuestionsRequest validateRequest(
            QuestionDtos.GenerateQuestionsRequest request, MindMapGraph graph) {
        if (request == null) {
            throw ApiException.badRequest("QUESTION_REQUEST_EMPTY", "出题参数不能为空");
        }
        requireShortText(request.subject(), "学科");
        requireText(request.difficulty(), "难度");
        String difficulty = request.difficulty().trim();
        if (!DIFFICULTIES.contains(difficulty)) {
            throw ApiException.badRequest("QUESTION_DIFFICULTY_INVALID", "难度只能是 easy、medium、hard");
        }
        if (request.questionTypes() == null || request.questionTypes().isEmpty()) {
            throw ApiException.badRequest("QUESTION_TYPE_EMPTY", "至少选择一种题型");
        }
        Set<String> types = new HashSet<>();
        for (String rawType : request.questionTypes()) {
            String type = normalize(rawType, "题型");
            if (!QUESTION_TYPES.contains(type)) {
                throw ApiException.badRequest("QUESTION_TYPE_INVALID", "题型只能是 single_choice 或 short_answer");
            }
            types.add(type);
        }
        int count = request.safeCount();
        if (count < 1 || count > MAX_COUNT) {
            throw ApiException.badRequest("QUESTION_COUNT_INVALID", "题目数量必须在 1 到 10 之间");
        }
        List<String> nodeIds = normalizeNodeIds(request.nodeIds(), graph, false);
        String grade = request.grade() == null || request.grade().trim().isEmpty()
                ? "未指定" : request.grade().trim();
        return new QuestionDtos.GenerateQuestionsRequest(
                request.subject().trim(), grade,
                List.copyOf(types), difficulty, count, nodeIds);
    }

    public void validateAiResponse(AiQuestionResponse response,
                                   QuestionDtos.GenerateQuestionsRequest request,
                                   MindMapGraph graph) {
        if (response == null || response.safeQuestions().isEmpty()) {
            // 遗留项⑧：空返回时把节点数带进错误信息，否则用户只看到「AI 没有返回题目」，
            // 无法判断是导图为空、还是模型偶发空返回。
            int nodeCount = GraphIndex.of(graph).allNodes().size();
            throw aiInvalid("AI 没有返回题目（当前导图节点数 " + nodeCount
                    + "，请求节点数 " + request.count() + "）");
        }
        if (response.safeQuestions().size() != request.count()) {
            throw aiInvalid("AI 返回题目数量应为 " + request.count()
                    + "，实际为 " + response.safeQuestions().size());
        }
        GraphIndex index = GraphIndex.of(graph);
        int seq = 1;
        for (AiQuestion question : response.safeQuestions()) {
            String name = "第 " + seq + " 题";
            String type = required(question.questionType(), name + "题型");
            if (!request.questionTypes().contains(type)) {
                throw aiInvalid(name + "题型不在请求范围内");
            }
            String difficulty = required(question.difficulty(), name + "难度");
            if (!difficulty.equals(request.difficulty())) {
                throw aiInvalid(name + "难度必须为 " + request.difficulty());
            }
            validateStem(question.stem(), name);
            validateExplanation(question.explanation(), name);
            List<String> nodeIds = validateAiNodeIds(question.nodeIds(), index, name);
            if ("single_choice".equals(type)) {
                validateSingleChoice(question.options(), question.answer(), name);
            } else {
                validateShortAnswer(question.options(), question.answer(), name);
            }
            // 显式读取，保证 JSON 字段存在且后续保存时有可用节点。
            if (nodeIds.isEmpty()) {
                throw aiInvalid(name + "至少关联一个节点");
            }
            seq++;
        }
    }

    private void validateSingleChoice(List<AiQuestionOption> options, JsonNode answer, String name) {
        if (options == null || options.size() != 4) {
            throw aiInvalid(name + "单选题必须正好有 4 个选项");
        }
        Set<String> keys = new HashSet<>();
        Set<String> allowed = Set.of("A", "B", "C", "D");
        for (AiQuestionOption option : options) {
            if (option == null) {
                throw aiInvalid(name + "存在空选项");
            }
            String key = required(option.key(), name + "选项 key");
            String text = required(option.text(), name + "选项内容");
            if (!allowed.contains(key) || !keys.add(key)) {
                throw aiInvalid(name + "选项 key 必须是不重复的 A/B/C/D");
            }
            if (text.length() > MAX_OPTION_LENGTH || containsHtml(text)) {
                throw aiInvalid(name + "选项内容过长或包含非法 HTML");
            }
        }
        JsonNode correctKeys = answer == null ? null : answer.get("correctKeys");
        if (correctKeys == null || !correctKeys.isArray() || correctKeys.size() != 1) {
            throw aiInvalid(name + "答案必须且只能有一个 correctKeys 数组项");
        }
        String correct = required(correctKeys.get(0).asText(""), name + "正确答案");
        if (!allowed.contains(correct)) {
            throw aiInvalid(name + "正确答案必须是 A/B/C/D 之一");
        }
    }

    private void validateShortAnswer(List<AiQuestionOption> options, JsonNode answer, String name) {
        if (options != null && !options.isEmpty()) {
            throw aiInvalid(name + "简答题不能包含选项");
        }
        if (answer == null || !answer.hasNonNull("referenceAnswer")) {
            throw aiInvalid(name + "简答题答案必须包含 referenceAnswer");
        }
        String reference = answer.get("referenceAnswer").asText("").trim();
        if (reference.isEmpty() || reference.length() > MAX_ANSWER_LENGTH || containsHtml(reference)) {
            throw aiInvalid(name + "参考答案不能为空、过长或包含非法 HTML");
        }
    }

    private List<String> validateAiNodeIds(List<String> rawIds, GraphIndex index, String name) {
        if (rawIds == null || rawIds.isEmpty()) {
            throw aiInvalid(name + "至少关联一个思维导图节点");
        }
        List<String> ids = new ArrayList<>();
        Set<String> unique = new HashSet<>();
        for (String rawId : rawIds) {
            String id = required(rawId, name + "关联节点 ID");
            if (!unique.add(id)) {
                continue;
            }
            MindMapNode node = index.findById(id);
            if (node == null || !"active".equals(node.getStatus())) {
                throw aiInvalid(name + "关联节点不存在或已建议删除：" + id);
            }
            ids.add(id);
        }
        if (ids.size() > MAX_NODE_IDS_PER_QUESTION) {
            throw aiInvalid(name + "最多关联 3 个节点");
        }
        return ids;
    }

    private List<String> normalizeNodeIds(List<String> rawIds, MindMapGraph graph, boolean required) {
        if (rawIds == null || rawIds.isEmpty()) {
            if (required) {
                throw ApiException.badRequest("QUESTION_NODE_IDS_EMPTY", "题目至少关联一个节点");
            }
            return List.of();
        }
        GraphIndex index = GraphIndex.of(graph);
        List<String> ids = new ArrayList<>();
        Set<String> unique = new HashSet<>();
        for (String rawId : rawIds) {
            String id = normalize(rawId, "关联节点");
            if (!unique.add(id)) {
                continue;
            }
            MindMapNode node = index.findById(id);
            if (node == null || !"active".equals(node.getStatus())) {
                throw ApiException.badRequest("QUESTION_NODE_NOT_FOUND", "关联节点不存在或不可用：" + id);
            }
            ids.add(id);
        }
        if (ids.size() > 50) {
            throw ApiException.badRequest("QUESTION_NODE_IDS_TOO_MANY", "一次出题最多选择 50 个节点");
        }
        return ids;
    }

    private void validateStem(String stem, String name) {
        String text = required(stem, name + "题干");
        if (text.length() > MAX_STEM_LENGTH || containsHtml(text)) {
            throw aiInvalid(name + "题干不能为空、过长或包含非法 HTML");
        }
    }

    private void validateExplanation(String explanation, String name) {
        if (explanation == null || explanation.trim().isEmpty()) {
            if (name.startsWith("第")) {
                throw aiInvalid(name + "解析不能为空");
            }
            throw ApiException.badRequest("QUESTION_EXPLANATION_EMPTY", name + "解析不能为空");
        }
        String text = explanation.trim();
        if (text.length() > MAX_EXPLANATION_LENGTH || containsHtml(text)) {
            if (name.startsWith("第")) {
                throw aiInvalid(name + "解析过长或包含非法 HTML");
            }
            throw ApiException.badRequest("QUESTION_EXPLANATION_INVALID", "解析不能过长或包含 HTML");
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.trim().isEmpty()) {
            throw ApiException.badRequest("QUESTION_FIELD_EMPTY", field + "不能为空");
        }
    }

    private static void requireShortText(String value, String field) {
        requireText(value, field);
        if (value.trim().length() > 64) {
            throw ApiException.badRequest("QUESTION_FIELD_TOO_LONG", field + "不能超过 64 字");
        }
    }

    private static String required(String value, String field) {
        if (value == null || value.trim().isEmpty()) {
            throw aiInvalid(field + "不能为空");
        }
        return value.trim();
    }

    private static String normalize(String value, String field) {
        if (value == null || value.trim().isEmpty()) {
            throw ApiException.badRequest("QUESTION_FIELD_EMPTY", field + "不能为空");
        }
        return value.trim();
    }

    private static boolean containsHtml(String value) {
        String lower = value.toLowerCase();
        return lower.contains("<script") || lower.contains("</") || lower.contains("javascript:");
    }

    private static AiGatewayException aiInvalid(String message) {
        return new AiGatewayException("AI_QUESTION_INVALID", message);
    }
}
