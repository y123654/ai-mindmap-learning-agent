package com.example.aimindmap.ai.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * AI 临时节点内容。AI 不能指定正式 id、保护标志和状态。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AiTempNode(
        String label,
        String summary,
        String sourceType,
        List<String> sourcePath,
        String sourceBlockId
) {
}