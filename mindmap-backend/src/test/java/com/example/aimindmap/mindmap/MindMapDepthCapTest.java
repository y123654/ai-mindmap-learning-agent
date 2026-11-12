package com.example.aimindmap.mindmap;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.aimindmap.mindmap.graph.MindMapGraph;
import com.example.aimindmap.mindmap.graph.MindMapNode;
import com.example.aimindmap.parser.BlockType;
import com.example.aimindmap.parser.MarkdownBlock;
import com.example.aimindmap.parser.MarkdownDiff;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MindMapDepthCapTest {

    private final MindMapDepthCap cap = new MindMapDepthCap();
    private final Instant now = Instant.now();

    /** 按每层节点数构造 add_node 操作：父节点轮流挂子节点，深度从根（depth=0）开始。 */
    private List<ValidatedOperation> buildAdds(int[] perDepth) {
        List<ValidatedOperation> ops = new ArrayList<>();
        List<String> previous = new ArrayList<>();
        int seq = 0;

        String rootId = "n" + (seq++);
        ops.add(ValidatedOperation.add(rootId, null, null, node(rootId, "root"), "root"));
        previous = List.of(rootId);

        for (int depth = 1; depth < perDepth.length; depth++) {
            List<String> current = new ArrayList<>();
            for (int i = 0; i < perDepth[depth]; i++) {
                String parentId = previous.get(i % previous.size());
                String id = "n" + (seq++);
                ops.add(ValidatedOperation.add(id, parentId, null, node(id, "L" + depth + "-" + i), "test"));
                current.add(id);
            }
            previous = current;
        }
        return ops;
    }

    private MindMapNode node(String id, String label) {
        return new MindMapNode(id, label, null, "markdown", null, null, List.of(label), now);
    }

    private MindMapNode markdownNode(String id, String label, String blockId) {
        return new MindMapNode(id, label, null, "markdown", blockId, "a#" + blockId,
                List.of(label), now);
    }

    private MindMapNode aiNode(String id, String label, String blockId) {
        return new MindMapNode(id, label, null, "ai_enhanced", blockId, "a#" + blockId,
                List.of(label), now);
    }

    private MarkdownBlock heading(int level, String id) {
        return new MarkdownBlock(id, BlockType.HEADING, "H" + level, "H" + level,
                List.of(), level, 0, "a#heading(" + id + ")", "h");
    }

    private MarkdownBlock bullet(int indent, String id, List<String> headingPath) {
        return new MarkdownBlock(id, BlockType.BULLET_LIST_ITEM, "x", "x",
                headingPath, indent, 0, "a#bullet(" + id + ")", "h");
    }

    private MarkdownBlock paragraph(String id, List<String> headingPath) {
        return new MarkdownBlock(id, BlockType.PARAGRAPH, "p", "p", headingPath, null, 0, "a", "h");
    }

    private long addCount(List<ValidatedOperation> ops) {
        return ops.stream().filter(o -> "add_node".equals(o.type())).count();
    }

    @Test
    void decide_248Nodes_activeAndFinalDepthSafetyCutKeeps78() {
        // 分布 1/7/40/30/170 => 总 248；这些测试节点 sourceBlockId=null，结构过滤放行，
        // 合并阶段的深度兜底把深度 >3 的 170 个丢掉 => 78
        List<ValidatedOperation> ops = buildAdds(new int[]{1, 7, 40, 30, 170});
        assertThat(addCount(ops)).isEqualTo(248);

        MindMapDepthCap.Decision decision =
                cap.decide(new MindMapGraph(null, 1, null, null), ops, 0);
        assertThat(decision.active()).isTrue();
        assertThat(decision.predictedAdds()).isEqualTo(248);

        MindMapDepthCap.FilterResult result = cap.filterMergedOperations(ops, Set.of());
        assertThat(addCount(result.operations())).isEqualTo(78);
        assertThat(result.keptAdds()).isEqualTo(78);
        assertThat(result.droppedAdds()).isEqualTo(170);
    }

    @Test
    void decide_94Nodes_inactive() {
        List<ValidatedOperation> ops = buildAdds(new int[]{1, 2, 9, 31, 51});
        assertThat(addCount(ops)).isEqualTo(94);
        assertThat(cap.decide(new MindMapGraph(null, 1, null, null), ops, 0).active()).isFalse();
    }

    @Test
    void decide_boundary() {
        assertThat(cap.decide(new MindMapGraph(null, 1, null, null),
                buildAdds(new int[]{1, 9, 90}), 0).active()).isFalse();
        assertThat(cap.decide(new MindMapGraph(null, 1, null, null),
                buildAdds(new int[]{1, 10, 90}), 0).active()).isTrue();
    }

    @Test
    void decide_paragraphEstimateCounts() {
        List<ValidatedOperation> ops = buildAdds(new int[]{1, 9, 80});
        assertThat(addCount(ops)).isEqualTo(90);
        MindMapDepthCap.Decision decision =
                cap.decide(new MindMapGraph(null, 1, null, null), ops, 20);
        assertThat(decision.active()).isTrue();
        assertThat(decision.predictedAdds()).isEqualTo(110);
    }

    @Test
    void allowedRuleBlockIds_onlyH1toH3AndFirstIndentBullets() {
        List<MarkdownBlock> blocks = List.of(
                heading(1, "blk_h1"), heading(2, "blk_h2"), heading(3, "blk_h3"),
                heading(4, "blk_h4"), heading(5, "blk_h5"),
                bullet(1, "blk_b1", List.of("H1", "H2")),
                bullet(2, "blk_b2", List.of("H1", "H2")),
                bullet(3, "blk_b3", List.of("H1", "H2")));
        Set<String> allowed = cap.allowedRuleBlockIds(blocks);
        assertThat(allowed).containsExactlyInAnyOrder(
                "blk_h1", "blk_h2", "blk_h3", "blk_b1");
    }

    @Test
    void filterRuleOperations_dropsH4AndNestedBullets() {
        List<ValidatedOperation> ops = List.of(
                ValidatedOperation.add("h1", null, null, markdownNode("h1", "根", "blk_h1"), "r"),
                ValidatedOperation.add("h4", "h1", null, markdownNode("h4", "四级标题", "blk_h4"), "r"),
                ValidatedOperation.add("b1", "h1", null, markdownNode("b1", "一级列表", "blk_b1"), "r"),
                ValidatedOperation.add("b2", "b1", null, markdownNode("b2", "二级列表", "blk_b2"), "r"));
        Set<String> allowed = Set.of("blk_h1", "blk_b1");

        List<ValidatedOperation> kept = cap.filterRuleOperations(ops, allowed);
        assertThat(kept).extracting(ValidatedOperation::nodeId).containsExactly("h1", "b1");
    }

    @Test
    void filterMerged_aiNodesOnlyCutByDepth_nonAddOpsKept() {
        // h1(depth0) -> h2(depth1) -> h3(depth2) -> b1(depth3) -> ai 段落节点(depth4) 被丢弃；
        // H4 markdown 节点被结构过滤；update_node / 删除建议始终保留。
        MindMapNode h1 = markdownNode("h1", "根", "blk_h1");
        MindMapNode h2 = markdownNode("h2", "章", "blk_h2");
        MindMapNode h3 = markdownNode("h3", "节", "blk_h3");
        MindMapNode b1 = markdownNode("b1", "要点", "blk_b1");
        MindMapNode h4 = markdownNode("h4", "四级", "blk_h4");
        MindMapNode aiDeep = aiNode("ai1", "深段落", "blk_p_deep");
        MindMapNode aiOk = aiNode("ai2", "浅段落", "blk_p_ok");
        List<ValidatedOperation> ops = List.of(
                ValidatedOperation.add("h1", null, null, h1, "r"),
                ValidatedOperation.add("h2", "h1", null, h2, "r"),
                ValidatedOperation.add("h3", "h2", null, h3, "r"),
                ValidatedOperation.add("b1", "h3", null, b1, "r"),
                ValidatedOperation.add("h4", "h3", null, h4, "r"),
                ValidatedOperation.add("ai1", "b1", null, aiDeep, "ai"),
                ValidatedOperation.add("ai2", "h3", null, aiOk, "ai"),
                ValidatedOperation.update("h2", h2, "改名"),
                ValidatedOperation.proposeRemove(b1, "删除建议"));
        Set<String> allowed = Set.of("blk_h1", "blk_h2", "blk_h3", "blk_b1");

        MindMapDepthCap.FilterResult result = cap.filterMergedOperations(ops, allowed);
        assertThat(result.operations()).extracting(ValidatedOperation::nodeId)
                .containsExactly("h1", "h2", "h3", "b1", "ai2", "h2", "b1");
    }

    @Test
    void paragraphAllowed_andFilterAiContext() {
        MarkdownBlock underH2 = paragraph("p1", List.of("根", "章"));
        MarkdownBlock underH3 = paragraph("p2", List.of("根", "章", "节"));
        MarkdownBlock tooDeep = paragraph("p3", List.of("根", "章", "节", "要点"));
        assertThat(cap.paragraphAllowed(underH2)).isTrue();
        assertThat(cap.paragraphAllowed(underH3)).isTrue();
        assertThat(cap.paragraphAllowed(tooDeep)).isFalse();

        MindMapGraph empty = new MindMapGraph(null, 1, null, null);
        AiSyncContext context = new AiSyncContext(
                empty,
                List.of(underH2, tooDeep),
                List.<MarkdownDiff.ChangedBlock>of(),
                List.<MarkdownDiff.ChangedBlock>of(),
                List.of());
        AiSyncContext filtered = cap.filterAiContext(context);
        assertThat(filtered.addedParagraphs()).extracting(MarkdownBlock::blockId)
                .containsExactly("p1");
    }
}