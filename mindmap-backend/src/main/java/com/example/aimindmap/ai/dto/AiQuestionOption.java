package com.example.aimindmap.ai.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AiQuestionOption(
        String key,
        String text
) {
}
