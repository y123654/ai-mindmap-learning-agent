package com.example.aimindmap.ai.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AiQuestionResponse(
        List<AiQuestion> questions
) {
    public List<AiQuestion> safeQuestions() {
        return questions == null ? List.of() : questions;
    }
}
