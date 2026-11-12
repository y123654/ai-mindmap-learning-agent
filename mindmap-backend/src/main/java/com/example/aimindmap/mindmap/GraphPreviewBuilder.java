package com.example.aimindmap.mindmap;

import com.example.aimindmap.mindmap.graph.MindMapGraph;
import com.example.aimindmap.mindmap.graph.MindMapNode;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * v1.1 §9.2 / §13.2：把校验通过的正式操作应用到当前图的副本，生成完整目标图。
 * 不修改入参图，保证同步失败时当前已发布图不变（§5.3）。
 */
@Component
public class GraphPreviewBuilder {

    public MindMapGraph preview(MindMapGraph current, List<ValidatedOperation> operations) {
        Instant now = Instant.now();
        if (operations == null || operations.isEmpty()) {
            // 没有变化时版本号不增长
            return current;
        }

        MindMapNode treeCopy = current.getTree() == null ? null : current.getTree().deepCopy();
        MindMapGraph target = new MindMapGraph(current.getRootId(), current.getVersion() + 1, treeCopy, now);

        for (ValidatedOperation operation : operations) {
            switch (operation.type()) {
                case "add_node" -> applyAdd(target, operation, now);
                case "update_node" -> applyUpdate(target, operation, now);
                case "propose_remove_source_node" -> applyProposeRemove(target, operation, now);
                default -> throw new IllegalStateException("不支持的操作：" + operation.type());
            }
        }
        return target;
    }

    private void applyAdd(MindMapGraph target, ValidatedOperation op, Instant now) {
        MindMapNode newNode = op.node().deepCopy();
        newNode.setCreatedAt(now);
        newNode.setUpdatedAt(now);
        if (op.parentId() == null) {
            target.setTree(newNode);
            target.setRootId(newNode.getId());
        } else {
            MindMapNode parent = findNode(target.getTree(), op.parentId());
            if (parent == null) {
                throw new IllegalStateException("父节点不存在：" + op.parentId());
            }
            List<MindMapNode> children = parent.getChildren();
            if (op.afterNodeId() == null) {
                children.add(newNode);
            } else {
                int index = -1;
                for (int i = 0; i < children.size(); i++) {
                    if (children.get(i).getId().equals(op.afterNodeId())) {
                        index = i;
                        break;
                    }
                }
                if (index >= 0) {
                    children.add(index + 1, newNode);
                } else {
                    // afterNodeId 不是直接兄弟时退化为追加，保证不丢节点
                    children.add(newNode);
                }
            }
        }
    }

    private void applyUpdate(MindMapGraph target, ValidatedOperation op, Instant now) {
        MindMapNode existing = findNode(target.getTree(), op.nodeId());
        if (existing == null) {
            throw new IllegalStateException("待修改节点不存在：" + op.nodeId());
        }
        MindMapNode patch = op.node();
        // 只覆盖内容与溯源字段，保留 id、children、手动保护标志、status，避免更新破坏树结构
        existing.setLabel(patch.getLabel());
        existing.setSummary(patch.getSummary());
        existing.setSourceBlockId(patch.getSourceBlockId());
        existing.setSourceAnchor(patch.getSourceAnchor());
        existing.setSourcePath(patch.getSourcePath());
        existing.setUpdatedAt(now);
    }

    private void applyProposeRemove(MindMapGraph target, ValidatedOperation op, Instant now) {
        MindMapNode existing = findNode(target.getTree(), op.nodeId());
        if (existing == null) {
            throw new IllegalStateException("建议删除的节点不存在：" + op.nodeId());
        }
        // §9.3 / §14.4：节点保留在树中，仅状态变为 removed_suggested，前端虚线/黄色提示
        existing.setStatus("removed_suggested");
        existing.setUpdatedAt(now);
    }
    private MindMapNode findNode(MindMapNode root, String nodeId) {
        if (root == null) {
            return null;
        }
        if (root.getId().equals(nodeId)) {
            return root;
        }
        for (MindMapNode child : root.getChildren()) {
            MindMapNode found = findNode(child, nodeId);
            if (found != null) {
                return found;
            }
        }
        return null;
    }
}