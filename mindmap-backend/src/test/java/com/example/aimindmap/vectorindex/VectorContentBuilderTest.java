package com.example.aimindmap.vectorindex;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.aimindmap.mindmap.graph.MindMapNode;
import com.example.aimindmap.question.QuestionEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class VectorContentBuilderTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final VectorContentBuilder builder = new VectorContentBuilder(objectMapper);

    @Test
    void mindmapNodeContainsLabelSummaryAndPath() {
        MindMapNode node = new MindMapNode();
        node.setId("n1");
        node.setLabel("进程调度");
        node.setSummary("决定CPU由哪个进程使用");
        node.setSourcePath(List.of("操作系统", "进程管理", "进程调度"));

        VectorContentBuilder.VectorItem item = builder.mindmapNode(node);

        assertThat(item.title()).isEqualTo("进程调度");
        assertThat(item.content()).contains("节点标题：进程调度");
        assertThat(item.content()).contains("节点摘要：决定CPU由哪个进程使用");
        assertThat(item.content()).contains("所属路径：操作系统 / 进程管理 / 进程调度");
        assertThat(item.sourcePath()).containsExactly("操作系统", "进程管理", "进程调度");
    }

    @Test
    void questionContainsTypeStemOptionsAnswerExplanationAndRelated() {
        QuestionEntity entity = new QuestionEntity();
        entity.setQuestionType("单选题");
        entity.setStem("时间片轮转算法主要用于？");
        entity.setOptions("""
                [{"key":"A","text":"分时系统"},{"key":"B","text":"批处理系统"}]
                """.trim());
        entity.setAnswer("\"A\"");
        entity.setExplanation("时间片轮转是分时系统的典型调度算法。");
        entity.setStatus("pending_confirmation");

        VectorContentBuilder.QuestionView view =
                builder.toQuestionView(entity, List.of("进程调度"));
        VectorContentBuilder.VectorItem item = builder.question(view);

        assertThat(item.content()).contains("题型：单选题");
        assertThat(item.content()).contains("题干：时间片轮转算法主要用于？");
        assertThat(item.content()).contains("A. 分时系统").contains("B. 批处理系统");
        assertThat(item.content()).contains("答案：A");
        assertThat(item.content()).contains("解析：时间片轮转是分时系统的典型调度算法。");
        assertThat(item.content()).contains("关联知识点：进程调度");
        assertThat(item.title()).contains("时间片轮转");
    }

    @Test
    void rendersArrayAnswer() {
        QuestionEntity entity = new QuestionEntity();
        entity.setQuestionType("多选题");
        entity.setStem("下列属于进程状态的是？");
        entity.setAnswer("""
                [{"key":"A","text":"就绪"},{"key":"C","text":"阻塞"}]
                """.trim());
        entity.setExplanation("");

        VectorContentBuilder.VectorItem item =
                builder.question(builder.toQuestionView(entity, List.of()));

        assertThat(item.content()).contains("答案：A. 就绪；C. 阻塞");
    }

    @Test
    void documentChunkUsesHeadingOrDocumentTitle() {
        MarkdownChunker chunker = new MarkdownChunker();
        MarkdownChunker.Chunk withHeading =
                chunker.chunk("# 操作系统\n\n进程是资源分配单位。").get(0);
        MarkdownChunker.Chunk preamble =
                chunker.chunk("一些没有标题的内容").get(0);

        VectorContentBuilder.VectorItem titled = builder.documentChunk("我的笔记", withHeading);
        VectorContentBuilder.VectorItem preTitled = builder.documentChunk("我的笔记", preamble);

        assertThat(titled.title()).isEqualTo("操作系统");
        assertThat(preTitled.title()).isEqualTo("我的笔记");
    }
}
