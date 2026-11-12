package com.example.aimindmap.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.aimindmap.common.error.ApiException;
import com.example.aimindmap.mindmap.DocumentLockRegistry;
import com.example.aimindmap.mindmap.MindMapRepository;
import com.example.aimindmap.mindmap.SyncJobEntity;
import com.example.aimindmap.mindmap.SyncJobRepository;
import com.example.aimindmap.mindmap.SyncStatus;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

class DocumentServiceTest {

    private final DocumentRepository documentRepository = mock(DocumentRepository.class);
    private final MindMapRepository mindMapRepository = mock(MindMapRepository.class);
    private final SyncJobRepository syncJobRepository = mock(SyncJobRepository.class);
    private final DocumentLockRegistry lockRegistry = new DocumentLockRegistry();
    private final com.example.aimindmap.vectorindex.VectorIndexTaskService vectorIndexTaskService =
            mock(com.example.aimindmap.vectorindex.VectorIndexTaskService.class);
    private final DocumentService service = new DocumentService(
            documentRepository, mindMapRepository, syncJobRepository, lockRegistry,
            vectorIndexTaskService);

    @Test
    void renameDocumentTrimsAndPersistsTitle() {
        DocumentEntity document = activeDocument(1L, "旧标题");
        when(documentRepository.findById(1L)).thenReturn(Optional.of(document));
        when(documentRepository.save(any(DocumentEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(syncJobRepository.findByDocumentIdAndStatusInOrderByIdDesc(eq(1L), eq(SyncStatus.ACTIVE)))
                .thenReturn(List.of());

        DocumentEntity result = service.renameDocument(1L, "  光合作用笔记  ");

        assertThat(result.getTitle()).isEqualTo("光合作用笔记");
        verify(documentRepository).save(document);
    }

    @Test
    void renameBlankTitleFallsBackToDefault() {
        DocumentEntity document = activeDocument(1L, "旧标题");
        when(documentRepository.findById(1L)).thenReturn(Optional.of(document));
        when(documentRepository.save(any(DocumentEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(syncJobRepository.findByDocumentIdAndStatusInOrderByIdDesc(eq(1L), eq(SyncStatus.ACTIVE)))
                .thenReturn(List.of());

        DocumentEntity result = service.renameDocument(1L, "   ");

        assertThat(result.getTitle()).isEqualTo("未命名文档");
    }

    @Test
    void deleteDocumentIsSoftDelete() {
        DocumentEntity document = activeDocument(1L, "要删除的笔记");
        when(documentRepository.findById(1L)).thenReturn(Optional.of(document));
        when(syncJobRepository.findByDocumentIdAndStatusInOrderByIdDesc(eq(1L), eq(SyncStatus.ACTIVE)))
                .thenReturn(List.of());

        service.deleteDocument(1L);

        ArgumentCaptor<DocumentEntity> captor = ArgumentCaptor.forClass(DocumentEntity.class);
        verify(documentRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("deleted");
    }

    /**
     * 缺陷修复（2026-11-04，P1/P2）：软删除必须把该文档所属导图的节点向量一起清掉，
     * 且必须在文档状态已经落成 deleted 之后才入队（否则异步 worker 可能先于状态更新读到 active）。
     */
    @Test
    void deleteDocumentEnqueuesMindmapNodeDeletionAfterStatusMarkedDeleted() {
        DocumentEntity document = activeDocument(1L, "要删除的笔记");
        document.setUserId(7L);
        when(documentRepository.findById(1L)).thenReturn(Optional.of(document));
        when(syncJobRepository.findByDocumentIdAndStatusInOrderByIdDesc(eq(1L), eq(SyncStatus.ACTIVE)))
                .thenReturn(List.of());
        List<String> statusAtNodeEnqueue = new java.util.ArrayList<>();
        when(vectorIndexTaskService.enqueueMindmapDeletionByDocument(7L, 1L)).thenAnswer(invocation -> {
            statusAtNodeEnqueue.add(document.getStatus());
            return 3;
        });

        service.deleteDocument(1L);

        // 1) 调用顺序：先落库 deleted，再删 chunk 向量，最后删 node 向量
        InOrder inOrder = inOrder(documentRepository, vectorIndexTaskService);
        inOrder.verify(documentRepository).save(document);
        inOrder.verify(vectorIndexTaskService).enqueueDocumentDeletion(7L, 1L);
        inOrder.verify(vectorIndexTaskService).enqueueMindmapDeletionByDocument(7L, 1L);
        // 2) 入队那一刻文档状态已经是 deleted
        assertThat(document.getStatus()).isEqualTo("deleted");
        assertThat(statusAtNodeEnqueue).containsExactly("deleted");
    }

    @Test
    void deleteDocumentRejectedWhileSyncActive() {
        DocumentEntity document = activeDocument(1L, "同步中的笔记");
        when(documentRepository.findById(1L)).thenReturn(Optional.of(document));
        when(syncJobRepository.findByDocumentIdAndStatusInOrderByIdDesc(eq(1L), eq(SyncStatus.ACTIVE)))
                .thenReturn(List.of(mock(SyncJobEntity.class)));

        assertThatThrownBy(() -> service.deleteDocument(1L))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("DOCUMENT_LOCKED"));
        verify(documentRepository, never()).save(any());
    }

    @Test
    void getDeletedDocumentThrowsNotFound() {
        DocumentEntity document = activeDocument(1L, "已删除");
        document.setStatus("deleted");
        when(documentRepository.findById(1L)).thenReturn(Optional.of(document));

        assertThatThrownBy(() -> service.getDocument(1L))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getStatus().value()).isEqualTo(404));
    }

    private DocumentEntity activeDocument(Long id, String title) {
        DocumentEntity document = new DocumentEntity();
        document.setTitle(title);
        document.setStatus("active");
        document.setContent("");
        document.setContentVersion(1);
        try {
            var idField = DocumentEntity.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(document, id);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
        return document;
    }
}
