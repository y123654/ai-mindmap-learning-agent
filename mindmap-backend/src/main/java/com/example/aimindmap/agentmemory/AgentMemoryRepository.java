package com.example.aimindmap.agentmemory;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 长期记忆 Repository（方案 §10.2）。
 */
public interface AgentMemoryRepository extends JpaRepository<AgentMemoryEntity, Long> {

    List<AgentMemoryEntity> findByUserIdOrderByImportanceDescUpdatedAtDesc(Long userId);

    List<AgentMemoryEntity> findByUserIdAndStatusOrderByImportanceDescUpdatedAtDesc(
            Long userId, String status);

    List<AgentMemoryEntity> findByUserIdAndStatus(Long userId, String status);
}
