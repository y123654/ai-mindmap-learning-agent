package com.example.aimindmap.mindmap.graph;

import java.time.Instant;

/**
 * v1.1 方案 §7.3 图对象。数据库 mindmaps.graph 保存该对象的 JSON。
 */
public class MindMapGraph {

    private String rootId;
    private int version;
    private MindMapNode tree;
    private Instant updatedAt;

    public MindMapGraph() {
    }

    public MindMapGraph(String rootId, int version, MindMapNode tree, Instant updatedAt) {
        this.rootId = rootId;
        this.version = version;
        this.tree = tree;
        this.updatedAt = updatedAt;
    }

    public boolean isEmpty() {
        return tree == null || rootId == null;
    }

    public String getRootId() {
        return rootId;
    }

    public void setRootId(String rootId) {
        this.rootId = rootId;
    }

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    public MindMapNode getTree() {
        return tree;
    }

    public void setTree(MindMapNode tree) {
        this.tree = tree;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}