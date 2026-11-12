package com.example.aimindmap.agenttracing;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentEvidenceRepository extends JpaRepository<AgentEvidenceEntity, Long> {

    List<AgentEvidenceEntity> findByRunIdOrderByEvidenceKeyAsc(Long runId);
}
