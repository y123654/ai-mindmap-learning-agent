package com.example.aimindmap.question;

import com.example.aimindmap.auth.AuthUser;
import com.example.aimindmap.auth.ExternalIdCodec;
import com.example.aimindmap.auth.OwnershipGuard;
import com.example.aimindmap.question.QuestionDtos.GenerateQuestionsRequest;
import com.example.aimindmap.question.QuestionDtos.QuestionGenerationJobResponse;
import com.example.aimindmap.question.QuestionDtos.QuestionResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** v1.1 §11.5 阶段 4 题目接口。修订第 44 条：路径 id 为外部混淆 ID。 */
@RestController
public class QuestionController {

    private final QuestionService questionService;
    private final ObjectMapper objectMapper;
    private final OwnershipGuard ownershipGuard;
    private final ExternalIdCodec idCodec;

    public QuestionController(QuestionService questionService,
                              ObjectMapper objectMapper,
                              OwnershipGuard ownershipGuard,
                              ExternalIdCodec idCodec) {
        this.questionService = questionService;
        this.objectMapper = objectMapper;
        this.ownershipGuard = ownershipGuard;
        this.idCodec = idCodec;
    }

    @PostMapping("/api/mindmaps/{mindmapId}/question-generation-jobs")
    @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    public QuestionGenerationJobResponse createGenerationJob(
            @AuthenticationPrincipal AuthUser user,
            @PathVariable String mindmapId,
            @Valid @RequestBody GenerateQuestionsRequest request) {
        Long internalMindmapId = idCodec.decode(mindmapId);
        ownershipGuard.requireMindmap(internalMindmapId, user.userId());
        return toJobResponse(questionService.createGenerationJob(internalMindmapId, request));
    }

    @GetMapping("/api/question-generation-jobs/{jobId}")
    public QuestionGenerationJobResponse getJob(@AuthenticationPrincipal AuthUser user,
                                                @PathVariable String jobId) {
        Long internalJobId = idCodec.decode(jobId);
        ownershipGuard.requireQuestionJob(internalJobId, user.userId());
        return toJobResponse(questionService.getJob(internalJobId));
    }

    @GetMapping("/api/questions")
    public List<QuestionDtos.QuestionListItemResponse> listAllQuestions(@AuthenticationPrincipal AuthUser user) {
        return questionService.listQuestionsForUser(user.userId());
    }

    @GetMapping("/api/mindmaps/{mindmapId}/questions")
    public List<QuestionResponse> listQuestions(@AuthenticationPrincipal AuthUser user,
                                                @PathVariable String mindmapId) {
        Long internalMindmapId = idCodec.decode(mindmapId);
        ownershipGuard.requireMindmap(internalMindmapId, user.userId());
        return questionService.listQuestions(internalMindmapId).stream()
                .map(this::toQuestionResponse)
                .toList();
    }

    @PostMapping("/api/questions/{questionId}/regenerate")
    public QuestionResponse regenerateQuestion(@AuthenticationPrincipal AuthUser user,
                                               @PathVariable String questionId) {
        Long internalQuestionId = idCodec.decode(questionId);
        ownershipGuard.requireQuestion(internalQuestionId, user.userId());
        return toQuestionResponse(questionService.regenerateQuestion(internalQuestionId));
    }

    private QuestionGenerationJobResponse toJobResponse(QuestionGenerationJobEntity entity) {
        return new QuestionGenerationJobResponse(
                idCodec.encode(entity.getId()),
                idCodec.encode(entity.getMindmapId()),
                entity.getStatus(),
                readJson(entity.getRequest()),
                readLongList(entity.getResultQuestionIds()).stream().map(idCodec::encode).toList(),
                entity.getErrorCode(),
                entity.getErrorMessage(),
                entity.getCreatedAt(),
                entity.getCompletedAt()
        );
    }

    private QuestionResponse toQuestionResponse(QuestionEntity entity) {
        return new QuestionResponse(
                idCodec.encode(entity.getId()),
                idCodec.encode(entity.getMindmapId()),
                readStringList(entity.getNodeIds()),
                entity.getSubject(),
                entity.getGrade(),
                entity.getQuestionType(),
                entity.getDifficulty(),
                entity.getStem(),
                readJson(entity.getOptions()),
                readJson(entity.getAnswer()),
                entity.getExplanation(),
                entity.getAiModel(),
                entity.getStatus(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }

    private Object readJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, JsonNode.class);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private List<Long> readLongList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<Long>>() {
            });
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }

    private List<String> readStringList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {
            });
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }
}