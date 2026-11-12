package com.example.aimindmap.ai.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AiQuestion(
        String questionType,
        String difficulty,
        String stem,
        List<AiQuestionOption> options,
        JsonNode answer,
        String explanation,
        List<String> nodeIds
) {
}
