-- 综合升级方案 第二部分 阶段四·B：Outbox 本地发件箱 + 消费幂等表（V12）
-- outbox_events：业务事务内写 PENDING，OutboxRelay 投递成功后置 SENT，
--   连续失败达上限置 DEAD；SENT 保留 7 天后定时清理。
-- idempotent_consumptions：消费端幂等三件套之一，messageId=taskType:taskId（4-3 使用）。
CREATE TABLE outbox_events (
    id BIGSERIAL PRIMARY KEY,
    task_type VARCHAR(32) NOT NULL,
    task_id BIGINT NOT NULL,
    payload TEXT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','SENT','DEAD')),
    attempts INTEGER NOT NULL DEFAULT 0,
    last_error TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    sent_at TIMESTAMP
);
CREATE INDEX idx_outbox_pending ON outbox_events(status, id) WHERE status = 'PENDING';

CREATE TABLE idempotent_consumptions (
    message_id VARCHAR(128) PRIMARY KEY,   -- taskType:taskId
    consumed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
