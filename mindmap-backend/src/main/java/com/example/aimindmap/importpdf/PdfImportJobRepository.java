package com.example.aimindmap.importpdf;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PdfImportJobRepository extends JpaRepository<PdfImportJobEntity, Long> {

    Optional<PdfImportJobEntity> findByIdAndUserId(Long id, Long userId);

    /**
     * 阶段五-A MQ 消费者条件认领（幂等三件套之一）：仅 PENDING 行能被原子置为 EXTRACTING。
     * 返回 1=获得执行权；0=任务已终态/已被认领，调用方直接 ack。
     */
    @Modifying
    @Query(value = "UPDATE pdf_import_jobs SET status = 'EXTRACTING' "
            + "WHERE id = :id AND status = 'PENDING'",
            nativeQuery = true)
    int claimForConsumer(@Param("id") Long id);

    /**
     * 可重试失败转延时重试前把任务行从 EXTRACTING 复位为 PENDING，
     * 重试消息到期回到工作队列后才能被 {@link #claimForConsumer} 再次认领。
     */
    @Modifying
    @Query(value = "UPDATE pdf_import_jobs SET status = 'PENDING' "
            + "WHERE id = :id AND status = 'EXTRACTING'",
            nativeQuery = true)
    int requeueForRetry(@Param("id") Long id);

    /**
     * app.mq.enabled=false 轮询兜底：按 id 升序（与上传投递顺序一致）行锁认领 PENDING。
     */
    @Query(value = "SELECT * FROM pdf_import_jobs "
            + "WHERE status = 'PENDING' ORDER BY id LIMIT :limit FOR UPDATE SKIP LOCKED",
            nativeQuery = true)
    List<PdfImportJobEntity> claimNextPending(@Param("limit") int limit);

    /** 对账：PENDING 超过门槛仍未消费（outbox DEAD/漏发），行锁 + SKIP LOCKED。 */
    @Query(value = "SELECT * FROM pdf_import_jobs "
            + "WHERE status = 'PENDING' AND created_at < :cutoff "
            + "ORDER BY id LIMIT :limit FOR UPDATE SKIP LOCKED",
            nativeQuery = true)
    List<PdfImportJobEntity> findStalePending(@Param("cutoff") Instant cutoff,
                                              @Param("limit") int limit);

    /** 对账：EXTRACTING 超过门槛（消费者崩溃/中断），行锁 + SKIP LOCKED。 */
    @Query(value = "SELECT * FROM pdf_import_jobs "
            + "WHERE status = 'EXTRACTING' AND created_at < :cutoff "
            + "ORDER BY id LIMIT :limit FOR UPDATE SKIP LOCKED",
            nativeQuery = true)
    List<PdfImportJobEntity> findStaleExtracting(@Param("cutoff") Instant cutoff,
                                                 @Param("limit") int limit);
}
