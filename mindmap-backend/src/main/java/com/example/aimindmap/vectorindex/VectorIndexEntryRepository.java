package com.example.aimindmap.vectorindex;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface VectorIndexEntryRepository
        extends JpaRepository<VectorIndexEntryEntity, Long> {

    Optional<VectorIndexEntryEntity> findByResourceTypeAndResourceIdAndChunkIndex(
            String resourceType, String resourceId, int chunkIndex);

    List<VectorIndexEntryEntity> findByResourceTypeAndResourceId(
            String resourceType, String resourceId);

    /** 某张导图下的全部节点映射（resource_id 形如 "{mindmapId}:{nodeId}"）。 */
    @Query(value = "SELECT * FROM vector_index_entries "
            + "WHERE resource_type = 'mindmap_node' AND resource_id LIKE :prefix",
            nativeQuery = true)
    List<VectorIndexEntryEntity> findMindmapNodeEntries(@Param("prefix") String prefix);

    List<VectorIndexEntryEntity> findByUserId(Long userId);
}
