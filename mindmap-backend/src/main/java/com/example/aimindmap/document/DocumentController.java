package com.example.aimindmap.document;

import com.example.aimindmap.auth.AuthUser;
import com.example.aimindmap.auth.ExternalIdCodec;
import com.example.aimindmap.auth.OwnershipGuard;
import com.example.aimindmap.document.DocumentDtos.CreateDocumentRequest;
import com.example.aimindmap.document.DocumentDtos.DocumentResponse;
import com.example.aimindmap.document.DocumentDtos.RenameDocumentRequest;
import com.example.aimindmap.document.DocumentDtos.SaveContentRequest;
import com.example.aimindmap.document.DocumentDtos.SaveContentResponse;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private final DocumentService documentService;
    private final OwnershipGuard ownershipGuard;
    private final ExternalIdCodec idCodec;

    public DocumentController(DocumentService documentService,
                              OwnershipGuard ownershipGuard,
                              ExternalIdCodec idCodec) {
        this.documentService = documentService;
        this.ownershipGuard = ownershipGuard;
        this.idCodec = idCodec;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public DocumentResponse createDocument(@AuthenticationPrincipal AuthUser user,
                                           @RequestBody(required = false) CreateDocumentRequest request) {
        String title = request == null ? null : request.title();
        return DocumentResponse.from(documentService.createDocument(title, user.userId()), idCodec);
    }

    @GetMapping
    public List<DocumentDtos.DocumentSummaryResponse> listDocuments(@AuthenticationPrincipal AuthUser user) {
        return documentService.listDocuments(user.userId()).stream()
                .map(entity -> DocumentDtos.DocumentSummaryResponse.from(entity, idCodec))
                .toList();
    }

    @GetMapping("/{id}")
    public DocumentResponse getDocument(@AuthenticationPrincipal AuthUser user,
                                        @PathVariable String id) {
        Long documentId = idCodec.decode(id);
        ownershipGuard.requireDocument(documentId, user.userId());
        return DocumentResponse.from(documentService.getDocument(documentId), idCodec);
    }

    @PutMapping("/{id}/content")
    public SaveContentResponse saveContent(@AuthenticationPrincipal AuthUser user,
                                           @PathVariable String id,
                                           @Valid @RequestBody SaveContentRequest request) {
        Long documentId = idCodec.decode(id);
        ownershipGuard.requireDocument(documentId, user.userId());
        var saved = documentService.saveContent(documentId, request.content(), request.expectedVersion());
        return new SaveContentResponse(idCodec.encode(saved.getId()), saved.getContentVersion(), Instant.now());
    }

    /** 修订第 23 条：重命名笔记。 */
    @PatchMapping("/{id}")
    public DocumentResponse renameDocument(@AuthenticationPrincipal AuthUser user,
                                           @PathVariable String id,
                                           @Valid @RequestBody(required = false) RenameDocumentRequest request) {
        Long documentId = idCodec.decode(id);
        ownershipGuard.requireDocument(documentId, user.userId());
        String title = request == null ? null : request.title();
        return DocumentResponse.from(documentService.renameDocument(documentId, title), idCodec);
    }

    /** 修订第 23 条：软删除笔记。 */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteDocument(@AuthenticationPrincipal AuthUser user,
                                               @PathVariable String id) {
        Long documentId = idCodec.decode(id);
        ownershipGuard.requireDocument(documentId, user.userId());
        documentService.deleteDocument(documentId);
        return ResponseEntity.noContent().build();
    }
}