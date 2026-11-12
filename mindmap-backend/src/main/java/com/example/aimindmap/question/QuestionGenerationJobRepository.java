package com.example.aimindmap.question;

import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface QuestionGenerationJobRepository
        extends JpaRepository<QuestionGenerationJobEntity, Long> {

    /**
     * 阶段六-2 MQ 消费者条件认领（幂等三件套之一，跨实例正确性靠 DB 原子操作）：
     * 仅 PENDING 行能被原子置为 RUNNING（出题链路只有 RUNNING 一个中间态）。
     * 返回 1=获得执行权；0=任务已终态/已被认领，调用方（QuestionGenerationMessageConsumer）直接 ack。
     */
    @Modifying
    @Query(value = "UPDATE question_generation_jobs SET status = 'RUNNING' "
            + "WHERE id = :id AND status = 'PENDING'",
            nativeQuery = true)
    int claimForConsumer(@Param("id") Long id);

    /**
     * 可重试失败转延时重试前把任务行从 RUNNING 复位为 PENDING，
     * 重试消息到期回到工作队列后才能被 {@link #claimForConsumer} 再次认领。
     * PENDING/COMPLETED/FAILED 行不命中（返回 0），避免覆盖已发布或已终态任务。
     */
    @Modifying
    @Query(value = "UPDATE question_generation_jobs SET status = 'PENDING' "
            + "WHERE id = :id AND status = 'RUNNING'",
            nativeQuery = true)
    int requeueForRetry(@Param("id") Long id);

    /**
     * 对账：PENDING 超过门槛仍未消费（outbox DEAD/漏发/消息丢失），行锁 + SKIP LOCKED。
     * 年龄锚点只有 created_at（本表无 updated_at，不加 V13 迁移，取舍与 6-1 一致）。
     */
    @Query(value = "SELECT * FROM question_generation_jobs "
            + "WHERE status = 'PENDING' AND created_at < :cutoff "
            + "ORDER BY id LIMIT :limit FOR UPDATE SKIP LOCKED",
            nativeQuery = true)
    List<QuestionGenerationJobEntity> findStalePending(@Param("cutoff") Instant cutoff,
                                                       @Param("limit") int limit);

    /**
     * 对账：唯一中间态 RUNNING 超过门槛（消费者崩溃/三档重试耗尽进 DLQ 后基础设施恢复），
     * 行锁 + SKIP LOCKED。
     */
    @Query(value = "SELECT * FROM question_generation_jobs "
            + "WHERE status = 'RUNNING' AND created_at < :cutoff "
            + "ORDER BY id LIMIT :limit FOR UPDATE SKIP LOCKED",
            nativeQuery = true)
    List<QuestionGenerationJobEntity> findStaleProcessing(@Param("cutoff") Instant cutoff,
                                                          @Param("limit") int limit);
}
