package com.example.aimindmap.document;

import com.example.aimindmap.common.error.ApiException;
import com.example.aimindmap.mindmap.DocumentLockRegistry;
import com.example.aimindmap.mindmap.MindMapEntity;
import com.example.aimindmap.mindmap.MindMapRepository;
import com.example.aimindmap.mindmap.SyncJobRepository;
import com.example.aimindmap.mindmap.SyncStatus;
import jakarta.transaction.Transactional;
import java.util.List;
import com.example.aimindmap.vectorindex.VectorIndexTaskService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);

    private static final String EMPTY_GRAPH_JSON = """
            {
              "rootId": null,
              "version": 1,
              "tree": null
            }
            """;

    private final DocumentRepository documentRepository;
    private final MindMapRepository mindMapRepository;
    private final SyncJobRepository syncJobRepository;
    private final DocumentLockRegistry lockRegistry;
    private final VectorIndexTaskService vectorIndexTaskService;

    public DocumentService(DocumentRepository documentRepository,
                           MindMapRepository mindMapRepository,
                           SyncJobRepository syncJobRepository,
                           DocumentLockRegistry lockRegistry,
                           VectorIndexTaskService vectorIndexTaskService) {
        this.documentRepository = documentRepository;
        this.mindMapRepository = mindMapRepository;
        this.syncJobRepository = syncJobRepository;
        this.lockRegistry = lockRegistry;
        this.vectorIndexTaskService = vectorIndexTaskService;
    }

    @Transactional
    public DocumentEntity createDocument(String title, Long userId) {
        DocumentEntity document = new DocumentEntity();
        document.setUserId(userId);
        document.setTitle(normalizeTitle(title));
        document.setContent("");
        document.setContentVersion(1);

        DocumentEntity savedDocument = documentRepository.save(document);

        MindMapEntity mindMap = new MindMapEntity();
        mindMap.setUserId(userId);
        mindMap.setDocumentId(savedDocument.getId());
        mindMap.setGraph(EMPTY_GRAPH_JSON);
        mindMap.setGraphVersion(1);
        mindMap.setSyncStatus("idle");
        mindMapRepository.save(mindMap);

        vectorIndexTaskService.enqueueDocument(userId, savedDocument.getId());

        return savedDocument;
    }

    /** 阶段 5：用 PDF 生成的 Markdown 草稿直接新建一篇笔记（含空思维导图），内容版本仍为 1。 */
    @Transactional
    public DocumentEntity createDocumentFromDraft(String title, String markdown, Long userId) {
        DocumentEntity document = createDocument(title, userId);
        document.setContent(markdown == null ? "" : markdown);
        DocumentEntity saved = documentRepository.save(document);
        vectorIndexTaskService.enqueueDocument(userId, saved.getId());
        return saved;
    }

    @Transactional
    public DocumentEntity saveContent(Long id, String content, Integer expectedVersion) {
        synchronized (lockRegistry.lockFor(id)) {
            // §1/§14.3：同步或动画期间前后端都锁定，拒绝保存
            boolean activeJob = !syncJobRepository
                    .findByDocumentIdAndStatusInOrderByIdDesc(id, SyncStatus.ACTIVE)
                    .isEmpty();
            if (activeJob) {
                throw ApiException.conflict("DOCUMENT_LOCKED",
                        "思维导图正在同步或播放动画，请完成后再保存 Markdown");
            }

            DocumentEntity document = getDocumentOrThrow(id);
            if (!"active".equals(document.getStatus())) {
                throw ApiException.notFound("文档不存在：" + id);
            }
            if (!document.getContentVersion().equals(expectedVersion)) {
                throw ApiException.conflict("VERSION_CONFLICT",
                        "文档版本不一致，请刷新后重试。服务器版本：" + document.getContentVersion());
            }

            document.setContent(content == null ? "" : content);
            document.setContentVersion(document.getContentVersion() + 1);
            document.touch();
            DocumentEntity saved = documentRepository.save(document);
            vectorIndexTaskService.enqueueDocument(saved.getUserId(), saved.getId());
            return saved;
        }
    }

    @Transactional
    public DocumentEntity getDocument(Long id) {
        DocumentEntity document = getDocumentOrThrow(id);
        // 修订第 23 条：软删除后不能再读取详情
        if (!"active".equals(document.getStatus())) {
            throw ApiException.notFound("文档不存在：" + id);
        }
        return document;
    }

    /** 修订第 23 条：重命名笔记。归属由 Controller 层 OwnershipGuard 校验。 */
    @Transactional
    public DocumentEntity renameDocument(Long id, String title) {
        DocumentEntity document = getDocumentOrThrow(id);
        if (!"active".equals(document.getStatus())) {
            throw ApiException.notFound("文档不存在：" + id);
        }
        document.setTitle(normalizeTitle(title));
        document.touch();
        DocumentEntity saved = documentRepository.save(document);
        vectorIndexTaskService.enqueueDocument(saved.getUserId(), saved.getId());
        return saved;
    }

    /**
     * 修订第 23 条：软删除笔记（status=deleted）。
     *
     * <p>{@code mindmaps} 行保留（便于将来恢复/重建导图结构），但向量要一起清理：
     * chunk 向量（{@code enqueueDocumentDeletion}）和该导图的全部 node 向量
     * （{@code enqueueMindmapDeletionByDocument}）都会投递 DELETE，
     * 否则已删笔记仍会被 AI 助手检索到。</p>
     */
    @Transactional
    public void deleteDocument(Long id) {
        synchronized (lockRegistry.lockFor(id)) {
            boolean activeJob = !syncJobRepository
                    .findByDocumentIdAndStatusInOrderByIdDesc(id, SyncStatus.ACTIVE)
                    .isEmpty();
            if (activeJob) {
                throw ApiException.conflict("DOCUMENT_LOCKED",
                        "思维导图正在同步或播放动画，请完成后再删除笔记");
            }
            DocumentEntity document = getDocumentOrThrow(id);
            document.setStatus("deleted");
            document.touch();
            documentRepository.save(document);
            vectorIndexTaskService.enqueueDocumentDeletion(document.getUserId(), document.getId());
            // 缺陷修复（2026-11-04）：只删 chunk 会留下整图节点向量孤儿，且仍会被检索命中
            int nodeTasks = vectorIndexTaskService.enqueueMindmapDeletionByDocument(
                    document.getUserId(), document.getId());
            if (nodeTasks > 0) {
                log.info("软删除文档 {} 时一并入队 {} 个导图节点向量删除", document.getId(), nodeTasks);
            }
        }
    }

    @Transactional
    public List<DocumentEntity> listDocuments(Long userId) {
        return documentRepository.findByStatusAndUserIdOrderByUpdatedAtDesc("active", userId);
    }

    private DocumentEntity getDocumentOrThrow(Long id) {
        return documentRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("文档不存在：" + id));
    }

    private String normalizeTitle(String title) {
        if (title == null || title.trim().isBlank()) {
            return "未命名文档";
        }
        String trimmed = title.trim();
        return trimmed.length() > 255 ? trimmed.substring(0, 255) : trimmed;
    }
}
