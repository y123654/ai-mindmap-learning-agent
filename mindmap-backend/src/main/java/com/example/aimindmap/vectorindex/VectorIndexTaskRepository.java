package com.example.aimindmap.vectorindex;

import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface VectorIndexTaskRepository
        extends JpaRepository<VectorIndexTaskEntity, Long> {

    /** 认领 PENDING：行锁 + SKIP LOCKED，避免多实例/并发重复认领。 */
    @Query(value = "SELECT * FROM vector_index_tasks "
            + "WHERE status = 'PENDING' ORDER BY id LIMIT :limit FOR UPDATE SKIP LOCKED",
            nativeQuery = true)
    List<VectorIndexTaskEntity> claimPending(@Param("limit") int limit);

    /** 认领可重试的 FAILED：未达尝试上限，同样行锁 + SKIP LOCKED。 */
    @Query(value = "SELECT * FROM vector_index_tasks "
            + "WHERE status = 'FAILED' AND attempts < :maxAttempts "
            + "ORDER BY id LIMIT :limit FOR UPDATE SKIP LOCKED",
            nativeQuery = true)
    List<VectorIndexTaskEntity> claimRetriableFailed(@Param("limit") int limit,
                                                      @Param("maxAttempts") int maxAttempts);

    boolean existsByResourceTypeAndResourceIdAndChunkIndexAndOperationAndStatus(
            String resourceType, String resourceId, int chunkIndex, String operation, String status);

    /**
     * 4-3 MQ 消费者条件认领（幂等三件套之一）：仅当任务行仍是 PENDING 时
     * 原子地置为 PROCESSING 并把 attempts+1。返回受影响行数：
     * 1=本次投递获得执行权；0=任务已被其他投递/实例处理（或已完成/重试复位中），直接 ack。
     */
    @Modifying
    @Query(value = "UPDATE vector_index_tasks SET status = 'PROCESSING', "
            + "attempts = attempts + 1, updated_at = CURRENT_TIMESTAMP "
            + "WHERE id = :id AND status = 'PENDING'",
            nativeQuery = true)
    int claimForConsumer(@Param("id") Long id);

    /**
     * 发延时重试前把任务行从 PROCESSING 复位为 PENDING，使重试消息到期回到工作队列时
     * 能再次被 {@link #claimForConsumer} 认领。仅复位仍处于 PROCESSING 的行（0 行属并发竞态，
     * 重试消息回来后条件认领自然返回 0 并直接 ack）。
     */
    @Modifying
    @Query(value = "UPDATE vector_index_tasks SET status = 'PENDING', "
            + "last_error = :lastError, updated_at = CURRENT_TIMESTAMP "
            + "WHERE id = :id AND status = 'PROCESSING'",
            nativeQuery = true)
    int requeueForRetry(@Param("id") Long id, @Param("lastError") String lastError);

    /**
     * 对账扫描：PENDING 超过阈值仍未被消费（漏发/outbox DEAD），行锁 + SKIP LOCKED，
     * 双实例下同一条只被一个实例补写 outbox。
     */
    @Query(value = "SELECT * FROM vector_index_tasks "
            + "WHERE status = 'PENDING' AND updated_at < :cutoff "
            + "ORDER BY id LIMIT :limit FOR UPDATE SKIP LOCKED",
            nativeQuery = true)
    List<VectorIndexTaskEntity> findStalePending(@Param("cutoff") Instant cutoff,
                                                 @Param("limit") int limit);

    /**
     * 对账扫描：PROCESSING 超过阈值（消费者崩溃/处理中断；正常重试路径会先复位为 PENDING），
     * 同样行锁 + SKIP LOCKED。
     */
    @Query(value = "SELECT * FROM vector_index_tasks "
            + "WHERE status = 'PROCESSING' AND updated_at < :cutoff "
            + "ORDER BY id LIMIT :limit FOR UPDATE SKIP LOCKED",
            nativeQuery = true)
    List<VectorIndexTaskEntity> findStaleProcessing(@Param("cutoff") Instant cutoff,
                                                    @Param("limit") int limit);
}
