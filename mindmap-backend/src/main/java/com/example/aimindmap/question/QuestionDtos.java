package com.example.aimindmap.question;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.time.Instant;
import java.util.List;

public final class QuestionDtos {

    private QuestionDtos() {
    }

    public record GenerateQuestionsRequest(
            @NotBlank(message = "学科不能为空") String subject,
            String grade,
            @NotEmpty(message = "至少选择一种题型") List<@NotBlank String> questionTypes,
            @NotBlank(message = "难度不能为空") String difficulty,
            @Min(value = 1, message = "题目数量至少为 1")
            @Max(value = 10, message = "单次最多生成 10 道题")
            Integer count,
            List<String> nodeIds
    ) {
        public int safeCount() {
            return count == null ? 5 : count;
        }
    }

    /** 修订第 44 条：id / mindmapId 对外为混淆字符串。 */
    public record QuestionResponse(
            String id,
            String mindmapId,
            List<String> nodeIds,
            String subject,
            String grade,
            String questionType,
            String difficulty,
            String stem,
            Object options,
            Object answer,
            String explanation,
            String aiModel,
            String status,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    /** 修订第 44 条：资源 id 对外为混淆字符串。 */
    public record QuestionListItemResponse(
            String id,
            String mindmapId,
            String documentId,
            String documentTitle,
            String subject,
            String grade,
            String questionType,
            String difficulty,
            String stem,
            Object options,
            Object answer,
            String explanation,
            Object nodeIds,
            String status,
            java.time.Instant createdAt,
            java.time.Instant updatedAt
    ) {
    }

    /** 修订第 44 条：jobId / mindmapId / resultQuestionIds 对外为混淆字符串。 */
    public record QuestionGenerationJobResponse(
            String jobId,
            String mindmapId,
            String status,
            Object request,
            List<String> resultQuestionIds,
            String errorCode,
            String errorMessage,
            Instant createdAt,
            Instant completedAt
    ) {
    }
}