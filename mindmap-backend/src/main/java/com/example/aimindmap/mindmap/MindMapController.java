package com.example.aimindmap.mindmap;

import com.example.aimindmap.auth.AuthUser;
import com.example.aimindmap.auth.ExternalIdCodec;
import com.example.aimindmap.auth.OwnershipGuard;
import com.example.aimindmap.common.error.ApiException;
import com.example.aimindmap.document.DocumentEntity;
import com.example.aimindmap.document.DocumentRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/documents/{documentId}/mindmap")
public class MindMapController {

    private final MindMapRepository mindMapRepository;
    private final DocumentRepository documentRepository;
    private final ObjectMapper objectMapper;
    private final OwnershipGuard ownershipGuard;
    private final ExternalIdCodec idCodec;

    public MindMapController(MindMapRepository mindMapRepository,
                             DocumentRepository documentRepository,
                             ObjectMapper objectMapper,
                             OwnershipGuard ownershipGuard,
                             ExternalIdCodec idCodec) {
        this.mindMapRepository = mindMapRepository;
        this.documentRepository = documentRepository;
        this.objectMapper = objectMapper;
        this.ownershipGuard = ownershipGuard;
        this.idCodec = idCodec;
    }

    @GetMapping
    public MindMapDtos.MindMapResponse getMindMap(@AuthenticationPrincipal AuthUser user,
                                                  @PathVariable String documentId) throws JsonProcessingException {
        Long internalDocumentId = idCodec.decode(documentId);
        ownershipGuard.requireMindmapByDocument(internalDocumentId, user.userId());
        // 缺陷修复（2026-11-04）：软删除的文档详情已 404，导图接口必须同口径，
        // 否则"文档看不见了、导图还能按 id 拿到全部节点"。
        DocumentEntity document = documentRepository.findById(internalDocumentId)
                .orElseThrow(() -> ApiException.notFound("思维导图不存在，文档 ID：" + internalDocumentId));
        if (!"active".equals(document.getStatus())) {
            throw ApiException.notFound("思维导图不存在，文档 ID：" + internalDocumentId);
        }
        MindMapEntity mindMap = mindMapRepository.findByDocumentId(internalDocumentId)
                .orElseThrow(() -> ApiException.notFound("思维导图不存在，文档 ID：" + internalDocumentId));

        return new MindMapDtos.MindMapResponse(
                idCodec.encode(mindMap.getId()),
                idCodec.encode(mindMap.getDocumentId()),
                objectMapper.readValue(mindMap.getGraph(), Object.class),
                mindMap.getGraphVersion(),
                mindMap.getSyncStatus(),
                mindMap.getUpdatedAt()
        );
    }
}