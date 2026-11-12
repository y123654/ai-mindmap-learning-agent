package com.example.aimindmap.agenttracing;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AgentStepRepository extends JpaRepository<AgentStepEntity, Long> {

    /** 按 run 内序号升序返回步骤（观测接口 {@code GET /api/agent/runs/{runId}} 的 steps）。 */
    List<AgentStepEntity> findByRunIdOrderByStepIndexAsc(Long runId);

    /**
     * 本 run 当前最大的 {@code step_index}；没有步骤时返回 {@code null}。
     *
     * <p>P1-C1 起步骤序号必须 run 内从 1 严格递增（PLAN → RETRIEVE ×N → SYNTH，
     * 补检也要接着往后排），因此写入前统一用该方法取“下一个序号”。
     */
    @Query("select max(step.stepIndex) from AgentStepEntity step where step.runId = :runId")
    Integer findMaxStepIndexByRunId(@Param("runId") Long runId);
}
