package com.example.aimindmap.mindmap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.aimindmap.common.error.ApiException;
import com.example.aimindmap.mindmap.graph.GraphIndex;
import com.example.aimindmap.mindmap.graph.MindMapGraph;
import com.example.aimindmap.mindmap.graph.MindMapNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * {@link MindMapNodeBudget} 纯单测（《思维导图节点预算与折叠实施方案-v1.0》§6.1 的 15 条用例，
 * 外加 §4.2.6 要求覆盖的“孤儿兜底”分支，以及 2026-11-04 修正 {@code fixed >= cap} 后新增的
 * “fixed == cap 应折叠而不是失败”边界用例）。不起 Spring。
 */
class MindMapNodeBudgetTest {

    private final MindMapNodeBudget budget = new MindMapNodeBudget();
    private final Instant now = Instant.parse("2026-11-03T00:00:00Z");

    // ---------------------------------------------------------------- 用例 1

    @Test
    void belowCap_returnsOperationsUnchanged() {
        List<ValidatedOperation> ops = bulletsUnderHeading(98);
        assertThat(ops).hasSize(100);

        MindMapNodeBudget.BudgetResult result = budget.apply(ops, emptyGraph(), props(200));

        assertThat(result.keptAdds()).isEqualTo(100);
        assertThat(result.foldedAdds()).isZero();
        assertThat(result.foldedParents()).isZero();
        assertThat(result.truncatedSummaries()).isZero();
        assertThat(fingerprint(result.operations())).isEqualTo(fingerprint(ops));
        assertThat(result.operations()).hasSameSizeAs(ops);
    }

    // ---------------------------------------------------------------- 用例 2

    @Test
    void wide248_foldsToHardCapWithoutOrphans() {
        WideDoc doc = wide248();
        assertThat(doc.operations()).hasSize(248);

        MindMapNodeBudget.BudgetResult result =
                budget.apply(doc.operations(), emptyGraph(), props(200));

        assertThat(result.keptAdds()).isEqualTo(200);
        assertThat(result.foldedAdds()).isEqualTo(48);
        assertThat(result.foldedParents()).isEqualTo(14);
        assertThat(addCount(result.operations())).isEqualTo(200);
        assertNoOrphan(result.operations(), emptyGraph());

        // 固定节点 69 个（33 个标题 = 1 根 + 8 个 H2 + 24 个 H3，加 36 个 AI 段落），保留列表 131 个
        assertThat(totalBulletAdds(result.operations())).isEqualTo(131);
        for (String parentId : doc.bulletParents()) {
            assertThat(bulletAddsUnder(result.operations(), parentId))
                    .as("父组 %s 至少保留 1 个列表项", parentId)
                    .isGreaterThanOrEqualTo(1);
        }
        assertThat(summaries(result.operations()))
                .anyMatch(summary -> summary.contains(MindMapNodeBudget.FOLD_MARKER));
    }

    // ---------------------------------------------------------------- 用例 3

    @Test
    void foldedTextContainsLabelsAndMarker() {
        MindMapNodeBudget.BudgetResult result =
                budget.apply(bulletsUnderHeading(10), emptyGraph(), props(5));

        String summary = nodeOf(result.operations(), "h_sec").getSummary();
        assertThat(summary)
                .contains("[节点预算折叠]")
                .contains("已折叠 7 项：")
                .contains("列表项-04")
                .contains("列表项-07")
                .contains("列表项-10")
                .doesNotContain("列表项-01");
        assertThat(result.foldedAdds()).isEqualTo(7);
    }

    // ---------------------------------------------------------------- 用例 4

    @Test
    void allocationIsDeterministic() {
        MindMapNodeBudget.BudgetResult first =
                budget.apply(wide248().operations(), emptyGraph(), props(200));
        MindMapNodeBudget.BudgetResult second =
                budget.apply(wide248().operations(), emptyGraph(), props(200));

        assertThat(fingerprint(first.operations())).isEqualTo(fingerprint(second.operations()));
        assertThat(first.foldedAdds()).isEqualTo(second.foldedAdds());
        assertThat(first.foldedParents()).isEqualTo(second.foldedParents());
    }

    // ---------------------------------------------------------------- 用例 5

    @Test
    void everyGroupKeepsAtLeastOneWhenBudgetAllows() {
        List<ValidatedOperation> ops = fourGroupsOfTen();
        List<String> parents = List.of("h_sec0", "h_sec1", "h_sec2", "h_sec3");

        MindMapNodeBudget.BudgetResult result = budget.apply(ops, emptyGraph(), props(20));

        assertThat(result.keptAdds()).isEqualTo(20);
        assertThat(totalBulletAdds(result.operations())).isEqualTo(15);
        int keptSum = 0;
        for (String parentId : parents) {
            long kept = bulletAddsUnder(result.operations(), parentId);
            assertThat(kept).as("父组 %s 的 keep", parentId).isGreaterThanOrEqualTo(1);
            keptSum += (int) kept;
        }
        assertThat(keptSum).isEqualTo(15);
    }

    // ---------------------------------------------------------------- 用例 6

    @Test
    void bulletsCannotSave_fallsBackToAiParagraphPass() {
        // 10 个标题 + 5 个列表项 + 35 个 AI 段落 = 50 个新增，cap=40。
        // 第一轮 fixed=45 > cap，折掉全部列表项也救不回来（本轮不折叠）；
        // 第二轮折 10 个 AI 段落后正好 40。
        // 注：fixed == cap 的边界原先会走“第一轮救不回来”的分支，2026-11-04 按 §2.1 目标 1
        // 把 fixed >= cap 改成 fixed > cap 后，该场景改为“全部折掉正好等于上限”，故本用例的
        // AI 段落数从 30 提高到 35，保持“第一轮救不回来、必须进入第二轮”的覆盖意图不变。
        List<ValidatedOperation> ops = new ArrayList<>();
        ops.add(add("h_root", null, headingNode("h_root", "根")));
        for (int i = 0; i < 9; i++) {
            String id = "h2_" + i;
            ops.add(add(id, "h_root", headingNode(id, "二级标题-" + i)));
        }
        for (int i = 1; i <= 5; i++) {
            String id = String.format("b%02d", i);
            ops.add(add(id, "h2_0", bulletNode(id, "列表项-" + String.format("%02d", i))));
        }
        for (int i = 0; i < 35; i++) {
            String id = "ai_" + i;
            ops.add(add(id, "h2_" + (i % 3), aiNode(id, "AI段落-" + i)));
        }
        assertThat(addCount(ops)).isEqualTo(50);

        MindMapNodeBudget.BudgetResult result = budget.apply(ops, emptyGraph(), props(40));

        assertThat(result.keptAdds()).isEqualTo(40);
        assertThat(result.foldedAdds()).isEqualTo(10);
        assertThat(aiAdds(result.operations())).isEqualTo(25);
        assertThat(totalBulletAdds(result.operations())).isEqualTo(5);
        for (int i = 1; i <= 5; i++) {
            assertThat(addOp(result.operations(), String.format("b%02d", i))).isNotNull();
        }
        assertThat(summaries(result.operations()))
                .anyMatch(summary -> summary.contains("已折叠"));
    }

    // ---------------------------------------------------------------- 用例 7

    @Test
    void headingsAloneOverCap_throwsStructureTooLarge() {
        List<ValidatedOperation> ops = new ArrayList<>();
        ops.add(add("h0", null, headingNode("h0", "根")));
        for (int i = 1; i < 250; i++) {
            ops.add(add("h" + i, "h0", headingNode("h" + i, "标题-" + i)));
        }

        assertThatThrownBy(() -> budget.apply(ops, emptyGraph(), props(200)))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo("MINDMAP_STRUCTURE_TOO_LARGE");
                    assertThat(ex.getMessage())
                            .contains("拆分")
                            .contains("标题无法折叠")
                            .contains("同步")
                            .doesNotContain("重试")
                            .doesNotContain("重试即可");
                });
    }

    // ---------------------------------------------------------------- 用例 8

    @Test
    void foldDisabled_returnsOperationsUnchanged() {
        MindMapProperties disabled = props(200);
        disabled.setFoldEnabled(false);
        WideDoc doc = wide248();

        MindMapNodeBudget.BudgetResult result =
                budget.apply(doc.operations(), emptyGraph(), disabled);

        assertThat(result.keptAdds()).isEqualTo(248);
        assertThat(result.foldedAdds()).isZero();
        assertThat(addCount(result.operations())).isEqualTo(248);
        assertThat(fingerprint(result.operations())).isEqualTo(fingerprint(doc.operations()));
    }

    // ---------------------------------------------------------------- 用例 9

    @Test
    void longFoldText_truncatesTo500WithEllipsis() {
        List<ValidatedOperation> ops = new ArrayList<>();
        ops.add(add("h_root", null, headingNode("h_root", "根")));
        ops.add(add("h_sec", "h_root", headingNode("h_sec", "章节")));
        for (int i = 1; i <= 10; i++) {
            String id = String.format("b%02d", i);
            String label = "长标签" + String.format("%02d", i) + "甲".repeat(90);
            ops.add(add(id, "h_sec", bulletNode(id, label)));
        }

        MindMapNodeBudget.BudgetResult result = budget.apply(ops, emptyGraph(), props(6));

        assertThat(result.foldedAdds()).isEqualTo(6);
        assertThat(result.truncatedSummaries()).isEqualTo(1);
        String summary = nodeOf(result.operations(), "h_sec").getSummary();
        assertThat(summary).hasSize(MindMapOperationValidator.MAX_SUMMARY_LENGTH);
        assertThat(summary).endsWith("…");
    }

    // ---------------------------------------------------------------- 用例 10

    @Test
    void afterNodeIdPointingToFoldedNode_isRepaired() {
        List<ValidatedOperation> ops = new ArrayList<>();
        ops.add(add("h_root", null, headingNode("h_root", "根")));
        ops.add(add("h_sec", "h_root", headingNode("h_sec", "章节")));
        for (int i = 1; i <= 10; i++) {
            String id = String.format("b%02d", i);
            // b01/b02 故意指向会被折叠的尾部节点，用来覆盖兜底修复
            String after = i == 1 ? "b09" : (i == 2 ? "b10" : String.format("b%02d", i - 1));
            ops.add(add(id, "h_sec", after, bulletNode(id, "列表项-" + String.format("%02d", i))));
        }

        MindMapNodeBudget.BudgetResult result = budget.apply(ops, emptyGraph(), props(8));

        // keep 前 6 个，折叠 b07-b10
        assertThat(addOp(result.operations(), "b07")).isNull();
        assertThat(addOp(result.operations(), "b01").afterNodeId()).isNull();
        assertThat(addOp(result.operations(), "b02").afterNodeId()).isEqualTo("b01");
        Set<String> keptIds = addIds(result.operations());
        for (ValidatedOperation op : result.operations()) {
            if (!"add_node".equals(op.type()) || op.afterNodeId() == null) {
                continue;
            }
            assertThat(keptIds)
                    .as("afterNodeId 必须指向保留下来的节点：%s", op.nodeId())
                    .contains(op.afterNodeId());
        }
        assertNoOrphan(result.operations(), emptyGraph());
    }

    // ---------------------------------------------------------------- 用例 11

    @Test
    void updatesAndRemovalsOfFoldedNodes_areDropped() {
        List<ValidatedOperation> ops = new ArrayList<>(bulletsUnderHeading(10));
        ops.add(ValidatedOperation.update("b08", bulletNode("b08", "改过的标题"), "测试"));
        ops.add(ValidatedOperation.proposeRemove(bulletNode("b08", "列表项-08"), "测试"));
        ops.add(ValidatedOperation.update("b01", bulletNode("b01", "保留节点改名"), "测试"));

        MindMapNodeBudget.BudgetResult result = budget.apply(ops, emptyGraph(), props(8));

        assertThat(result.operations())
                .noneMatch(op -> "b08".equals(op.nodeId()));
        assertThat(result.operations())
                .anyMatch(op -> "update_node".equals(op.type()) && "b01".equals(op.nodeId()));
        assertThat(result.foldedAdds()).isEqualTo(4);
    }

    // ---------------------------------------------------------------- 用例 12

    @Test
    void writableExistingParent_getsMergedUpdateNode() {
        MindMapGraph graph = graphWithParent("g_root", "g_parent", false, null);
        List<ValidatedOperation> ops = bulletsUnderExistingParent(10);

        MindMapNodeBudget.BudgetResult result = budget.apply(ops, graph, props(5));

        assertThat(addCount(result.operations())).isEqualTo(5);
        ValidatedOperation update = updateOp(result.operations(), "g_parent");
        assertThat(update).isNotNull();
        assertThat(update.node().getSummary())
                .contains("[节点预算折叠]")
                .contains("已折叠 5 项：")
                .contains("列表项-06")
                .doesNotContain("列表项-01");
        assertThat(result.foldedParents()).isEqualTo(1);
        // 图里的节点不能被就地改（update 用的是 deepCopy）
        assertThat(graph.getTree().getChildren().get(0).getSummary()).isNull();
    }

    // ---------------------------------------------------------------- 用例 13

    @Test
    void manualLockedParent_writesToLastKeptChild() {
        MindMapGraph graph = graphWithParent("g_root", "g_parent", true, "用户内容");
        List<ValidatedOperation> ops = bulletsUnderExistingParent(10);

        MindMapNodeBudget.BudgetResult result = budget.apply(ops, graph, props(5));

        assertThat(updateOp(result.operations(), "g_parent")).isNull();
        assertThat(result.operations())
                .noneMatch(op -> "update_node".equals(op.type()));
        assertThat(nodeOf(result.operations(), "b05").getSummary())
                .contains("[节点预算折叠]")
                .contains("已折叠 5 项：")
                .contains("列表项-06");
        assertThat(graph.getTree().getChildren().get(0).getSummary()).isEqualTo("用户内容");
    }

    // ---------------------------------------------------------------- 用例 14

    @Test
    void repeatedApply_doesNotStackFoldSegment() {
        MindMapGraph graph = graphWithParent("g_root", "g_parent", false,
                "原有摘要\n[节点预算折叠] 已折叠 9 项：旧项-A；旧项-B");

        MindMapNodeBudget.BudgetResult first =
                budget.apply(bulletsUnderExistingParent(10), graph, props(5));
        MindMapNodeBudget.BudgetResult second =
                budget.apply(bulletsUnderExistingParent(10), graph, props(5));

        String firstSummary = updateOp(first.operations(), "g_parent").node().getSummary();
        String secondSummary = updateOp(second.operations(), "g_parent").node().getSummary();
        assertThat(firstSummary).isEqualTo(secondSummary);
        assertThat(countOccurrences(firstSummary, "[节点预算折叠]")).isEqualTo(1);
        assertThat(firstSummary)
                .contains("原有摘要")
                .contains("列表项-06")
                .doesNotContain("旧项-A");

        // 对第一次结果再 apply 一次（已在上限内，应原样返回，摘要不再变化）
        MindMapNodeBudget.BudgetResult third =
                budget.apply(first.operations(), graph, props(5));
        assertThat(updateOp(third.operations(), "g_parent").node().getSummary())
                .isEqualTo(firstSummary);
    }

    // ---------------------------------------------------------------- 用例 15

    @Test
    void nestedBulletWithChildIsNotFolded_noOrphan() {
        List<ValidatedOperation> ops = new ArrayList<>();
        ops.add(add("h_root", null, headingNode("h_root", "根")));
        ops.add(add("h_sec", "h_root", headingNode("h_sec", "章节")));
        ops.add(add("b1", "h_sec", bulletNode("b1", "列表项-01")));
        ops.add(add("b1c", "b1", bulletNode("b1c", "嵌套项")));
        ops.add(add("b2", "h_sec", bulletNode("b2", "列表项-02")));
        ops.add(add("b3", "h_sec", bulletNode("b3", "列表项-03")));
        ops.add(add("b4", "h_sec", bulletNode("b4", "列表项-04")));

        MindMapNodeBudget.BudgetResult result = budget.apply(ops, emptyGraph(), props(5));

        assertThat(result.keptAdds()).isEqualTo(5);
        assertThat(result.foldedAdds()).isEqualTo(2);
        assertThat(addOp(result.operations(), "b1")).isNotNull();
        assertThat(addOp(result.operations(), "b1c")).isNotNull();
        assertThat(addOp(result.operations(), "b2")).isNotNull();
        assertThat(addOp(result.operations(), "b3")).isNull();
        assertThat(addOp(result.operations(), "b4")).isNull();
        assertNoOrphan(result.operations(), emptyGraph());
    }

    // ---------------------------------------------------------------- 附加：fixed == cap 边界

    /**
     * 偏离方案 §4.2.3 的修正用例：原伪代码用 {@code fixed >= cap} 放弃折叠，
     * 导致「固定节点正好等于上限、候选全折掉」这种本来可行的场景白失败。
     * 6 个标题 + 4 个列表项、cap=6 → fixed=6 == cap，应全部折掉 4 个列表项后成功。
     */
    @Test
    void fixedEqualsCap_foldsAllCandidatesInsteadOfFailing() {
        List<ValidatedOperation> ops = new ArrayList<>();
        ops.add(add("h_root", null, headingNode("h_root", "根")));
        for (int i = 0; i < 5; i++) {
            String id = "h2_" + i;
            ops.add(add(id, "h_root", headingNode(id, "二级标题-" + i)));
        }
        for (int i = 1; i <= 4; i++) {
            String id = String.format("b%02d", i);
            ops.add(add(id, "h2_0", bulletNode(id, "列表项-" + String.format("%02d", i))));
        }
        assertThat(addCount(ops)).isEqualTo(10);

        MindMapNodeBudget.BudgetResult result = budget.apply(ops, emptyGraph(), props(6));

        assertThat(result.keptAdds()).isEqualTo(6);
        assertThat(result.foldedAdds()).isEqualTo(4);
        assertThat(result.foldedParents()).isEqualTo(1);
        assertThat(addCount(result.operations())).isEqualTo(6);
        assertThat(totalBulletAdds(result.operations())).isZero();
        assertThat(nodeOf(result.operations(), "h2_0").getSummary())
                .contains("[节点预算折叠]")
                .contains("已折叠 4 项：")
                .contains("列表项-01")
                .contains("列表项-04");
        assertNoOrphan(result.operations(), emptyGraph());
    }

    // ---------------------------------------------------------------- 附加：§4.2.6 孤儿兜底

    @Test
    void keptAddUnderRemovedParent_throwsIllegalStateException() {
        List<ValidatedOperation> ops = List.of(
                add("g_root", null, headingNode("g_root", "根")),
                add("child", "g_root", headingNode("child", "子")));

        assertThatThrownBy(() -> MindMapNodeBudget.filterAndRepair(ops, Set.of("g_root")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("孤儿");
    }

    // ---------------------------------------------------------------- 构造工具

    private MindMapProperties props(int cap) {
        MindMapProperties properties = new MindMapProperties();
        properties.setMaxNewNodes(cap);
        return properties;
    }

    private MindMapGraph emptyGraph() {
        return new MindMapGraph(null, 1, null, null);
    }

    private MindMapGraph graphWithParent(String rootId, String parentId,
                                         boolean manualLocked, String parentSummary) {
        MindMapNode root = headingNode(rootId, "已有根");
        MindMapNode parent = headingNode(parentId, "已有父节点");
        parent.setManualLocked(manualLocked);
        parent.setSummary(parentSummary);
        root.getChildren().add(parent);
        return new MindMapGraph(rootId, 1, root, null);
    }

    private MindMapNode headingNode(String id, String label) {
        return new MindMapNode(id, label, null, "markdown", "blk_" + id,
                "a#heading(" + id + ")", List.of(label), now);
    }

    private MindMapNode bulletNode(String id, String label) {
        return new MindMapNode(id, label, null, "markdown", "blk_" + id,
                "a#bullet(" + id + ")", List.of(label), now);
    }

    private MindMapNode aiNode(String id, String label) {
        return new MindMapNode(id, label, null, "ai_enhanced", "blk_" + id,
                "a#paragraph(" + id + ")", List.of(label), now);
    }

    private ValidatedOperation add(String id, String parentId, MindMapNode node) {
        return ValidatedOperation.add(id, parentId, null, node, "test");
    }

    private ValidatedOperation add(String id, String parentId, String afterNodeId, MindMapNode node) {
        return ValidatedOperation.add(id, parentId, afterNodeId, node, "test");
    }

    /** 根 + 1 个章节 + count 个列表项（总数 count + 2）。 */
    private List<ValidatedOperation> bulletsUnderHeading(int count) {
        List<ValidatedOperation> ops = new ArrayList<>();
        ops.add(add("h_root", null, headingNode("h_root", "根")));
        ops.add(add("h_sec", "h_root", headingNode("h_sec", "章节")));
        for (int i = 1; i <= count; i++) {
            String id = String.format("b%02d", i);
            ops.add(add(id, "h_sec", bulletNode(id, "列表项-" + String.format("%02d", i))));
        }
        return ops;
    }

    /** 图里已有 g_parent 时的 10 个列表项（父节点不在本批 add 里）。 */
    private List<ValidatedOperation> bulletsUnderExistingParent(int count) {
        List<ValidatedOperation> ops = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            String id = String.format("b%02d", i);
            ops.add(add(id, "g_parent", bulletNode(id, "列表项-" + String.format("%02d", i))));
        }
        return ops;
    }

    /** 根 + 4 个章节，每章 10 个列表项（总 45 个新增）。 */
    private List<ValidatedOperation> fourGroupsOfTen() {
        List<ValidatedOperation> ops = new ArrayList<>();
        ops.add(add("h_root", null, headingNode("h_root", "根")));
        for (int section = 0; section < 4; section++) {
            String headingId = "h_sec" + section;
            ops.add(add(headingId, "h_root", headingNode(headingId, "章节-" + section)));
            for (int i = 0; i < 10; i++) {
                String id = "b" + section + "_" + i;
                ops.add(add(id, headingId, bulletNode(id, "列表项-" + section + "-" + i)));
            }
        }
        return ops;
    }

    /**
     * 248 节点样本：1 根 + 8 个 H2 + 24 个 H3（共 32 个标题，含根 33 个 heading 节点中的根单独算 1）
     * + 36 个 AI 段落 + 179 个一级列表项。
     */
    private WideDoc wide248() {
        List<ValidatedOperation> ops = new ArrayList<>();
        List<String> bulletParents = new ArrayList<>();
        ops.add(add("h_root", null, headingNode("h_root", "根主题")));

        List<String> h2 = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            String id = "h2_" + i;
            ops.add(add(id, "h_root", headingNode(id, "二级标题-" + i)));
            h2.add(id);
        }
        List<String> h3 = new ArrayList<>();
        for (int i = 0; i < 24; i++) {
            String id = "h3_" + i;
            ops.add(add(id, h2.get(i % 8), headingNode(id, "三级标题-" + i)));
            h3.add(id);
        }
        for (int i = 0; i < 36; i++) {
            String id = "ai_" + i;
            ops.add(add(id, h3.get(i % 24), aiNode(id, "AI段落-" + i)));
        }

        for (int i = 0; i < 12; i++) {
            bulletParents.add(h3.get(i));
        }
        bulletParents.add(h2.get(6));
        bulletParents.add(h2.get(7));

        int[] sizes = new int[14];
        for (int i = 0; i < 11; i++) {
            sizes[i] = 13;
        }
        for (int i = 11; i < 14; i++) {
            sizes[i] = 12;
        }
        for (int group = 0; group < bulletParents.size(); group++) {
            String parentId = bulletParents.get(group);
            for (int i = 0; i < sizes[group]; i++) {
                String id = "b_" + group + "_" + i;
                ops.add(add(id, parentId, bulletNode(id, "列表项-" + group + "-" + i)));
            }
        }
        return new WideDoc(ops, bulletParents);
    }

    private record WideDoc(List<ValidatedOperation> operations, List<String> bulletParents) {
    }

    // ---------------------------------------------------------------- 断言工具

    private static int addCount(List<ValidatedOperation> ops) {
        return (int) ops.stream().filter(op -> "add_node".equals(op.type())).count();
    }

    private static int totalBulletAdds(List<ValidatedOperation> ops) {
        return (int) ops.stream()
                .filter(op -> "add_node".equals(op.type()))
                .filter(op -> op.node() != null && op.node().getSourceAnchor() != null
                        && op.node().getSourceAnchor().contains("#bullet("))
                .count();
    }

    private static int aiAdds(List<ValidatedOperation> ops) {
        return (int) ops.stream()
                .filter(op -> "add_node".equals(op.type()))
                .filter(op -> op.node() != null && "ai_enhanced".equals(op.node().getSourceType()))
                .count();
    }

    private static long bulletAddsUnder(List<ValidatedOperation> ops, String parentId) {
        return ops.stream()
                .filter(op -> "add_node".equals(op.type()))
                .filter(op -> parentId.equals(op.parentId()))
                .filter(op -> op.node() != null && op.node().getSourceAnchor() != null
                        && op.node().getSourceAnchor().contains("#bullet("))
                .count();
    }

    private static Set<String> addIds(List<ValidatedOperation> ops) {
        return ops.stream()
                .filter(op -> "add_node".equals(op.type()))
                .map(ValidatedOperation::nodeId)
                .collect(Collectors.toSet());
    }

    private static ValidatedOperation addOp(List<ValidatedOperation> ops, String nodeId) {
        return ops.stream()
                .filter(op -> "add_node".equals(op.type()) && nodeId.equals(op.nodeId()))
                .findFirst()
                .orElse(null);
    }

    private static ValidatedOperation updateOp(List<ValidatedOperation> ops, String nodeId) {
        return ops.stream()
                .filter(op -> "update_node".equals(op.type()) && nodeId.equals(op.nodeId()))
                .findFirst()
                .orElse(null);
    }

    private static MindMapNode nodeOf(List<ValidatedOperation> ops, String nodeId) {
        ValidatedOperation op = addOp(ops, nodeId);
        assertThat(op).as("节点 %s 应保留", nodeId).isNotNull();
        return op.node();
    }

    private static List<String> summaries(List<ValidatedOperation> ops) {
        return ops.stream()
                .filter(op -> op.node() != null)
                .map(op -> op.node().getSummary())
                .filter(summary -> summary != null && !summary.isBlank())
                .toList();
    }

    private static List<String> fingerprint(List<ValidatedOperation> ops) {
        return ops.stream()
                .map(op -> op.type() + "|" + op.nodeId() + "|" + op.parentId() + "|"
                        + op.afterNodeId() + "|" + (op.node() == null ? "" : op.node().getSummary()))
                .toList();
    }

    private static int countOccurrences(String text, String token) {
        int count = 0;
        int index = text.indexOf(token);
        while (index >= 0) {
            count++;
            index = text.indexOf(token, index + token.length());
        }
        return count;
    }

    /** 每个保留的 add：父节点必须在批次内或图内；afterNodeId 也必须在批次内或图内。 */
    private static void assertNoOrphan(List<ValidatedOperation> ops, MindMapGraph graph) {
        Set<String> batchIds = addIds(ops);
        Set<String> graphIds = GraphIndex.of(graph).allNodes().stream()
                .map(MindMapNode::getId)
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
        for (ValidatedOperation op : ops) {
            if (!"add_node".equals(op.type())) {
                continue;
            }
            if (op.parentId() != null) {
                assertThat(batchIds.contains(op.parentId()) || graphIds.contains(op.parentId()))
                        .as("节点 %s 的父节点 %s 必须存在", op.nodeId(), op.parentId())
                        .isTrue();
            }
            if (op.afterNodeId() != null) {
                assertThat(batchIds.contains(op.afterNodeId()) || graphIds.contains(op.afterNodeId()))
                        .as("节点 %s 的 afterNodeId %s 必须存在", op.nodeId(), op.afterNodeId())
                        .isTrue();
            }
        }
    }

}
