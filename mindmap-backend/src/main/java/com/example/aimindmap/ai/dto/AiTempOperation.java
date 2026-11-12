package com.example.aimindmap.ai.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * v1.1 §9.1 AI 临时操作。ID 使用 tmp_ 前缀，正式 ID 一律由后端生成。
 * sourceBlockId 为后端要求 AI 回填的溯源块 ID（§9.1 之外的增量字段，仅用于服务端校验）。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AiTempOperation(
        String type,
        String clientTempId,
        String parentClientTempId,
        String parentNodeId,
        String nodeId,
        String afterNodeId,
        AiTempNode node,
        AiTempFields fields,
        String reason
) {
}