package com.example.aimindmap.mindmap;

import com.example.aimindmap.mindmap.graph.MindMapNode;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * v1.1 方案 §9.2 后端正式操作。
 *
 * @param type        操作类型（序列化为方案中的小写下划线形式）
 * @param nodeId      目标节点正式 ID（add_node 时为新节点 ID，update_node 时为被修改节点 ID）
 * @param parentId    新增节点的父节点 ID；根节点为 null
 * @param afterNodeId 同级前一个节点 ID，用于保持 Markdown 顺序；排末尾时为 null
 * @param node        操作对应的完整节点数据
 * @param reason      产生原因
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ValidatedOperation(
        String type,
        String nodeId,
        String parentId,
        String afterNodeId,
        MindMapNode node,
        String reason
) {
    public static ValidatedOperation add(String nodeId, String parentId, String afterNodeId,
                                         MindMapNode node, String reason) {
        return new ValidatedOperation(OperationType.ADD_NODE.wire(), nodeId, parentId, afterNodeId, node, reason);
    }

    public static ValidatedOperation update(String nodeId, MindMapNode node, String reason) {
        return new ValidatedOperation(OperationType.UPDATE_NODE.wire(), nodeId, null, null, node, reason);
    }

    /** §9.3：只把节点标记为 removed_suggested，不真正删除，等用户接受或保留。 */
    public static ValidatedOperation proposeRemove(MindMapNode node, String reason) {
        return new ValidatedOperation(OperationType.PROPOSE_REMOVE_SOURCE_NODE.wire(),
                node.getId(), null, null, node, reason);
    }
}