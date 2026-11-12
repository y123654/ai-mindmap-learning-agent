package com.example.aimindmap.vectorindex;

import com.example.aimindmap.ai.dto.AiQuestionOption;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.aimindmap.mindmap.graph.MindMapNode;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/** 构造三类资料的向量化文本与展示元数据（方案 §7.4）。 */
@Component
public class VectorContentBuilder {

    /** 一条待向量化的逻辑内容。 */
    public record VectorItem(
            String title,
            List<String> sourcePath,
            String content,
            int contentVersion) {
    }

    private final ObjectMapper objectMapper;

    public VectorContentBuilder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public VectorItem documentChunk(String documentTitle, MarkdownChunker.Chunk chunk) {
        List<String> path = chunk.sourcePath();
        String title = path.isEmpty() ? documentTitle : path.get(path.size() - 1);
        return new VectorItem(title, path, chunk.text(), 0);
    }

    public VectorItem documentChunk(String documentTitle,
                                    int contentVersion,
                                    MarkdownChunker.Chunk chunk) {
        VectorItem base = documentChunk(documentTitle, chunk);
        return new VectorItem(base.title(), base.sourcePath(), base.content(), contentVersion);
    }

    public VectorItem mindmapNode(MindMapNode node) {
        List<String> path = node.getSourcePath() == null ? List.of() : node.getSourcePath();
        StringBuilder builder = new StringBuilder("节点标题：").append(node.getLabel());
        if (node.getSummary() != null && !node.getSummary().isBlank()) {
            builder.append("\n节点摘要：").append(node.getSummary());
        }
        if (!path.isEmpty()) {
            builder.append("\n所属路径：").append(String.join(" / ", path));
        }
        return new VectorItem(node.getLabel(), path, builder.toString(), 0);
    }

    public VectorItem question(QuestionView view) {
        StringBuilder builder = new StringBuilder("题型：").append(view.questionType());
        builder.append("\n题干：").append(view.stem());

        List<String> renderedOptions = new ArrayList<>();
        for (QuestionOptionView option : view.options()) {
            renderedOptions.add(option.key() + ". " + option.text());
        }
        if (!renderedOptions.isEmpty()) {
            builder.append("\n选项：\n").append(String.join("\n", renderedOptions));
        }
        if (!view.answer().isBlank()) {
            builder.append("\n答案：").append(view.answer());
        }
        if (view.explanation() != null && !view.explanation().isBlank()) {
            builder.append("\n解析：").append(view.explanation());
        }
        if (!view.relatedTitles().isEmpty()) {
            builder.append("\n关联知识点：").append(String.join("、", view.relatedTitles()));
        }

        String title = view.stem().replaceAll("\\s+", " ").trim();
        if (title.length() > 40) {
            title = title.substring(0, 40);
        }
        return new VectorItem(title, List.of(), builder.toString(), 0);
    }

    /** 读取题目实体 JSON 字段，转换成内容构造视图。 */
    public QuestionView toQuestionView(
            com.example.aimindmap.question.QuestionEntity entity, List<String> relatedTitles) {
        List<QuestionOptionView> options = new ArrayList<>();
        for (AiQuestionOption option : readOptions(entity)) {
            options.add(new QuestionOptionView(option.key(), option.text()));
        }
        return new QuestionView(
                entity.getQuestionType(),
                entity.getStem(),
                options,
                renderAnswerJson(readAnswer(entity)),
                entity.getExplanation() == null ? "" : entity.getExplanation(),
                relatedTitles == null ? List.of() : relatedTitles);
    }

    /** 读取题目关联的节点 ID（questions.node_ids）。 */
    public List<String> readNodeIds(com.example.aimindmap.question.QuestionEntity entity) {
        if (entity.getNodeIds() == null || entity.getNodeIds().isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(entity.getNodeIds(), new TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            throw new IllegalStateException("题目关联节点 JSON 无法解析，questionId=" + entity.getId(), e);
        }
    }

    private List<AiQuestionOption> readOptions(com.example.aimindmap.question.QuestionEntity entity) {
        if (entity.getOptions() == null || entity.getOptions().isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(entity.getOptions(),
                    new TypeReference<List<AiQuestionOption>>() {
                    });
        } catch (Exception e) {
            throw new IllegalStateException("题目选项 JSON 无法解析，questionId=" + entity.getId(), e);
        }
    }

    private JsonNode readAnswer(com.example.aimindmap.question.QuestionEntity entity) {
        try {
            return objectMapper.readTree(entity.getAnswer());
        } catch (Exception e) {
            throw new IllegalStateException("题目答案 JSON 无法解析，questionId=" + entity.getId(), e);
        }
    }

    private String renderAnswerJson(JsonNode answer) {
        if (answer == null || answer.isNull()) {
            return "";
        }
        if (answer.isTextual() || answer.isNumber() || answer.isBoolean()) {
            return answer.asText();
        }
        List<String> parts = new ArrayList<>();
        if (answer.isArray()) {
            for (JsonNode element : answer) {
                String key = element.path("key").asText("");
                String text = element.path("text").asText("");
                parts.add(text.isBlank() ? element.asText() : (key.isBlank() ? text : key + ". " + text));
            }
        } else {
            answer.fields().forEachRemaining(entry -> {
                JsonNode value = entry.getValue();
                parts.add(value.isTextual() ? entry.getKey() + ". " + value.asText()
                        : entry.getKey() + ". " + value.toString());
            });
        }
        return String.join("；", parts);
    }

    public record QuestionView(
            String questionType,
            String stem,
            List<QuestionOptionView> options,
            String answer,
            String explanation,
            List<String> relatedTitles) {
    }

    public record QuestionOptionView(String key, String text) {
    }
}
