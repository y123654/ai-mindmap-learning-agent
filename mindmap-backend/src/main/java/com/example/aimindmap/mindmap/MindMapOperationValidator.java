package com.example.aimindmap.mindmap;

import com.example.aimindmap.common.error.ApiException;
import com.example.aimindmap.mindmap.graph.GraphIndex;
import com.example.aimindmap.mindmap.graph.MindMapGraph;
import com.example.aimindmap.mindmap.graph.MindMapNode;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * v1.1 §9.4 冲突保护规则（校验任一操作失败，本次同步整体失败，不发布半成品）。
 */
@Component
public class MindMapOperationValidator {

    private static final Logger log = LoggerFactory.getLogger(MindMapOperationValidator.class);

    /** §9.4 第 8 条：单次新增节点默认最多 100 个。 */
    public static final int MAX_NEW_NODES = 100;
    /** §9.4 第 8 条：硬上限可配置为 200；三级裁剪后的大导图按该上限做最终保护。 */
    public static final int MAX_NEW_NODES_HARD = 200;
    /** §9.4 第 9 条：节点层级最多 6 层（根为第 1 层，最大深度 5）。 */
    public static final int MAX_DEPTH = 5;
    /** 节点标题硬上限（§9.4 第 10 条“建议不超过 20”为前端软提示，后端防坏数据）。 */
    public static final int MAX_LABEL_LENGTH = 100;
    /** 节点摘要硬上限（§9.4 第 11 条“建议不超过 60”为 AI 软提示）。 */
    public static final int MAX_SUMMARY_LENGTH = 500;

    public void validate(List<ValidatedOperation> operations, MindMapGraph graph) {
        validate(operations, graph, MAX_NEW_NODES);
    }

    /**
     * @param maxNewNodes 本次校验允许的单次新增上限（常规 100，三级裁剪后 200）。
     */
    public void validate(List<ValidatedOperation> operations, MindMapGraph graph, int maxNewNodes) {
        if (operations == null || operations.isEmpty()) {
            return;
        }
        GraphIndex currentIndex = GraphIndex.of(graph);
        Set<String> batchIds = new HashSet<>();
        Map<String, Integer> batchDepth = new HashMap<>();
        int addCount = 0;

        for (ValidatedOperation operation : operations) {
            if (operation == null || operation.type() == null) {
                throw fail("存在空操作");
            }
            if ("add_node".equals(operation.type())) {
                validateAdd(operation, graph, currentIndex, batchIds, batchDepth);
                addCount++;
            } else if ("update_node".equals(operation.type())) {
                validateUpdate(operation, currentIndex);
            } else if ("propose_remove_source_node".equals(operation.type())) {
                // 阶段 3：Markdown 删除内容只产生删除建议（§9.3），不真正删除
                validateProposeRemove(operation, graph, currentIndex);
            } else {
                throw fail("不支持的操作类型：" + operation.type());
            }
        }

        if (addCount > maxNewNodes) {
            // 2026-09-17：超过最大节点限制必须在后端日志可见，便于排查“同步失败”原因
            log.warn("操作校验未通过：本次同步新增节点 {} 个，超过本次上限 {} 个（§9.4，"
                            + "常规上限 {}、裁剪后硬上限 {}）；出现本日志请检查文档前三级节点数量，"
                            + "整批操作不发布。本次操作总数={}",
                    addCount, maxNewNodes, MAX_NEW_NODES, MAX_NEW_NODES_HARD, operations.size());
            throw fail("单次同步新增节点 " + addCount + " 个，超过上限 " + maxNewNodes + " 个（§9.4）");
        }
    }

    private void validateAdd(ValidatedOperation op,
                             MindMapGraph graph,
                             GraphIndex currentIndex,
                             Set<String> batchIds,
                             Map<String, Integer> batchDepth) {
        MindMapNode node = op.node();
        String nodeId = op.nodeId();
        if (node == null || nodeId == null || nodeId.isBlank()) {
            throw fail("add_node 缺少节点或节点 ID");
        }
        if (!nodeId.equals(node.getId())) {
            throw fail("add_node 的 nodeId 与节点 id 不一致：" + nodeId);
        }
        if (node.getLabel() == null || node.getLabel().isBlank()) {
            throw fail("节点标题不能为空：" + nodeId);
        }
        if (node.getLabel().length() > MAX_LABEL_LENGTH) {
            throw fail("节点标题超过 " + MAX_LABEL_LENGTH + " 字符：" + node.getLabel());
        }
        if (currentIndex.containsId(nodeId) || batchIds.contains(nodeId)) {
            throw fail("节点 ID 重复：" + nodeId);
        }

        int depth;
        if (op.parentId() == null) {
            // 只有空图的第一个操作可以作为根节点（§8.6 根节点规则由 RuleOperationBuilder 决定）
            if (!graph.isEmpty() || !batchIds.isEmpty()) {
                throw fail("已有根节点，新节点必须指定父节点：" + nodeId);
            }
            depth = 0;
        } else {
            Integer parentBatchDepth = batchDepth.get(op.parentId());
            if (parentBatchDepth != null) {
                depth = parentBatchDepth + 1;
            } else {
                MindMapNode parent = currentIndex.findById(op.parentId());
                if (parent == null) {
                    throw fail("父节点不存在：" + op.parentId());
                }
                depth = currentIndex.depth(op.parentId()) + 1;
            }
        }
        if (depth > MAX_DEPTH) {
            throw fail("节点层级超过 6 层（§9.4）：" + node.getLabel());
        }
        if (op.afterNodeId() != null
                && !currentIndex.containsId(op.afterNodeId())
                && !batchIds.contains(op.afterNodeId())) {
            throw fail("afterNodeId 指向的节点不存在：" + op.afterNodeId());
        }
        batchIds.add(nodeId);
        batchDepth.put(nodeId, depth);
    }

    private void validateProposeRemove(ValidatedOperation op,
                                       MindMapGraph graph,
                                       GraphIndex currentIndex) {
        String nodeId = op.nodeId();
        if (nodeId == null || nodeId.isBlank()) {
            throw fail("propose_remove_source_node 缺少 nodeId");
        }
        MindMapNode existing = currentIndex.findById(nodeId);
        if (existing == null) {
            throw fail("建议删除的节点不存在：" + nodeId);
        }
        if (graph.getRootId() != null && graph.getRootId().equals(nodeId)) {
            // §9.4 第 7 条：根节点不允许删除
            throw fail("根节点不允许删除：" + nodeId);
        }
        if ("manual".equals(existing.getSourceType()) || existing.isManualLocked()
                || existing.isManuallyEdited()) {
            throw fail("受手动保护的节点不能建议删除：" + nodeId);
        }
        if (!"active".equals(existing.getStatus())) {
            throw fail("节点已处于删除建议状态，不能重复建议：" + nodeId);
        }
        if (op.node() == null || !nodeId.equals(op.node().getId())) {
            throw fail("propose_remove_source_node 的节点数据与 nodeId 不一致：" + nodeId);
        }
    }
    private void validateUpdate(ValidatedOperation op, GraphIndex currentIndex) {
        String nodeId = op.nodeId();
        MindMapNode node = op.node();
        if (node == null || nodeId == null || !nodeId.equals(node.getId())) {
            throw fail("update_node 缺少一致的节点 ID");
        }
        MindMapNode existing = currentIndex.findById(nodeId);
        if (existing == null) {
            throw fail("待修改节点不存在：" + nodeId);
        }
        if (node.getLabel() == null || node.getLabel().isBlank()) {
            throw fail("节点标题不能为空：" + nodeId);
        }
        if (node.getLabel().length() > MAX_LABEL_LENGTH) {
            throw fail("节点标题超过 " + MAX_LABEL_LENGTH + " 字符：" + node.getLabel());
        }
        // §9.4 第 1-4 条保护规则对规则同步同样适用：同步不能覆盖用户手动改过的节点
        if ("manual".equals(existing.getSourceType()) || existing.isManualLocked()) {
            throw fail("手动节点受保护，不能被同步覆盖：" + nodeId);
        }
        if (existing.isManuallyEdited()) {
            throw fail("用户手动改过的节点受保护，不能被同步覆盖：" + nodeId);
        }
    }

    private static ApiException fail(String message) {
        return ApiException.badRequest("VALIDATION_FAILED", message);
    }
}