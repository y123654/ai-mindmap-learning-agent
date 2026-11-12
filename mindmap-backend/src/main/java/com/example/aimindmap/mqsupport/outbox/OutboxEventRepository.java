package com.example.aimindmap.mqsupport.outbox;

import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OutboxEventRepository extends JpaRepository<OutboxEventEntity, Long> {

    /**
     * 认领一批 PENDING：行锁 + SKIP LOCKED，多实例/双实例下同一行只被一个 relay 拿到
     * （方案《阶段四·B》《C》H1-5）。必须在事务内调用，锁随事务提交释放。
     */
    @Query(value = "SELECT * FROM outbox_events "
            + "WHERE status = 'PENDING' ORDER BY id LIMIT :limit FOR UPDATE SKIP LOCKED",
            nativeQuery = true)
    List<OutboxEventEntity> claimPending(@Param("limit") int limit);

    /** 物理清理 sent_at 早于 cutoff 的 SENT 行（SENT 保留 7 天）。 */
    @Modifying
    @Query("DELETE FROM OutboxEventEntity e "
            + "WHERE e.status = com.example.aimindmap.mqsupport.outbox.OutboxEventStatus.SENT "
            + "AND e.sentAt < :cutoff")
    int deleteSentBefore(@Param("cutoff") Instant cutoff);

    /**
     * 4-3 对账用：该任务是否已有指定状态的 outbox 行（避免每轮扫描重复补写 PENDING）。
     */
    boolean existsByTaskTypeAndTaskIdAndStatus(String taskType, Long taskId, OutboxEventStatus status);
}
