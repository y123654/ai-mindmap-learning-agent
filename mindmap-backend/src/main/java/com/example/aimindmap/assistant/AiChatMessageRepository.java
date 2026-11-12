package com.example.aimindmap.assistant;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AiChatMessageRepository extends JpaRepository<AiChatMessageEntity, Long> {
    List<AiChatMessageEntity> findBySessionIdOrderByIdAsc(Long sessionId);

    List<AiChatMessageEntity> findTop30BySessionIdOrderByIdDesc(Long sessionId);

    List<AiChatMessageEntity> findTop10BySessionIdOrderByIdDesc(Long sessionId);

    long countBySessionId(Long sessionId);
}
