package com.example.aimindmap.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.aimindmap.common.error.ApiException;
import com.example.aimindmap.document.DocumentRepository;
import com.example.aimindmap.mindmap.MindMapRepository;
import com.example.aimindmap.mindmap.SyncJobEntity;
import com.example.aimindmap.mindmap.SyncJobRepository;
import com.example.aimindmap.question.QuestionGenerationJobRepository;
import com.example.aimindmap.question.QuestionRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class OwnershipGuardTest {

    private final DocumentRepository documentRepository = mock(DocumentRepository.class);
    private final MindMapRepository mindMapRepository = mock(MindMapRepository.class);
    private final SyncJobRepository syncJobRepository = mock(SyncJobRepository.class);
    private final QuestionRepository questionRepository = mock(QuestionRepository.class);
    private final QuestionGenerationJobRepository questionJobRepository =
            mock(QuestionGenerationJobRepository.class);
    private final OwnershipGuard guard = new OwnershipGuard(
            documentRepository, mindMapRepository, syncJobRepository,
            questionRepository, questionJobRepository);

    @Test
    void documentOwnedByAnotherUserIsTreatedAsNotFound() {
        when(documentRepository.findByIdAndUserId(9L, 1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> guard.requireDocument(9L, 1L))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus().value()).isEqualTo(404);
                    assertThat(ex.getCode()).isEqualTo("NOT_FOUND");
                });
    }

    @Test
    void syncJobOwnershipIsCheckedThroughItsDocument() {
        SyncJobEntity job = new SyncJobEntity();
        job.setDocumentId(9L);
        when(syncJobRepository.findById(3L)).thenReturn(Optional.of(job));
        when(documentRepository.findByIdAndUserId(9L, 1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> guard.requireSyncJob(3L, 1L))
                .isInstanceOf(ApiException.class);
    }
}
