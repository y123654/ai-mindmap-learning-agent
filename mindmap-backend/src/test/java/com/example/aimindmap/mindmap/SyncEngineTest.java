package com.example.aimindmap.mindmap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.aimindmap.ai.AiChatRequest;
import com.example.aimindmap.ai.AiGateway;
import com.example.aimindmap.ai.AiJsonRequest;
import com.example.aimindmap.ai.AiJsonResult;
import com.example.aimindmap.ai.AiProperties;
import com.example.aimindmap.ai.AiUsage;
import com.example.aimindmap.ai.dto.AiOperationResponse;
import com.example.aimindmap.ai.dto.AiTempNode;
import com.example.aimindmap.ai.dto.AiTempOperation;
import com.example.aimindmap.common.error.ApiException;
import com.example.aimindmap.mindmap.graph.GraphIndex;
import com.example.aimindmap.mindmap.graph.MindMapGraph;
import com.example.aimindmap.mindmap.graph.MindMapNode;
import com.example.aimindmap.parser.MarkdownBlock;
import com.example.aimindmap.parser.MarkdownParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SyncEngineTest {

    private final MarkdownParser parser = new MarkdownParser();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AiProperties aiProperties = new AiProperties();
    private final StubAiGateway aiGateway = new StubAiGateway();
    private final SyncEngine engine = new SyncEngine(
            parser,
            new com.example.aimindmap.parser.MarkdownBlockDiffer(),
            new RuleOperationBuilder(),
            new MindMapPromptService(objectMapper, aiProperties),
            aiGateway,
            new AiOperationConverter(),
            new MindMapOperationValidator(),
            new GraphPreviewBuilder(),
            new MindMapDepthCap(),
            new MindMapNodeBudget(),
            new MindMapProperties());

    @BeforeEach
    void resetStub() {
        aiGateway.response = null;
        aiGateway.error = null;
        aiGateway.lastRequest = null;
        aiGateway.allRequests.clear();
    }

    private MindMapGraph emptyGraph() {
        return new MindMapGraph(null, 1, null, null);
    }

    private MarkdownBlock paragraph(SyncEngine.SyncPreview preview, String contains) {
        return preview.newBlocks().stream()
                .filter(b -> b.type() == com.example.aimindmap.parser.BlockType.PARAGRAPH
                        && b.normalizedText().contains(contains))
                .findFirst()
                .orElseThrow();
    }

    /**
     * AI 对“本批请求里的每个新增段落”各返回一个 add_node（parent 交给后端按块路径推断）。
     *
     * <p>多段落文档会被 {@link MindMapPromptService} 拆成多批（每批最多 6 段），
     * 所以这里按请求 userPrompt 的 {@code changedBlocks} 只回本批的块：
     * 返回别的批次的 blockId 会被 {@link AiOperationConverter} 以
     * “node.sourceBlockId 不是本次新增/不确定段落的块 ID”拒收。
     */
    private void stubAddNodeForEachAddedParagraph(SyncEngine.SyncPreview previous) {
        List<MarkdownBlock> paragraphs = previous == null
                ? List.of()
                : previous.newBlocks().stream()
                        .filter(b -> b.type() == com.example.aimindmap.parser.BlockType.PARAGRAPH)
                        .toList();
        aiGateway.response = (request, type) -> {
            Set<String> requestedBlockIds = requestedParagraphBlockIds(request);
            List<AiTempOperation> ops = new ArrayList<>();
            int seq = 1;
            for (MarkdownBlock block : paragraphs) {
                if (!requestedBlockIds.contains(block.blockId())) {
                    continue;
                }
                ops.add(new AiTempOperation(
                        "add_node",
                        "tmp_" + String.format("%03d", seq),
                        null,
                        null,
                        null,
                        null,
                        new AiTempNode("AI归纳-" + seq, "段落要点摘要", "ai_enhanced",
                                List.of(), block.blockId()),
                        null,
                        "测试：普通段落归纳"));
                seq++;
            }
            return new AiOperationResponse(ops);
        };
    }

    /** 从 AI 请求的 userPrompt 里读出本批送来的段落块 ID（只用于测试桩，不参与生产逻辑）。 */
    private Set<String> requestedParagraphBlockIds(AiJsonRequest request) {
        Set<String> blockIds = new java.util.LinkedHashSet<>();
        try {
            JsonNode root = objectMapper.readTree(request.userPrompt());
            JsonNode changedBlocks = root.path("changedBlocks");
            for (int i = 0; i < changedBlocks.size(); i++) {
                blockIds.add(changedBlocks.get(i).path("blockId").asText());
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return blockIds;
    }

    // ---------- 阶段 2 回归用例（规则同步，不调用 AI） ----------

    @Test
    void firstSyncBuildsTreeFromHeadingsAndBullets() {
        String markdown = "# 光合作用\n\n## 原料\n\n- 二氧化碳\n- 水\n";
        SyncEngine.SyncPreview preview =
                engine.prepare("未命名文档", markdown, List.of(), emptyGraph());

        MindMapGraph graph = preview.targetGraph();
        assertThat(graph.getVersion()).isEqualTo(2);
        assertThat(graph.getTree().getLabel()).isEqualTo("光合作用");
        assertThat(graph.getTree().getChildren()).hasSize(1);

        MindMapNode materials = graph.getTree().getChildren().get(0);
        assertThat(materials.getLabel()).isEqualTo("原料");
        assertThat(materials.getChildren()).extracting(MindMapNode::getLabel)
                .containsExactly("二氧化碳", "水");
        assertThat(materials.getChildren().get(0).getSourceType()).isEqualTo("markdown");
        assertThat(preview.aiMeta()).isNull();
    }

    @Test
    void addingOneListItemAddsOnlyOneNode() {
        String before = "# 光合作用\n\n## 原料\n\n- 二氧化碳\n- 水\n";
        String after = "# 光合作用\n\n## 原料\n\n- 二氧化碳\n- 水\n- 阳光\n";

        SyncEngine.SyncPreview first = engine.prepare("未命名文档", before, List.of(), emptyGraph());
        List<MarkdownBlock> oldBlocks = first.newBlocks();

        SyncEngine.SyncPreview second =
                engine.prepare("未命名文档", after, oldBlocks, first.targetGraph());

        long addCount = second.operations().stream()
                .filter(op -> "add_node".equals(op.type())).count();
        assertThat(addCount).isEqualTo(1);
        assertThat(second.operations().get(0).node().getLabel()).isEqualTo("阳光");

        MindMapNode materials = second.targetGraph().getTree().getChildren().get(0);
        assertThat(materials.getChildren()).extracting(MindMapNode::getLabel)
                .containsExactly("二氧化碳", "水", "阳光");
        assertThat(second.targetGraph().getVersion()).isEqualTo(3);
    }

    @Test
    void renamedHeadingProducesUpdateOperation() {
        String before = "# 光合作用\n\n## 原料\n";
        String after = "# 光合作用\n\n## 原材料\n";

        SyncEngine.SyncPreview first = engine.prepare("未命名文档", before, List.of(), emptyGraph());
        SyncEngine.SyncPreview second = engine.prepare("未命名文档", after,
                first.newBlocks(), first.targetGraph());

        assertThat(second.operations()).hasSize(1);
        assertThat(second.operations().get(0).type()).isEqualTo("update_node");
        assertThat(second.operations().get(0).node().getLabel()).isEqualTo("原材料");
        assertThat(second.targetGraph().getTree().getChildren().get(0).getLabel())
                .isEqualTo("原材料");
    }

    @Test
    void manuallyEditedNodeIsProtectedFromSync() {
        String before = "# 光合作用\n\n## 原料\n";
        String after = "# 光合作用\n\n## 原材料\n";

        SyncEngine.SyncPreview first = engine.prepare("未命名文档", before, List.of(), emptyGraph());
        MindMapGraph graph = first.targetGraph();
        graph.getTree().getChildren().get(0).setManuallyEdited(true);

        SyncEngine.SyncPreview second = engine.prepare("未命名文档", after,
                first.newBlocks(), graph);
        assertThat(second.operations()).isEmpty();
    }

    @Test
    void tooDeepHierarchyFailsValidationAndKeepsVersion() {
        StringBuilder markdown = new StringBuilder();
        for (int level = 1; level <= 6; level++) {
            markdown.append("#".repeat(level)).append(" 标题").append(level).append("\n\n");
        }
        markdown.append("- 深层列表项\n");

        assertThatThrownBy(() -> engine.prepare("未命名文档", markdown.toString(),
                List.of(), emptyGraph()))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("层级");
    }

    @Test
    void renamedParentHeadingMovesChildrenWithoutDuplicatingNodes() {
        String before = "# 光合作用\n\n## 原料\n\n- 二氧化碳\n- 水\n";
        String after = "# 光合作用\n\n## 原材料\n\n- 二氧化碳\n- 水\n";

        SyncEngine.SyncPreview first = engine.prepare("未命名文档", before, List.of(), emptyGraph());
        SyncEngine.SyncPreview second = engine.prepare("未命名文档", after,
                first.newBlocks(), first.targetGraph());

        assertThat(second.operations()).hasSize(3);
        assertThat(second.operations()).allMatch(op -> "update_node".equals(op.type()));
        MindMapNode materials = second.targetGraph().getTree().getChildren().get(0);
        assertThat(materials.getLabel()).isEqualTo("原材料");
        assertThat(materials.getChildren()).extracting(MindMapNode::getLabel)
                .containsExactly("二氧化碳", "水");
        assertThat(materials.getChildren())
                .allSatisfy(child -> {
                    assertThat(child.getSourceAnchor()).contains("原材料");
                    assertThat(child.getSourcePath()).containsExactly("光合作用", "原材料");
                });
    }

    @Test
    void documentTitleBecomesRootWhenThereIsNoH1() {
        String markdown = "## 原料\n\n- 二氧化碳\n";
        SyncEngine.SyncPreview preview =
                engine.prepare("我的文档", markdown, List.of(), emptyGraph());

        assertThat(preview.targetGraph().getTree().getLabel()).isEqualTo("我的文档");
        assertThat(preview.targetGraph().getTree().getChildren()).hasSize(1);
    }

    // ---------- 阶段 3：AI 段落归纳 ----------

    @Test
    void addedParagraphIsSummarizedIntoAiEnhancedNode() {
        String markdown = "# 光合作用\n\n## 原料\n\n二氧化碳和水是光合作用的原料，在光下生成有机物。\n";
        // 先用纯解析器拿到真实块 ID（不触发 AI），再构造 AI 回复
        MarkdownBlock onlyParagraph = parser.parse(markdown).stream()
                .filter(b -> b.type() == com.example.aimindmap.parser.BlockType.PARAGRAPH)
                .findFirst().orElseThrow();
        aiGateway.response = (request, type) -> new AiOperationResponse(List.of(
                new AiTempOperation("add_node", "tmp_001", null, null, null, null,
                        new AiTempNode("AI归纳-1", "段落要点摘要", "ai_enhanced",
                                List.of(), onlyParagraph.blockId()),
                        null, "测试：普通段落归纳")));

        SyncEngine.SyncPreview preview =
                engine.prepare("未命名文档", markdown, List.of(), emptyGraph());

        assertThat(aiGateway.lastRequest).isNotNull();
        assertThat(aiGateway.lastRequest.requireJson()).isTrue();

        List<ValidatedOperation> aiAdds = preview.operations().stream()
                .filter(op -> "add_node".equals(op.type())
                        && "ai_enhanced".equals(op.node().getSourceType()))
                .toList();
        assertThat(aiAdds).hasSize(1);
        ValidatedOperation aiAdd = aiAdds.get(0);
        assertThat(aiAdd.nodeId()).startsWith("node_");
        assertThat(aiAdd.nodeId()).doesNotContain("tmp");
        assertThat(aiAdd.nodeId()).isEqualTo(aiAdd.node().getId());
        assertThat(aiAdd.node().getLabel()).isEqualTo("AI归纳-1");
        assertThat(aiAdd.node().getSourceBlockId()).isEqualTo(onlyParagraph.blockId());
        assertThat(aiAdd.node().getSourceAnchor()).contains("#paragraph");
        // 父节点是规则生成的“原料”标题
        MindMapNode materials = preview.targetGraph().getTree().getChildren().get(0);
        assertThat(aiAdd.parentId()).isEqualTo(materials.getId());
        assertThat(materials.getChildren()).extracting(MindMapNode::getLabel)
                .contains("AI归纳-1");
        assertThat(preview.aiMeta()).isNotNull();
        assertThat(preview.aiMeta().model()).isEqualTo("stub-model");
    }

    @Test
    void promptAppliesNoiseReductionChapterOwnershipAndSelfCheckRules() {
        String markdown = "# Agent\n\n## 什么是 Agent\n\n"
                + "例如一个很长的故事案例，主要用来说明 Agent 能根据反馈调整计划。请注意不要把案例过程放进图里。\n";

        engine.prepare("未命名文档", markdown, List.of(), emptyGraph());

        assertThat(aiGateway.lastRequest).isNotNull();
        assertThat(aiGateway.lastRequest.systemPrompt())
                .contains("信息抽取与降噪规则")
                .contains("核心名词、关键结论、重要限制")
                .contains("案例支持的核心结论")
                .contains("风险、警告、约束、前提条件不能丢失")
                .contains("严格遵守输入块的 headingPath")
                .contains("输出前三轮自检")
                .contains("没有 Mermaid、XMind、图片或解释文字");
    }

    @Test
    void manyNewParagraphsAreSentToAiInSmallBatches() {
        StringBuilder markdown = new StringBuilder("# 批量段落测试\n");
        for (int i = 1; i <= 13; i++) {
            markdown.append("\n## 主题").append(i)
                    .append("\n\n这是第").append(i)
                    .append("个需要交给 AI 归纳的普通段落，用来验证长文档不会生成被截断的大 JSON。\n");
        }

        aiGateway.response = (request, type) -> {
            try {
                JsonNode root = objectMapper.readTree(request.userPrompt());
                JsonNode changedBlocks = root.path("changedBlocks");
                List<AiTempOperation> ops = new ArrayList<>();
                for (int i = 0; i < changedBlocks.size(); i++) {
                    String blockId = changedBlocks.get(i).path("blockId").asText();
                    ops.add(new AiTempOperation(
                            "add_node",
                            "tmp_" + String.format("%03d", i + 1),
                            null,
                            null,
                            null,
                            null,
                            new AiTempNode("AI归纳-" + blockId.substring(Math.max(0, blockId.length() - 6)),
                                    "段落要点摘要", "ai_enhanced", List.of(), blockId),
                            null,
                            "测试：小批量段落归纳"));
                }
                return new AiOperationResponse(ops);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };

        SyncEngine.SyncPreview preview = engine.prepare(
                "未命名文档", markdown.toString(), List.of(), emptyGraph());

        // 默认 maxOutputTokens=2000 时，每批最多 6 个段落：13 个段落拆成 6 + 6 + 1。
        assertThat(aiGateway.allRequests).hasSize(3);
        assertThat(aiGateway.allRequests.get(0).maxOutputTokens()).isEqualTo(2000);
        assertThat(aiGateway.allRequests)
                .allSatisfy(request -> {
                    JsonNode root = objectMapper.readTree(request.userPrompt());
                    assertThat(root.path("changedBlocks").size()).isLessThanOrEqualTo(6);
                });

        List<ValidatedOperation> aiAdds = preview.operations().stream()
                .filter(op -> "add_node".equals(op.type())
                        && "ai_enhanced".equals(op.node().getSourceType()))
                .toList();
        assertThat(aiAdds).hasSize(13);
        assertThat(preview.aiMeta()).isNotNull();
    }

    @Test
    void changedParagraphUpdatesExistingAiNode() {
        String before = "# 光合作用\n\n## 原料\n\n二氧化碳和水是原料。\n";
        String after = "# 光合作用\n\n## 原料\n\n二氧化碳、水和阳光共同参与光合作用。\n";

        SyncEngine.SyncPreview blocksBefore =
                engine.prepare("未命名文档", before, List.of(), emptyGraph());
        stubAddNodeForEachAddedParagraph(blocksBefore);
        SyncEngine.SyncPreview first =
                engine.prepare("未命名文档", before, List.of(), emptyGraph());
        MindMapNode aiNode = first.targetGraph().getTree().getChildren().get(0).getChildren().stream()
                .filter(n -> "ai_enhanced".equals(n.getSourceType())).findFirst().orElseThrow();

        String oldBlockId = aiNode.getSourceBlockId();
        aiGateway.response = (request, type) -> new AiOperationResponse(List.of(
                new AiTempOperation("update_node", null, null, null, aiNode.getId(), null,
                        null,
                        new com.example.aimindmap.ai.dto.AiTempFields("更新后的要点", "新摘要"),
                        "段落改写")));

        SyncEngine.SyncPreview second = engine.prepare("未命名文档", after,
                first.newBlocks(), first.targetGraph());

        ValidatedOperation update = second.operations().stream()
                .filter(op -> "update_node".equals(op.type()) && op.nodeId().equals(aiNode.getId()))
                .findFirst().orElseThrow();
        assertThat(update.node().getLabel()).isEqualTo("更新后的要点");
        assertThat(update.node().getSourceBlockId()).isEqualTo(oldBlockId);
        MindMapNode updated = second.targetGraph().getTree().getChildren().get(0).getChildren().stream()
                .filter(n -> n.getId().equals(aiNode.getId())).findFirst().orElseThrow();
        assertThat(updated.getLabel()).isEqualTo("更新后的要点");
    }

    // ---------- 大导图三级裁剪（2026-09-17） ----------

    @Test
    void largeDocumentOnlyGeneratesFirstThreeLevels() {
        // 1 个 H1 + 10 个 H2 + 30 个 H3 + 60 个一级缩进列表 + 60 个二级缩进列表 = 161 个规则节点
        // 裁剪后：H1-H3 + 一级缩进列表 = 101 个（在 200 硬上限内），二级缩进列表全部不生成
        StringBuilder md = new StringBuilder();
        md.append("# 大文档根主题\n\n");
        for (int chapter = 0; chapter < 10; chapter++) {
            md.append("## 章节").append(chapter).append("\n\n");
            for (int section = 0; section < 3; section++) {
                md.append("### 小节").append(chapter).append("-").append(section).append("\n\n");
                md.append("- 一级要点甲").append(chapter).append("-").append(section).append("\n");
                md.append("- 一级要点乙").append(chapter).append("-").append(section).append("\n");
                md.append("  - 二级嵌套细节甲").append(chapter).append("-").append(section).append("\n");
                md.append("  - 二级嵌套细节乙").append(chapter).append("-").append(section).append("\n\n");
            }
        }

        SyncEngine.SyncPreview preview =
                engine.prepare("大文档", md.toString(), List.of(), emptyGraph());

        List<MindMapNode> all = new ArrayList<>();
        collect(preview.targetGraph().getTree(), all);
        assertThat(all).hasSize(101);
        int[] maxDepth = {0};
        inspectDepth(preview.targetGraph().getTree(), 0, maxDepth);
        assertThat(maxDepth[0]).isLessThanOrEqualTo(3);
        assertThat(all).noneMatch(n -> n.getLabel().contains("二级嵌套细节"));
        assertThat(all).anyMatch(n -> n.getLabel().contains("一级要点"));
    }

    private void collect(MindMapNode node, List<MindMapNode> out) {
        out.add(node);
        node.getChildren().forEach(child -> collect(child, out));
    }

    private void inspectDepth(MindMapNode node, int depth, int[] maxDepth) {
        maxDepth[0] = Math.max(maxDepth[0], depth);
        node.getChildren().forEach(child -> inspectDepth(child, depth + 1, maxDepth));
    }

    // ---------- 节点预算与折叠：宽文档集成（2026-11-04） ----------

    /**
     * 宽文档集成用例：真实样本 {@code wide-248.md}（{@code samples/复杂Markdown示例2-Agent智能体知识-原文.md}
     * 的原样副本）经过“三级裁剪 → 节点预算折叠 → 校验”后不再因 248 > 200 整单失败。
     *
     * <p>实测（2026-11-04 跑 {@code SyncEngineTest} + {@code MindMapNodeBudgetTest}）：
     * 样本 H1 1 + H2 8 + H3 23 = 32 个标题、180 个一级列表项、36 个段落，
     * 解析后共 248 个 add_node；预算折叠后保留 200 个（固定 68 + 列表 132），
     * 折叠 48 项、涉及 14 个父节点、摘要截断 0 次。
     */
    @Test
    void wideDocumentFoldsToHardCapWithoutValidationFailure() throws IOException {
        String markdown = readClasspathText("/mindmap/wide-248.md");

        // 先纯解析拿到真实块 ID（此时 AI 桩未就绪，不会产生 AI 操作），再让 AI 对每个段落返回一个 add_node
        SyncEngine.SyncPreview blocksPreview =
                engine.prepare("宽文档", markdown, List.of(), emptyGraph());
        stubAddNodeForEachAddedParagraph(blocksPreview);

        // 1) 不抛异常（尤其不能是 VALIDATION_FAILED）：能拿到 preview 就说明校验没有整单失败
        SyncEngine.SyncPreview preview =
                engine.prepare("宽文档", markdown, List.of(), emptyGraph());

        List<ValidatedOperation> adds = preview.operations().stream()
                .filter(op -> "add_node".equals(op.type()))
                .toList();
        // 2) 折叠后新增节点不超过硬上限 200（样本实测正好 200）
        assertThat(adds).hasSizeLessThanOrEqualTo(200);
        assertThat(adds).as("宽文档应被折叠到硬上限 200").hasSize(200);

        // 3) 至少一个节点的 summary 里能看到折叠文本
        List<String> summaries = adds.stream()
                .map(ValidatedOperation::node)
                .filter(java.util.Objects::nonNull)
                .map(MindMapNode::getSummary)
                .filter(summary -> summary != null && !summary.isBlank())
                .toList();
        assertThat(summaries)
                .as("至少一个节点 summary 含折叠标记")
                .anyMatch(summary -> summary.contains("已折叠"));
        assertThat(summaries)
                .anyMatch(summary -> summary.contains(MindMapNodeBudget.FOLD_MARKER));

        // 4) 无孤儿：每个 add 的 parentId / afterNodeId 都能在本批 add 或已有图里找到
        assertNoOrphans(preview.operations(), emptyGraph());
    }

    /** 读取 test/resources 下的 UTF-8 文本资源。 */
    private String readClasspathText(String path) throws IOException {
        try (InputStream in = getClass().getResourceAsStream(path)) {
            assertThat(in).as("类路径资源 %s 必须存在", path).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** 每个保留的 add：父节点与 afterNodeId 必须在本批 add 或已有图中，避免折叠产生孤儿。 */
    private static void assertNoOrphans(List<ValidatedOperation> operations, MindMapGraph graph) {
        Set<String> batchIds = operations.stream()
                .filter(op -> "add_node".equals(op.type()))
                .map(ValidatedOperation::nodeId)
                .collect(Collectors.toSet());
        Set<String> graphIds = GraphIndex.of(graph).allNodes().stream()
                .map(MindMapNode::getId)
                .collect(Collectors.toSet());
        for (ValidatedOperation op : operations) {
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

    @Test
    void aiFailureDoesNotChangeCurrentGraph() {
        String before = "# 光合作用\n\n## 原料\n\n- 二氧化碳\n";
        String after = "# 光合作用\n\n## 原料\n\n- 二氧化碳\n\n这是新增的普通段落，需要 AI 归纳。\n";

        SyncEngine.SyncPreview first = engine.prepare("未命名文档", before, List.of(), emptyGraph());
        MindMapGraph currentGraph = first.targetGraph();
        int versionBefore = currentGraph.getVersion();
        int childrenBefore = currentGraph.getTree().getChildren().get(0).getChildren().size();

        aiGateway.error = new com.example.aimindmap.ai.AiGatewayException(
                "AI_TIMEOUT", "连接 AI 服务超时");

        assertThatThrownBy(() -> engine.prepare("未命名文档", after,
                first.newBlocks(), currentGraph))
                .hasMessageContaining("超时");

        // §5.3：失败时当前已发布图不变
        assertThat(currentGraph.getVersion()).isEqualTo(versionBefore);
        assertThat(currentGraph.getTree().getChildren().get(0).getChildren())
                .hasSize(childrenBefore);
    }

    @Test
    void aiCannotOverrideManuallyEditedNode() {
        String before = "# 光合作用\n\n## 原料\n\n二氧化碳和水是原料。\n";
        String after = "# 光合作用\n\n## 原料\n\n二氧化碳、水和阳光共同参与反应。\n";

        SyncEngine.SyncPreview blocksBefore =
                engine.prepare("未命名文档", before, List.of(), emptyGraph());
        stubAddNodeForEachAddedParagraph(blocksBefore);
        SyncEngine.SyncPreview first =
                engine.prepare("未命名文档", before, List.of(), emptyGraph());
        MindMapNode aiNode = first.targetGraph().getTree().getChildren().get(0).getChildren().stream()
                .filter(n -> "ai_enhanced".equals(n.getSourceType())).findFirst().orElseThrow();
        aiNode.setManuallyEdited(true);

        aiGateway.response = (request, type) -> new AiOperationResponse(List.of(
                new AiTempOperation("update_node", null, null, null, aiNode.getId(), null,
                        null,
                        new com.example.aimindmap.ai.dto.AiTempFields("AI想强改", null),
                        null)));

        assertThatThrownBy(() -> engine.prepare("未命名文档", after,
                first.newBlocks(), first.targetGraph()))
                .isInstanceOf(com.example.aimindmap.ai.AiGatewayException.class)
                .hasMessageContaining("保护");
    }

    // ---------- 阶段 3：删除建议 ----------

    @Test
    void removedMarkdownContentOnlyProducesRemovalSuggestion() {
        String before = "# 光合作用\n\n## 原料\n\n- 二氧化碳\n- 水\n";
        String after = "# 光合作用\n\n## 原料\n\n- 二氧化碳\n";

        SyncEngine.SyncPreview first = engine.prepare("未命名文档", before, List.of(), emptyGraph());
        SyncEngine.SyncPreview second = engine.prepare("未命名文档", after,
                first.newBlocks(), first.targetGraph());

        ValidatedOperation proposal = second.operations().stream()
                .filter(op -> "propose_remove_source_node".equals(op.type()))
                .findFirst().orElseThrow();
        MindMapNode water = first.targetGraph().getTree().getChildren().get(0).getChildren().stream()
                .filter(n -> "水".equals(n.getLabel())).findFirst().orElseThrow();
        assertThat(proposal.nodeId()).isEqualTo(water.getId());

        // 节点仍在树中，只是状态变为 removed_suggested（前端虚线/黄色，不消失）
        MindMapNode suggested = second.targetGraph().getTree().getChildren().get(0).getChildren().stream()
                .filter(n -> n.getId().equals(water.getId())).findFirst().orElseThrow();
        assertThat(suggested.getStatus()).isEqualTo("removed_suggested");
    }

    /** 测试用桩：可返回固定响应或抛异常。 */
    private static final class StubAiGateway implements AiGateway {
        private ResponseBuilder response;
        private RuntimeException error;
        private AiJsonRequest lastRequest;
        private final List<AiJsonRequest> allRequests = new ArrayList<>();

        @Override
        public <T> T completeJson(AiJsonRequest request, Class<T> responseType) {
            return completeJsonForResult(request, responseType).value();
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> AiJsonResult<T> completeJsonForResult(AiJsonRequest request, Class<T> responseType) {
            lastRequest = request;
            allRequests.add(request);
            if (error != null) {
                throw error;
            }
            if (response == null) {
                return new AiJsonResult<>((T) new AiOperationResponse(List.of()), AiUsage.empty(), 1L);
            }
            return new AiJsonResult<>((T) response.build(request, responseType), AiUsage.empty(), 5L);
        }

        @Override
        public String completeChat(AiChatRequest request) {
            throw new UnsupportedOperationException("同步引擎测试不使用普通聊天接口");
        }

        @Override
        public void streamChat(AiChatRequest request, java.util.function.Consumer<String> onDelta) {
            throw new UnsupportedOperationException("同步引擎测试不使用流式聊天接口");
        }

        @Override
        public boolean configured() {
            return true;
        }

        @Override
        public String provider() {
            return "stub";
        }

        @Override
        public String model() {
            return "stub-model";
        }
    }

    @FunctionalInterface
    private interface ResponseBuilder {
        Object build(AiJsonRequest request, Class<?> type);
    }
}