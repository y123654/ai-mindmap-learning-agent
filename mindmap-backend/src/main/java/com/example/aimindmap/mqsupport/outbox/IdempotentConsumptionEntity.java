package com.example.aimindmap.mqsupport.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * idempotent_consumptions：消费幂等记录（方案《阶段四·B/C》）。
 * 主键 messageId 固定为 taskType:taskId；本小阶段只建表与实体，4-3 消费者才写入。
 */
@Entity
@Table(name = "idempotent_consumptions")
public class IdempotentConsumptionEntity {

    @Id
    @Column(name = "message_id", length = 128)
    private String messageId;

    @Column(name = "consumed_at", nullable = false)
    private Instant consumedAt = Instant.now();

    public IdempotentConsumptionEntity() {
    }

    public IdempotentConsumptionEntity(String messageId) {
        this.messageId = messageId;
    }

    public String getMessageId() {
        return messageId;
    }

    public void setMessageId(String messageId) {
        this.messageId = messageId;
    }

    public Instant getConsumedAt() {
        return consumedAt;
    }

    public void setConsumedAt(Instant consumedAt) {
        this.consumedAt = consumedAt;
    }
}
