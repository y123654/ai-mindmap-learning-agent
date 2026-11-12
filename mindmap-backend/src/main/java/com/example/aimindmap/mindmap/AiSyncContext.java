package com.example.aimindmap.mindmap;

import com.example.aimindmap.mindmap.graph.MindMapGraph;
import com.example.aimindmap.mindmap.graph.MindMapNode;
import com.example.aimindmap.parser.MarkdownBlock;
import com.example.aimindmap.parser.MarkdownDiff;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 一次同步中需要交给 AI 的上下文（v1.1 §13.2）：
 * 只有新增/修改的普通段落和 uncertain 块才进入 AI（§16.4：未变化块不发送）。
 */
public record AiSyncContext(
        MindMapGraph currentGraph,
        List<MarkdownBlock> addedParagraphs,
        List<MarkdownDiff.ChangedBlock> changedParagraphs,
        List<MarkdownDiff.ChangedBlock> uncertainParagraphs,
        List<ValidatedOperation> ruleOperations
) {

    public boolean required() {
        return !addedParagraphs.isEmpty()
                || !changedParagraphs.isEmpty()
                || !uncertainParagraphs.isEmpty();
    }

    /** 允许 AI 新节点回填的来源块（新增段落）。 */
    public Set<String> allowedNewBlockIds() {
        Set<String> ids = new HashSet<>();
        addedParagraphs.forEach(b -> ids.add(b.blockId()));
        uncertainParagraphs.forEach(pair -> ids.add(pair.newBlock().blockId()));
        return ids;
    }

    /** 允许 AI 更新节点对应的旧来源块（修改 / uncertain 段落）。 */
    public Set<String> allowedUpdateOldBlockIds() {
        Set<String> ids = new HashSet<>();
        changedParagraphs.forEach(pair -> ids.add(pair.oldBlock().blockId()));
        uncertainParagraphs.forEach(pair -> ids.add(pair.oldBlock().blockId()));
        return ids;
    }

    /** 按块 ID 查新版块（用于把 AI 回填的块 ID 还原成真实锚点/路径）。 */
    public Map<String, MarkdownBlock> newBlockById() {
        Map<String, MarkdownBlock> map = new LinkedHashMap<>();
        addedParagraphs.forEach(b -> map.put(b.blockId(), b));
        changedParagraphs.forEach(pair -> map.put(pair.newBlock().blockId(), pair.newBlock()));
        uncertainParagraphs.forEach(pair -> map.put(pair.newBlock().blockId(), pair.newBlock()));
        return map;
    }

    /** 同批次规则新增节点（AI 可能要把段落节点挂到这些新标题下）。 */
    public Map<String, MindMapNode> ruleAddedNodes() {
        Map<String, MindMapNode> map = new LinkedHashMap<>();
        for (ValidatedOperation op : ruleOperations) {
            if ("add_node".equals(op.type()) && op.node() != null) {
                if (op.node().getSourceBlockId() != null) {
                    map.put(op.node().getSourceBlockId(), op.node());
                }
            }
        }
        return map;
    }

    public List<MarkdownBlock> allNewParagraphBlocks() {
        List<MarkdownBlock> all = new ArrayList<>();
        all.addAll(addedParagraphs);
        changedParagraphs.forEach(pair -> all.add(pair.newBlock()));
        uncertainParagraphs.forEach(pair -> all.add(pair.newBlock()));
        return all;
    }
}