package com.example.aimindmap.agenttracing;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentCitationRepository extends JpaRepository<AgentCitationEntity, Long> {

    List<AgentCitationEntity> findByRunIdOrderByEvidenceKeyAsc(Long runId);
}
