package com.example.aimindmap.mindmap;

import com.example.aimindmap.mindmap.graph.GraphIndex;
import com.example.aimindmap.mindmap.graph.MindMapGraph;
import com.example.aimindmap.mindmap.graph.MindMapNode;
import com.example.aimindmap.parser.BlockType;
import com.example.aimindmap.parser.MarkdownBlock;
import com.example.aimindmap.parser.MarkdownDiff;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * v1.1 §1：标题和无序列表由程序规则直接生成节点，不调用 AI。
 * 阶段 2 只处理 HEADING 与 BULLET_LIST_ITEM；普通段落（AI 归纳）与有序列表暂不生成节点。
 */
@Component
public class RuleOperationBuilder {

    /**
     * 根据新解析出的块和差异构建正式操作。
     *
     * @param newBlocks 新版本 Markdown 的全部块
     * @param diff      与上一发布版本的块差异（首次同步时旧块为空，全部为 added）
     * @param graph     当前已发布思维导图（可能为空图）
     * @param title     文档标题（§8.6 无根标题时作为根节点）
     */
    public BuildResult build(List<MarkdownBlock> newBlocks,
                             MarkdownDiff diff,
                             MindMapGraph graph,
                             String title) {
        return build(newBlocks, diff, graph, title, NodeIdGenerator.basedOn(graph));
    }

    /**
     * 阶段 3：可传入外部共享的 {@link NodeIdGenerator}，
     * 保证规则节点与 AI 节点的正式 ID 不冲突（§9.1：正式 ID 只能由后端生成）。
     */
    public BuildResult build(List<MarkdownBlock> newBlocks,
                             MarkdownDiff diff,
                             MindMapGraph graph,
                             String title,
                             NodeIdGenerator idGenerator) {
        List<ValidatedOperation> operations;
        if (graph.isEmpty()) {
            operations = buildInitial(newBlocks, title, idGenerator);
        } else {
            operations = buildIncremental(newBlocks, diff, graph, idGenerator);
        }
        return new BuildResult(operations);
    }

    public record BuildResult(List<ValidatedOperation> operations) {
    }

    // ---------- 首次同步：按文档顺序构建整棵树 ----------

    private List<ValidatedOperation> buildInitial(List<MarkdownBlock> newBlocks,
                                                  String documentTitle,
                                                  NodeIdGenerator idGenerator) {
        List<MarkdownBlock> knowledgeBlocks = newBlocks.stream()
                .filter(RuleOperationBuilder::isKnowledgeBlock)
                .sorted(Comparator.comparingInt(MarkdownBlock::order))
                .toList();

        List<ValidatedOperation> operations = new ArrayList<>();
        Instant now = Instant.now();
        Map<String, String> lastChildByParent = new HashMap<>();

        long h1Count = knowledgeBlocks.stream()
                .filter(b -> b.type() == BlockType.HEADING && b.level() == 1).count();
        boolean syntheticRoot = h1Count != 1;

        String rootId;
        if (syntheticRoot) {
            rootId = idGenerator.next();
            MindMapNode root = new MindMapNode(rootId, safeTitle(documentTitle), null, "markdown",
                    null, null, List.of(safeTitle(documentTitle)), now);
            operations.add(ValidatedOperation.add(rootId, null, null, root,
                    "文档没有唯一一级标题，使用文档标题作为根节点（§8.6）"));
        } else {
            rootId = null;
        }

        // 标题栈：标题级别 -> 节点 ID；列表栈：嵌套深度 -> 节点 ID
        List<HeadingFrame> headingStack = new ArrayList<>();
        List<BulletFrame> bulletStack = new ArrayList<>();

        for (MarkdownBlock block : knowledgeBlocks) {
            if (block.type() == BlockType.HEADING) {
                bulletStack.clear();
                String nodeId;
                String parentId;
                if (!syntheticRoot && block.level() == 1 && rootId == null) {
                    // 唯一一级标题作为根节点
                    nodeId = idGenerator.next();
                    rootId = nodeId;
                    parentId = null;
                } else {
                    while (!headingStack.isEmpty()
                            && headingStack.get(headingStack.size() - 1).level() >= block.level()) {
                        headingStack.remove(headingStack.size() - 1);
                    }
                    parentId = headingStack.isEmpty()
                            ? rootId
                            : headingStack.get(headingStack.size() - 1).nodeId();
                    nodeId = idGenerator.next();
                    headingStack.add(new HeadingFrame(block.level(), nodeId));
                }
                List<String> sourcePath = new ArrayList<>(block.headingPath());
                sourcePath.add(block.normalizedText());
                MindMapNode node = new MindMapNode(nodeId, block.normalizedText(), null, "markdown",
                        block.blockId(), block.anchor(), sourcePath, now);
                String afterId = parentId == null ? null : lastChildByParent.get(parentId);
                operations.add(ValidatedOperation.add(nodeId, parentId, afterId, node,
                        "规则生成：Markdown 标题 " + block.anchor()));
                if (parentId != null) {
                    lastChildByParent.put(parentId, nodeId);
                }
            } else if (block.type() == BlockType.BULLET_LIST_ITEM) {
                int depth = block.level() == null ? 1 : block.level();
                while (!bulletStack.isEmpty()
                        && bulletStack.get(bulletStack.size() - 1).depth() >= depth) {
                    bulletStack.remove(bulletStack.size() - 1);
                }
                String parentId;
                BulletFrame parentBullet = findBulletFrame(bulletStack, depth - 1);
                if (depth > 1 && parentBullet != null) {
                    parentId = parentBullet.nodeId();
                } else {
                    parentId = headingStack.isEmpty()
                            ? rootId
                            : headingStack.get(headingStack.size() - 1).nodeId();
                }
                String nodeId = idGenerator.next();
                MindMapNode node = new MindMapNode(nodeId, block.normalizedText(), null, "markdown",
                        block.blockId(), block.anchor(), new ArrayList<>(block.headingPath()), now);
                String afterId = lastChildByParent.get(parentId);
                operations.add(ValidatedOperation.add(nodeId, parentId, afterId, node,
                        "规则生成：Markdown 无序列表项 " + block.anchor()));
                lastChildByParent.put(parentId, nodeId);
                bulletStack.add(new BulletFrame(depth, nodeId));
            }
        }
        return operations;
    }

    // ---------- 增量同步：只处理新增的标题/列表项和可保护感知的改名 ----------

    private List<ValidatedOperation> buildIncremental(List<MarkdownBlock> newBlocks,
                                                      MarkdownDiff diff,
                                                      MindMapGraph graph,
                                                      NodeIdGenerator idGenerator) {
        GraphIndex index = GraphIndex.of(graph);
        String rootId = graph.getRootId();
        Instant now = Instant.now();
        List<ValidatedOperation> operations = new ArrayList<>();

        // 第 1 步：为本次新增的知识块预分配正式 ID 和节点（父节点可能同样在本批次中）。
        // §8.5 第 5 条：uncertain 无法确定改/删时，按“新增 + 建议删除旧节点”处理，
        // 因此 uncertain 配对中的新知识块也在本步按新增建树（旧块在第 5 步产生删除建议）。
        List<MarkdownBlock> addedKnowledge = new ArrayList<>();
        diff.added().stream().filter(RuleOperationBuilder::isKnowledgeBlock).forEach(addedKnowledge::add);
        diff.uncertain().stream()
                .map(pair -> pair.newBlock())
                .filter(RuleOperationBuilder::isKnowledgeBlock)
                .forEach(addedKnowledge::add);
        addedKnowledge = addedKnowledge.stream()
                .distinct()
                .sorted(Comparator.comparingInt(MarkdownBlock::order))
                .toList();

        Map<String, MindMapNode> addedNodeByBlockId = new HashMap<>();
        for (MarkdownBlock block : addedKnowledge) {
            String nodeId = idGenerator.next();
            List<String> sourcePath = block.type() == BlockType.HEADING
                    ? concatPath(block.headingPath(), block.normalizedText())
                    : new ArrayList<>(block.headingPath());
            MindMapNode node = new MindMapNode(nodeId, block.normalizedText(), null, "markdown",
                    block.blockId(), block.anchor(), sourcePath, now);
            addedNodeByBlockId.put(block.blockId(), node);
        }

        // 第 2 步：按文档顺序为新增块生成 add_node 操作
        for (MarkdownBlock block : addedKnowledge) {
            String parentId = resolveParent(block, addedNodeByBlockId, index, rootId);
            MindMapNode node = addedNodeByBlockId.get(block.blockId());
            String afterId = resolveAfterNode(block, parentId, rootId, newBlocks,
                    addedNodeByBlockId, index);
            operations.add(ValidatedOperation.add(node.getId(), parentId, afterId, node,
                    "规则生成：Markdown 新增" + typeLabel(block.type())));
        }

        // 第 3 步：改名（changed）。manuallyEdited / manualLocked / manual 节点受保护，跳过
        List<MarkdownDiff.ChangedBlock> changedKnowledge = diff.changed().stream()
                .filter(c -> isKnowledgeBlock(c.newBlock()))
                .sorted(Comparator.comparingInt(c -> c.newBlock().order()))
                .toList();
        for (MarkdownDiff.ChangedBlock pair : changedKnowledge) {
            MindMapNode target = index.findByBlockId(pair.oldBlock().blockId());
            if (target == null) {
                continue;
            }
            if (isProtected(target)) {
                continue;
            }
            MindMapNode copy = target.deepCopy();
            copy.setLabel(pair.newBlock().normalizedText());
            copy.setSourceBlockId(pair.newBlock().blockId());
            copy.setSourceAnchor(pair.newBlock().anchor());
            if (pair.newBlock().type() == BlockType.HEADING) {
                copy.setSourcePath(concatPath(pair.newBlock().headingPath(),
                        pair.newBlock().normalizedText()));
            }
            copy.setUpdatedAt(now);
            operations.add(ValidatedOperation.update(target.getId(), copy,
                    "规则同步：Markdown 标题/列表文字修改 " + pair.oldBlock().anchor()));
        }

        // 【v1.1 修订·方案 A】第 4 步：搬家（relocated）。上级标题改名后，文字没变的下级块
        // 锚点/块 ID/路径全部平移。这里只刷新内部溯源信息，不改节点文字，前端不做琥珀高亮。
        // 保护规则与改名一致：manual / manuallyEdited / manualLocked 节点跳过。
        List<MarkdownDiff.ChangedBlock> relocatedKnowledge = diff.relocated().stream()
                .filter(c -> isKnowledgeBlock(c.newBlock()))
                .sorted(Comparator.comparingInt(c -> c.newBlock().order()))
                .toList();
        for (MarkdownDiff.ChangedBlock pair : relocatedKnowledge) {
            MindMapNode target = index.findByBlockId(pair.oldBlock().blockId());
            if (target == null || isProtected(target)) {
                continue;
            }
            MindMapNode copy = target.deepCopy();
            copy.setSourceBlockId(pair.newBlock().blockId());
            copy.setSourceAnchor(pair.newBlock().anchor());
            if (pair.newBlock().type() == BlockType.HEADING) {
                copy.setSourcePath(concatPath(pair.newBlock().headingPath(),
                        pair.newBlock().normalizedText()));
            } else {
                copy.setSourcePath(new ArrayList<>(pair.newBlock().headingPath()));
            }
            copy.setUpdatedAt(now);
            operations.add(ValidatedOperation.update(target.getId(), copy,
                    "规则同步：上级标题改名，节点随标题迁移至 " + pair.newBlock().anchor()));
        }

        // 第 5 步：Markdown 删除内容只产生“删除建议”（§1 / §9.3），不自动删除。
        // 受手动保护、根节点、已是建议状态的节点跳过；找不到对应节点（代码块/表格等）自然忽略。
        for (MarkdownBlock removed : diff.removed()) {
            MindMapNode existing = index.findByBlockId(removed.blockId());
            if (canProposeRemove(existing, graph)) {
                operations.add(ValidatedOperation.proposeRemove(existing.deepCopy(),
                        "Markdown 已删除内容，建议删除对应节点：" + removed.anchor()));
            }
        }
        for (MarkdownDiff.ChangedBlock pair : diff.uncertain()) {
            MindMapNode existing = index.findByBlockId(pair.oldBlock().blockId());
            if (canProposeRemove(existing, graph)) {
                operations.add(ValidatedOperation.proposeRemove(existing.deepCopy(),
                        "Markdown 内容发生较大变化，旧节点建议删除：" + pair.oldBlock().anchor()));
            }
        }
        return operations;
    }

    private String resolveParent(MarkdownBlock block,
                                 Map<String, MindMapNode> addedNodeByBlockId,
                                 GraphIndex index,
                                 String rootId) {
        List<String> headingPath = block.headingPath();

        if (block.type() == BlockType.BULLET_LIST_ITEM && block.level() != null && block.level() > 1) {
            String ancestorAnchor = stripLastBulletAnchor(block.anchor());
            if (ancestorAnchor != null) {
                MindMapNode added = findAddedByAnchor(addedNodeByBlockId, ancestorAnchor);
                if (added != null) {
                    return added.getId();
                }
                MindMapNode existing = index.findByAnchor(ancestorAnchor);
                if (existing != null) {
                    return existing.getId();
                }
            }
        }

        if (headingPath.isEmpty()) {
            return rootId;
        }
        // 列表项的 sourcePath 只含标题路径，因此必须先匹配已发布图，
        // 再匹配同批次新增的“标题”节点，避免把新增列表项误当成自己的父节点
        MindMapNode existing = index.findBySourcePath(headingPath);
        if (existing != null) {
            return existing.getId();
        }
        MindMapNode added = findAddedHeadingByPath(addedNodeByBlockId, headingPath);
        if (added != null) {
            return added.getId();
        }
        return rootId;
    }

    private String resolveAfterNode(MarkdownBlock block,
                                    String parentId,
                                    String rootId,
                                    List<MarkdownBlock> allNewBlocks,
                                    Map<String, MindMapNode> addedNodeByBlockId,
                                    GraphIndex index) {
        // 向前查找 Markdown 顺序中最近的同父知识块：可能是同批次新增块，也可能是已发布块
        MarkdownBlock previous = null;
        for (MarkdownBlock candidate : allNewBlocks) {
            if (candidate.order() >= block.order()) {
                break;
            }
            if (!isKnowledgeBlock(candidate)) {
                continue;
            }
            String candidateParent = resolveParent(candidate, addedNodeByBlockId, index, rootId);
            if (parentId.equals(candidateParent)) {
                previous = candidate;
            }
        }
        if (previous == null) {
            return null;
        }
        MindMapNode added = addedNodeByBlockId.get(previous.blockId());
        if (added != null) {
            return added.getId();
        }
        MindMapNode existing = index.findByBlockId(previous.blockId());
        return existing == null ? null : existing.getId();
    }

    private static MindMapNode findAddedByAnchor(Map<String, MindMapNode> addedNodes, String anchor) {
        for (MindMapNode node : addedNodes.values()) {
            if (anchor.equals(node.getSourceAnchor())) {
                return node;
            }
        }
        return null;
    }

    private static MindMapNode findAddedHeadingByPath(Map<String, MindMapNode> addedNodes, List<String> path) {
        for (MindMapNode node : addedNodes.values()) {
            boolean isHeadingNode = node.getSourceAnchor() != null
                    && node.getSourceAnchor().contains("#heading(");
            if (isHeadingNode && node.getSourcePath() != null && node.getSourcePath().equals(path)) {
                return node;
            }
        }
        return null;
    }

    /** 从嵌套列表锚点 a#bullet(x)#bullet(y) 得到父项锚点 a#bullet(x)。 */
    private static String stripLastBulletAnchor(String anchor) {
        int lastBullet = anchor.lastIndexOf("#bullet(");
        int lastOrdered = anchor.lastIndexOf("#ordered(");
        int cut = Math.max(lastBullet, lastOrdered);
        return cut <= 0 ? null : anchor.substring(0, cut);
    }

    private static boolean canProposeRemove(MindMapNode node, MindMapGraph graph) {
        if (node == null) {
            return false;
        }
        if (graph.getRootId() != null && graph.getRootId().equals(node.getId())) {
            return false; // §9.4 第 7 条：根节点不允许删除
        }
        if (isProtected(node)) {
            return false;
        }
        return "active".equals(node.getStatus());
    }
    private static boolean isProtected(MindMapNode node) {
        return "manual".equals(node.getSourceType())
                || node.isManualLocked()
                || node.isManuallyEdited();
    }

    private static boolean isKnowledgeBlock(MarkdownBlock block) {
        return block.type() == BlockType.HEADING
                || block.type() == BlockType.BULLET_LIST_ITEM;
    }

    private static List<String> concatPath(List<String> headingPath, String self) {
        List<String> path = new ArrayList<>(headingPath);
        path.add(self);
        return path;
    }

    private static BulletFrame findBulletFrame(List<BulletFrame> frames, int depth) {
        for (int i = frames.size() - 1; i >= 0; i--) {
            if (frames.get(i).depth() == depth) {
                return frames.get(i);
            }
        }
        return null;
    }

    private static String safeTitle(String title) {
        if (title == null || title.isBlank()) {
            return "未命名文档";
        }
        return title.trim();
    }

    private static String typeLabel(BlockType type) {
        return type == BlockType.HEADING ? "标题" : "无序列表项";
    }

    private record HeadingFrame(int level, String nodeId) {
    }

    private record BulletFrame(int depth, String nodeId) {
    }
}