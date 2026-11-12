package com.example.aimindmap.mqsupport.outbox;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 消费幂等仓储（方案《阶段四·C》幂等三件套之三）。
 * 本小阶段只建接口，插入冲突判重逻辑由 4-3 消费者实现。
 */
public interface IdempotentConsumptionRepository
        extends JpaRepository<IdempotentConsumptionEntity, String> {
}
