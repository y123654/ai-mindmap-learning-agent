package com.example.aimindmap.mindmap;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.aimindmap.mindmap.graph.MindMapGraph;
import com.example.aimindmap.mindmap.graph.MindMapNode;
import com.example.aimindmap.parser.BlockType;
import com.example.aimindmap.parser.MarkdownBlock;
import com.example.aimindmap.parser.MarkdownParser;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ManualNodeMarkdownWriterTest {

    private final MarkdownParser parser = new MarkdownParser();
    private final ManualNodeMarkdownWriter writer = new ManualNodeMarkdownWriter();

    @Test
    void addsManualChildAsNestedMarkdownListItemUnderBulletParent() {
        String markdown = """
                # Agent

                ## 一、什么是 Agent

                ### 1. Agent 的基本能力

                - 感知
                  - 接收用户输入
                  - 读取文件或数据库
                - 思考
                """;
        List<MarkdownBlock> blocks = parser.parse(markdown);
        MarkdownBlock rootBlock = find(blocks, "Agent", BlockType.HEADING);
        MarkdownBlock sectionBlock = find(blocks, "一、什么是 Agent", BlockType.HEADING);
        MarkdownBlock subSectionBlock = find(blocks, "1. Agent 的基本能力", BlockType.HEADING);
        MarkdownBlock perceptionBlock = find(blocks, "感知", BlockType.BULLET_LIST_ITEM);

        Instant now = Instant.now();
        MindMapNode root = node("node_001", "Agent", rootBlock, now);
        MindMapNode section = node("node_002", "一、什么是 Agent", sectionBlock, now);
        MindMapNode subSection = node("node_003", "1. Agent 的基本能力", subSectionBlock, now);
        MindMapNode perception = node("node_004", "感知", perceptionBlock, now);
        root.getChildren().add(section);
        section.getChildren().add(subSection);
        subSection.getChildren().add(perception);
        MindMapGraph graph = new MindMapGraph(root.getId(), 2, root, now);

        ManualNodeMarkdownWriter.WriteResult result = writer.insertManualNode(
                markdown, graph, perception.getId(), "工具调用", "根据需要调用外部 API",
                blocks);

        assertThat(result.markdown()).contains("""
                - 感知
                  - 接收用户输入
                  - 读取文件或数据库
                  - 工具调用
                    根据需要调用外部 API
                - 思考
                """.stripTrailing());

        MarkdownBlock inserted = parser.parse(result.markdown()).stream()
                .filter(block -> block.type() == BlockType.BULLET_LIST_ITEM)
                .filter(block -> block.normalizedText().startsWith("工具调用"))
                .findFirst()
                .orElseThrow();
        assertThat(inserted.headingPath()).containsExactly("Agent", "一、什么是 Agent", "1. Agent 的基本能力");
    }

    private MarkdownBlock find(List<MarkdownBlock> blocks, String text, BlockType type) {
        return blocks.stream()
                .filter(block -> block.type() == type && block.normalizedText().equals(text))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing block: " + text));
    }

    private MindMapNode node(String id, String label, MarkdownBlock block, Instant now) {
        return new MindMapNode(id, label, null, "markdown", block.blockId(), block.anchor(),
                List.copyOf(block.headingPath()), now);
    }
}
