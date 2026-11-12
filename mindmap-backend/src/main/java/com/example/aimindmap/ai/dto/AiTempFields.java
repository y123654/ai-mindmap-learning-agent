package com.example.aimindmap.ai.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * update_node 时 AI 可通过 fields 给出新标题/摘要（§12.1）。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AiTempFields(
        String label,
        String summary
) {
}