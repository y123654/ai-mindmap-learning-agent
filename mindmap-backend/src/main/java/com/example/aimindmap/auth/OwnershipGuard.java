package com.example.aimindmap.auth;

import com.example.aimindmap.common.error.ApiException;
import com.example.aimindmap.document.DocumentRepository;
import com.example.aimindmap.mindmap.MindMapRepository;
import com.example.aimindmap.mindmap.SyncJobRepository;
import com.example.aimindmap.question.QuestionGenerationJobRepository;
import com.example.aimindmap.question.QuestionRepository;
import org.springframework.stereotype.Component;

/**
 * v1.1 修订第 16 条：所有业务接口在 Controller 边界校验资源归属当前登录用户。
 * 不属于当前用户时统一按 404 处理，避免泄露资源是否存在。
 */
@Component
public class OwnershipGuard {

    private final DocumentRepository documentRepository;
    private final MindMapRepository mindMapRepository;
    private final SyncJobRepository syncJobRepository;
    private final QuestionRepository questionRepository;
    private final QuestionGenerationJobRepository questionJobRepository;

    public OwnershipGuard(DocumentRepository documentRepository,
                          MindMapRepository mindMapRepository,
                          SyncJobRepository syncJobRepository,
                          QuestionRepository questionRepository,
                          QuestionGenerationJobRepository questionJobRepository) {
        this.documentRepository = documentRepository;
        this.mindMapRepository = mindMapRepository;
        this.syncJobRepository = syncJobRepository;
        this.questionRepository = questionRepository;
        this.questionJobRepository = questionJobRepository;
    }

    public void requireDocument(Long documentId, Long userId) {
        documentRepository.findByIdAndUserId(documentId, userId)
                .orElseThrow(() -> ApiException.notFound("文档不存在或无权访问：" + documentId));
    }

    public void requireMindmap(Long mindmapId, Long userId) {
        mindMapRepository.findByIdAndUserId(mindmapId, userId)
                .orElseThrow(() -> ApiException.notFound("思维导图不存在或无权访问：" + mindmapId));
    }

    public void requireMindmapByDocument(Long documentId, Long userId) {
        mindMapRepository.findByDocumentIdAndUserId(documentId, userId)
                .orElseThrow(() -> ApiException.notFound("思维导图不存在或无权访问，文档 ID：" + documentId));
    }

    public void requireSyncJob(Long jobId, Long userId) {
        Long documentId = syncJobRepository.findById(jobId)
                .orElseThrow(() -> ApiException.notFound("同步任务不存在：" + jobId))
                .getDocumentId();
        requireDocument(documentId, userId);
    }

    public void requireQuestion(Long questionId, Long userId) {
        Long mindmapId = questionRepository.findById(questionId)
                .orElseThrow(() -> ApiException.notFound("题目不存在：" + questionId))
                .getMindmapId();
        requireMindmap(mindmapId, userId);
    }

    public void requireQuestionJob(Long jobId, Long userId) {
        Long mindmapId = questionJobRepository.findById(jobId)
                .orElseThrow(() -> ApiException.notFound("题目生成任务不存在：" + jobId))
                .getMindmapId();
        requireMindmap(mindmapId, userId);
    }
}