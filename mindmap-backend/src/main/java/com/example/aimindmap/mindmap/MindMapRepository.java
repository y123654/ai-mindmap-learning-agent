package com.example.aimindmap.mindmap;

import java.util.Optional;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MindMapRepository extends JpaRepository<MindMapEntity, Long> {
    List<MindMapEntity> findByUserId(Long userId);

    Optional<MindMapEntity> findByDocumentId(Long documentId);

    Optional<MindMapEntity> findByIdAndUserId(Long id, Long userId);

    Optional<MindMapEntity> findByDocumentIdAndUserId(Long documentId, Long userId);
}
