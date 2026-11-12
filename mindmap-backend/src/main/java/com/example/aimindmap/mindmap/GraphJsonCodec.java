package com.example.aimindmap.mindmap;

import com.example.aimindmap.mindmap.graph.MindMapGraph;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/**
 * mindmaps.graph JSONB 与 MindMapGraph 对象的转换。
 */
@Component
public class GraphJsonCodec {

    private final ObjectMapper objectMapper;

    public GraphJsonCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public MindMapGraph read(String json) {
        try {
            return objectMapper.readValue(json, MindMapGraph.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("思维导图 JSON 解析失败", e);
        }
    }

    public String write(MindMapGraph graph) {
        try {
            return objectMapper.writeValueAsString(graph);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("思维导图 JSON 序列化失败", e);
        }
    }
}