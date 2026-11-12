package com.example.aimindmap.agenttracing;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentToolCallRepository extends JpaRepository<AgentToolCallEntity, Long> {

    List<AgentToolCallEntity> findByRunIdOrderByIdAsc(Long runId);

    List<AgentToolCallEntity> findByRunIdAndStatusOrderByIdAsc(Long runId, AgentToolCallStatus status);
}
