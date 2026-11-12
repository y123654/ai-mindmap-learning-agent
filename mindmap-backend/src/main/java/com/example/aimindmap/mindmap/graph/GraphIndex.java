package com.example.aimindmap.mindmap.graph;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 思维导图树的只读索引：按正式节点 ID、稳定块 ID、sourcePath 查找节点，并提供深度信息。
 */
public class GraphIndex {

    private final Map<String, MindMapNode> byId = new HashMap<>();
    private final Map<String, MindMapNode> byBlockId = new HashMap<>();
    private final Map<String, MindMapNode> byAnchor = new HashMap<>();
    private final Map<String, MindMapNode> parentById = new HashMap<>();
    private final Map<String, Integer> depthById = new HashMap<>();

    private GraphIndex() {
    }

    public static GraphIndex of(MindMapGraph graph) {
        GraphIndex index = new GraphIndex();
        if (graph == null || graph.getTree() == null) {
            return index;
        }
        Deque<MindMapNode> queue = new ArrayDeque<>();
        queue.add(graph.getTree());
        index.depthById.put(graph.getTree().getId(), 0);
        while (!queue.isEmpty()) {
            MindMapNode node = queue.poll();
            index.byId.put(node.getId(), node);
            if (node.getSourceBlockId() != null) {
                index.byBlockId.put(node.getSourceBlockId(), node);
            }
            if (node.getSourceAnchor() != null) {
                index.byAnchor.put(node.getSourceAnchor(), node);
            }
            int childDepth = index.depthById.get(node.getId()) + 1;
            for (MindMapNode child : node.getChildren()) {
                index.parentById.put(child.getId(), node);
                index.depthById.put(child.getId(), childDepth);
                queue.add(child);
            }
        }
        return index;
    }

    public MindMapNode findById(String nodeId) {
        return byId.get(nodeId);
    }

    public MindMapNode findByBlockId(String blockId) {
        return blockId == null ? null : byBlockId.get(blockId);
    }

    public MindMapNode findByAnchor(String anchor) {
        return anchor == null ? null : byAnchor.get(anchor);
    }

    public MindMapNode findParent(String nodeId) {
        return parentById.get(nodeId);
    }

    public int depth(String nodeId) {
        return depthById.getOrDefault(nodeId, -1);
    }

    /** 按完整 sourcePath（含自身）查找标题类节点。 */
    public MindMapNode findBySourcePath(List<String> sourcePath) {
        if (sourcePath == null || sourcePath.isEmpty()) {
            return null;
        }
        for (MindMapNode node : byId.values()) {
            if (node.getSourcePath() != null && node.getSourcePath().equals(sourcePath)) {
                return node;
            }
        }
        return null;
    }

    public List<MindMapNode> allNodes() {
        return new ArrayList<>(byId.values());
    }

    public boolean containsId(String nodeId) {
        return byId.containsKey(nodeId);
    }
}