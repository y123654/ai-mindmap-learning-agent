package com.example.aimindmap.question;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.aimindmap.ai.dto.AiQuestion;
import com.example.aimindmap.ai.dto.AiQuestionOption;
import com.example.aimindmap.ai.dto.AiQuestionResponse;
import com.example.aimindmap.mindmap.graph.MindMapGraph;
import com.example.aimindmap.mindmap.graph.MindMapNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class QuestionValidatorTest {

    private final QuestionValidator validator = new QuestionValidator();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void acceptsValidSingleChoiceQuestion() {
        MindMapGraph graph = graphWithNode();
        var request = request(List.of("single_choice"), 1);
        var ai = new AiQuestionResponse(List.of(singleChoice("node_001")));

        assertThatCode(() -> validator.validateAiResponse(ai, request, graph))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsSingleChoiceWithoutFourOptions() {
        MindMapGraph graph = graphWithNode();
        var request = request(List.of("single_choice"), 1);
        var bad = new AiQuestion(
                "single_choice", "easy", "题干？",
                List.of(option("A", "A"), option("B", "B"), option("C", "C")),
                objectMapper.createObjectNode().putPOJO("correctKeys", List.of("A")),
                "解析", List.of("node_001"));

        assertThatThrownBy(() -> validator.validateAiResponse(new AiQuestionResponse(List.of(bad)), request, graph))
                .hasMessageContaining("4 个选项");
    }

    @Test
    void rejectsQuestionWhenNodeDoesNotExist() {
        var request = request(List.of("short_answer"), 1);
        var answer = objectMapper.createObjectNode().put("referenceAnswer", "参考答案");
        var ai = new AiQuestionResponse(List.of(new AiQuestion(
                "short_answer", "easy", "题干？", null, answer, "解析", List.of("missing"))));

        assertThatThrownBy(() -> validator.validateAiResponse(ai, request, graphWithNode()))
                .hasMessageContaining("关联节点不存在");
    }

    @Test
    void rejectsWrongReturnedQuestionCount() {
        var request = request(List.of("single_choice"), 2);
        var ai = new AiQuestionResponse(List.of(singleChoice("node_001")));

        assertThatThrownBy(() -> validator.validateAiResponse(ai, request, graphWithNode()))
                .hasMessageContaining("数量应为 2");
    }

    /** 遗留项⑧：AI 返回 0 题时，报错必须带上导图节点数，便于判断是不是空图导致的。 */
    @Test
    void rejectsEmptyAiResponseAndReportsNodeCount() {
        var request = request(List.of("short_answer"), 1);
        var ai = new AiQuestionResponse(List.of());

        assertThatThrownBy(() -> validator.validateAiResponse(ai, request, graphWithNode()))
                .hasMessageContaining("AI 没有返回题目")
                .hasMessageContaining("当前导图节点数 2")
                .hasMessageContaining("请求节点数 1");
    }

    private QuestionDtos.GenerateQuestionsRequest request(List<String> types, int count) {
        return new QuestionDtos.GenerateQuestionsRequest(
                "生物", "初中一年级", types, "easy", count, List.of("node_001"));
    }

    private AiQuestion singleChoice(String nodeId) {
        var answer = objectMapper.createObjectNode();
        answer.putArray("correctKeys").add("A");
        return new AiQuestion(
                "single_choice", "easy", "下列哪一项正确？",
                List.of(option("A", "正确"), option("B", "错误一"),
                        option("C", "错误二"), option("D", "错误三")),
                answer, "解析", List.of(nodeId));
    }

    private AiQuestionOption option(String key, String text) {
        return new AiQuestionOption(key, text);
    }

    private MindMapGraph graphWithNode() {
        MindMapNode root = new MindMapNode(
                "node_root", "根节点", null, "markdown", null, null, List.of("根节点"), Instant.now());
        MindMapNode child = new MindMapNode(
                "node_001", "知识点", "说明", "ai_enhanced", "blk_1", null,
                List.of("根节点", "知识点"), Instant.now());
        root.getChildren().add(child);
        return new MindMapGraph("node_root", 1, root, Instant.now());
    }
}
