package com.example.aimindmap.mindmap;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SyncJobRepository extends JpaRepository<SyncJobEntity, Long> {

    /** §13.4 活动状态：PENDING、PARSING、DIFFING、AI_GENERATING、VALIDATING、READY、ANIMATING */
    List<SyncJobEntity> findByDocumentIdAndStatusInOrderByIdDesc(Long documentId, List<String> statuses);

    Optional<SyncJobEntity> findFirstByDocumentIdOrderByIdDesc(Long documentId);

    /**
     * 阶段六-1 MQ 消费者条件认领（幂等三件套之一，跨实例正确性靠 DB 原子操作）：
     * 仅 PENDING 行能被原子置为 PARSING。返回 1=获得执行权；0=任务已终态/已被认领，
     * 调用方（SyncJobMessageConsumer）直接 ack。JVM synchronized 不能替代本条件更新。
     */
    @Modifying
    @Query(value = "UPDATE sync_jobs SET status = 'PARSING' "
            + "WHERE id = :id AND status = 'PENDING'",
            nativeQuery = true)
    int claimForConsumer(@Param("id") Long id);

    /**
     * 可重试失败转延时重试前把任务行从四个中间态复位为 PENDING，
     * 重试消息到期回到工作队列后才能被 {@link #claimForConsumer} 再次认领。
     * PENDING/READY/终态行不命中（返回 0），避免覆盖已发布或已取消任务。
     */
    @Modifying
    @Query(value = "UPDATE sync_jobs SET status = 'PENDING' "
            + "WHERE id = :id AND status IN ('PARSING','DIFFING','AI_GENERATING','VALIDATING')",
            nativeQuery = true)
    int requeueForRetry(@Param("id") Long id);

    /** 对账：PENDING 超过门槛仍未消费（outbox DEAD/漏发），行锁 + SKIP LOCKED。 */
    @Query(value = "SELECT * FROM sync_jobs "
            + "WHERE status = 'PENDING' AND created_at < :cutoff "
            + "ORDER BY id LIMIT :limit FOR UPDATE SKIP LOCKED",
            nativeQuery = true)
    List<SyncJobEntity> findStalePending(@Param("cutoff") Instant cutoff,
                                         @Param("limit") int limit);

    /**
     * 对账：四个中间态超过门槛（消费者崩溃/重试耗尽进 DLQ 后基础设施恢复），
     * 行锁 + SKIP LOCKED。年龄锚点只有 created_at（无 updated_at，沿用阶段五-A 已接受取舍）。
     */
    @Query(value = "SELECT * FROM sync_jobs "
            + "WHERE status IN ('PARSING','DIFFING','AI_GENERATING','VALIDATING') "
            + "AND created_at < :cutoff ORDER BY id LIMIT :limit FOR UPDATE SKIP LOCKED",
            nativeQuery = true)
    List<SyncJobEntity> findStaleProcessing(@Param("cutoff") Instant cutoff,
                                            @Param("limit") int limit);
}
