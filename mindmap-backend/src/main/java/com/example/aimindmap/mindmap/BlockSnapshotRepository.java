package com.example.aimindmap.mindmap;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BlockSnapshotRepository extends JpaRepository<BlockSnapshotEntity, Long> {

    @Query("select max(s.documentVersion) from BlockSnapshotEntity s where s.documentId = :documentId")
    Integer findLatestVersion(@Param("documentId") Long documentId);

    List<BlockSnapshotEntity> findByDocumentIdAndDocumentVersionOrderByBlockOrderAsc(
            Long documentId, Integer documentVersion);

    @Modifying
    void deleteByDocumentIdAndDocumentVersion(Long documentId, Integer documentVersion);
}