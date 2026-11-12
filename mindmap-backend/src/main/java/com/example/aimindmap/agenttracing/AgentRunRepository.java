package com.example.aimindmap.agenttracing;

import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentRunRepository extends JpaRepository<AgentRunEntity, Long> {

    /**
     * P0-E1：会话内 run 列表（只按 userId + sessionId 过滤，天然不会返回别人的 run）。
     * 排序固定为 started_at 倒序、id 倒序（同一毫秒内按写入顺序倒排），limit 由 Pageable 控制。
     */
    List<AgentRunEntity> findByUserIdAndSessionIdOrderByStartedAtDescIdDesc(
            Long userId, Long sessionId, Pageable pageable);
}
