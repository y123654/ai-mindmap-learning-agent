package com.example.aimindmap.mindmap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.aimindmap.ai.AiGatewayException;
import com.example.aimindmap.ai.dto.AiOperationResponse;
import com.example.aimindmap.ai.dto.AiTempNode;
import com.example.aimindmap.ai.dto.AiTempFields;
import com.example.aimindmap.ai.dto.AiTempOperation;
import com.example.aimindmap.mindmap.graph.MindMapGraph;
import com.example.aimindmap.mindmap.graph.MindMapNode;
import com.example.aimindmap.parser.BlockType;
import com.example.aimindmap.parser.MarkdownBlock;
import com.example.aimindmap.parser.MarkdownDiff;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class AiOperationConverterTest {

    private final AiOperationConverter converter = new AiOperationConverter();

    private MindMapGraph graphWithRootAndSection() {
        Instant now = Instant.now();
        MindMapNode root = new MindMapNode("node_001", "光合作用", null, "markdown",
                "blk_root", "光合作用#heading(1)", List.of("光合作用"), now);
        MindMapNode section = new MindMapNode("node_002", "原料", null, "markdown",
                "blk_h2", "光合作用 > 原料#heading(2)", List.of("光合作用", "原料"), now);
        root.getChildren().add(section);
        return new MindMapGraph("node_001", 2, root, now);
    }

    @Test
    void aiAddNodeGetsFormalIdAndInferredParent() {
        MindMapGraph graph = graphWithRootAndSection();
        AiSyncContext context = new AiSyncContext(graph,
                List.of(), List.of(), List.of(), List.of());
        AiOperationResponse response = new AiOperationResponse(List.of(
                new AiTempOperation("add_node", "tmp_001", null, null, null, null,
                        new AiTempNode("条件", "光照和二氧化碳浓度", "ai_enhanced",
                                List.of("光合作用"), "blk_unknown"),
                        null, "归纳")));
        // sourceBlockId 不在允许集合中 -> 协议错误
        assertThatThrownBy(() -> converter.convert(response, context, NodeIdGenerator.basedOn(graph)))
                .isInstanceOf(AiGatewayException.class)
                .hasMessageContaining("sourceBlockId");
    }

    @Test
    void rejectsFormalIdForgedByAi() {
        MindMapGraph graph = graphWithRootAndSection();
        AiSyncContext context = new AiSyncContext(graph,
                List.of(), List.of(), List.of(), List.of());
        // clientTempId 不是 tmp_ 前缀
        AiOperationResponse response = new AiOperationResponse(List.of(
                new AiTempOperation("add_node", "node_999", null, "node_002", null, null,
                        new AiTempNode("条件", null, "ai_enhanced", null, null), null, null)));
        assertThatThrownBy(() -> converter.convert(response, context, NodeIdGenerator.basedOn(graph)))
                .isInstanceOf(AiGatewayException.class)
                .hasMessageContaining("tmp_");
    }

    @Test
    void rejectsDeletionFromAi() {
        MindMapGraph graph = graphWithRootAndSection();
        AiSyncContext context = new AiSyncContext(graph,
                List.of(), List.of(), List.of(), List.of());
        AiOperationResponse response = new AiOperationResponse(List.of(
                new AiTempOperation("propose_remove_source_node", null, null, null,
                        "node_002", null, null, null, "AI 想删除")));
        assertThatThrownBy(() -> converter.convert(response, context, NodeIdGenerator.basedOn(graph)))
                .isInstanceOf(AiGatewayException.class)
                .hasMessageContaining("不允许的操作类型");
    }

    @Test
    void aiCannotUpdateManualNode() {
        MindMapGraph graph = graphWithRootAndSection();
        MindMapNode manual = new MindMapNode("node_manual", "手动知识点", null, "manual",
                "blk_old_paragraph", "manual", List.of("光合作用"), Instant.now());
        manual.setManualLocked(true);
        graph.getTree().getChildren().add(manual);

        MarkdownBlock oldBlock = new MarkdownBlock("blk_old_paragraph", BlockType.PARAGRAPH,
                "旧段落", "旧段落", List.of("光合作用"), null, 0, "old-anchor", "old-hash");
        MarkdownBlock newBlock = new MarkdownBlock("blk_new_paragraph", BlockType.PARAGRAPH,
                "新段落", "新段落", List.of("光合作用"), null, 0, "new-anchor", "new-hash");
        AiSyncContext context = new AiSyncContext(graph,
                List.of(),
                List.of(new MarkdownDiff.ChangedBlock(oldBlock, newBlock)),
                List.of(),
                List.of());
        AiOperationResponse response = new AiOperationResponse(List.of(
                new AiTempOperation("update_node", null, null, null, "node_manual", null,
                        null, new AiTempFields("被 AI 篡改", null), "AI 违反保护规则")));

        assertThatThrownBy(() -> converter.convert(response, context, NodeIdGenerator.basedOn(graph)))
                .isInstanceOf(AiGatewayException.class)
                .hasMessageContaining("受手动保护");
    }
    @Test
    void noopOperationsAreDropped() {
        MindMapGraph graph = graphWithRootAndSection();
        AiSyncContext context = new AiSyncContext(graph,
                List.of(), List.of(), List.of(), List.of());
        AiOperationResponse response = new AiOperationResponse(List.of(
                new AiTempOperation("noop", null, null, null, null, null, null, null, "无变化")));
        assertThat(converter.convert(response, context, NodeIdGenerator.basedOn(graph))).isEmpty();
    }

    @Test
    void aiNestedParagraphNodesAreFlattenedAndHeadingLabelsAreSanitized() {
        Instant now = Instant.now();
        MindMapNode root = new MindMapNode("node_001", "光合作用", null, "markdown",
                "blk_root", "光合作用#heading(1)", List.of("光合作用"), now);
        MindMapNode section = new MindMapNode("node_002", "原料", null, "markdown",
                "blk_h2", "光合作用 > 原料#heading(2)", List.of("光合作用", "原料"), now);
        MindMapNode subsection = new MindMapNode("node_003", "条件", null, "markdown",
                "blk_h3", "光合作用 > 原料 > 条件#heading(3)",
                List.of("光合作用", "原料", "条件"), now);
        root.getChildren().add(section);
        section.getChildren().add(subsection);
        MindMapGraph graph = new MindMapGraph("node_001", 2, root, now);

        MarkdownBlock detailParagraph = new MarkdownBlock("blk_p1", BlockType.PARAGRAPH,
                "光照和温度会影响反应速率。", "光照和温度会影响反应速率。",
                List.of("光合作用", "原料", "条件"), null, 10, "p1-anchor", "p1-hash");
        MarkdownBlock materialParagraph = new MarkdownBlock("blk_p2", BlockType.PARAGRAPH,
                "二氧化碳和水是关键原料。", "二氧化碳和水是关键原料。",
                List.of("光合作用", "原料"), null, 11, "p2-anchor", "p2-hash");
        AiSyncContext context = new AiSyncContext(graph,
                List.of(detailParagraph, materialParagraph), List.of(), List.of(), List.of());

        AiOperationResponse response = new AiOperationResponse(List.of(
                new AiTempOperation("add_node", "tmp_group", null, "node_003", null, null,
                        new AiTempNode("1. 条件", null, "ai_enhanced", List.of(), "blk_p1"),
                        null, "AI 错误地复制标题"),
                new AiTempOperation("add_node", "tmp_child", "tmp_group", null, null, null,
                        new AiTempNode("影响反应速率", null, "ai_enhanced", List.of(), "blk_p1"),
                        null, "具体要点"),
                new AiTempOperation("add_node", "tmp_heading_copy", null, "node_002", null, null,
                        new AiTempNode("原料", null, "ai_enhanced", List.of(), "blk_p2"),
                        null, "AI 错误地生成目录节点")));

        List<ValidatedOperation> ops = converter.convert(response, context, NodeIdGenerator.basedOn(graph));

        assertThat(ops).hasSize(2);
        assertThat(ops).extracting(ValidatedOperation::parentId)
                .containsExactly("node_003", "node_002");
        assertThat(ops).extracting(op -> op.node().getLabel())
                .containsExactly("影响反应速率", "二氧化碳和水是关键原料。");
        assertThat(ops).allSatisfy(op -> {
            assertThat(op.afterNodeId()).isNull();
            assertThat(op.node().getSourceType()).isEqualTo("ai_enhanced");
            assertThat(op.node().getSourcePath()).doesNotContain(op.node().getLabel());
        });
    }
}
