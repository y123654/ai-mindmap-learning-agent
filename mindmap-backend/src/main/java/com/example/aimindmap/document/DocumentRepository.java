package com.example.aimindmap.document;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DocumentRepository extends JpaRepository<DocumentEntity, Long> {
    List<DocumentEntity> findByStatusOrderByUpdatedAtDesc(String status);

    List<DocumentEntity> findByStatusAndUserIdOrderByUpdatedAtDesc(String status, Long userId);

    Optional<DocumentEntity> findByIdAndUserId(Long id, Long userId);
}