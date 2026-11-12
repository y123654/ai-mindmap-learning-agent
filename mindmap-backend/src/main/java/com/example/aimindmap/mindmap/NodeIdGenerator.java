package com.example.aimindmap.mindmap;

import com.example.aimindmap.mindmap.graph.GraphIndex;
import com.example.aimindmap.mindmap.graph.MindMapGraph;
import org.springframework.stereotype.Component;

/**
 * 正式节点 ID 生成器（v1.1 §9.1：正式 ID 只能由后端生成）。
 * 格式与 §7.1 示例对齐：node_001，序号超过 3 位时自然变长。
 */
public class NodeIdGenerator {

    private static final String PREFIX = "node_";

    private int nextSeq;

    private NodeIdGenerator(int nextSeq) {
        this.nextSeq = nextSeq;
    }

    public static NodeIdGenerator basedOn(MindMapGraph graph) {
        int max = 0;
        if (graph != null && graph.getTree() != null) {
            for (var node : GraphIndex.of(graph).allNodes()) {
                String id = node.getId();
                if (id != null && id.startsWith(PREFIX)) {
                    try {
                        max = Math.max(max, Integer.parseInt(id.substring(PREFIX.length())));
                    } catch (NumberFormatException ignored) {
                        // 非本规则生成的 ID（例如手动迁移数据），跳过
                    }
                }
            }
        }
        return new NodeIdGenerator(max);
    }

    public synchronized String next() {
        nextSeq++;
        return PREFIX + String.format("%03d", nextSeq);
    }
}