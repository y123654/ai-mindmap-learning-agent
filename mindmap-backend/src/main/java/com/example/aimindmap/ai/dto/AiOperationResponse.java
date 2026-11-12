package com.example.aimindmap.ai.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * v1.1 §12.1 AI 返回的最外层结构：{ "operations": [...] }。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AiOperationResponse(
        List<AiTempOperation> operations
) {
    public List<AiTempOperation> safeOperations() {
        return operations == null ? List.of() : operations;
    }
}