-- 学习 Agent 阶段 2：向量索引任务表与映射表（方案 §9.2）
-- 只建本阶段两张表；agent_memory / agent_runs / agent_tool_calls 顺延后续迁移
CREATE TABLE vector_index_tasks (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT REFERENCES users(id),
    resource_type VARCHAR(32) NOT NULL,
    resource_id VARCHAR(128) NOT NULL,
    chunk_index INTEGER NOT NULL DEFAULT 0,
    operation VARCHAR(16) NOT NULL CHECK (operation IN ('UPSERT', 'DELETE')),
    status VARCHAR(16) NOT NULL CHECK (status IN ('PENDING', 'PROCESSING', 'SUCCEEDED', 'FAILED')),
    attempts INTEGER NOT NULL DEFAULT 0,
    last_error TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE vector_index_entries (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT REFERENCES users(id),
    resource_type VARCHAR(32) NOT NULL,
    resource_id VARCHAR(128) NOT NULL,
    chunk_index INTEGER NOT NULL DEFAULT 0,
    qdrant_point_id VARCHAR(64) NOT NULL,
    content_hash VARCHAR(64),
    status VARCHAR(16) NOT NULL CHECK (status IN ('ACTIVE', 'PENDING_DELETE', 'ERROR')),
    last_synced_at TIMESTAMP,
    last_error TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 同一逻辑点（资源+片段）只允许一条映射
CREATE UNIQUE INDEX uq_vector_entries_resource_chunk
    ON vector_index_entries(resource_type, resource_id, chunk_index);

-- 任务防重：同一资源+片段+同一操作只允许一条 PENDING，防止 PENDING 堆积
CREATE UNIQUE INDEX uq_vector_tasks_pending
    ON vector_index_tasks(resource_type, resource_id, chunk_index, operation)
    WHERE status = 'PENDING';

CREATE INDEX idx_vector_tasks_status_id ON vector_index_tasks(status, id);
CREATE INDEX idx_vector_tasks_user ON vector_index_tasks(user_id);
CREATE INDEX idx_vector_entries_user ON vector_index_entries(user_id);
CREATE INDEX idx_vector_entries_resource ON vector_index_entries(resource_type, resource_id);
