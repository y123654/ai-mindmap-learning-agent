-- v1.1 方案 §10.3 Markdown 块快照表：用于增量比较和问题追踪
CREATE TABLE document_block_snapshots (
    id BIGSERIAL PRIMARY KEY,
    document_id BIGINT NOT NULL REFERENCES documents(id),
    document_version INTEGER NOT NULL,
    block_id VARCHAR(128) NOT NULL,
    block_type VARCHAR(32) NOT NULL,
    anchor TEXT NOT NULL,
    heading_path JSONB NOT NULL DEFAULT '[]'::jsonb,
    normalized_text TEXT NOT NULL,
    content_hash VARCHAR(128) NOT NULL,
    block_order INTEGER NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_block_snapshots_doc_version
    ON document_block_snapshots(document_id, document_version);

CREATE UNIQUE INDEX uk_block_snapshots_doc_version_block
    ON document_block_snapshots(document_id, document_version, block_id);

-- v1.1 方案 §10.4 同步任务表
CREATE TABLE sync_jobs (
    id BIGSERIAL PRIMARY KEY,
    document_id BIGINT NOT NULL REFERENCES documents(id),
    mindmap_id BIGINT REFERENCES mindmaps(id),
    status VARCHAR(32) NOT NULL,
    base_content_version INTEGER,
    target_content_version INTEGER NOT NULL,
    base_graph_version INTEGER,
    change_summary JSONB,
    ai_temp_operations JSONB,
    validated_operations JSONB,
    target_graph JSONB,
    error_code VARCHAR(64),
    error_message TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ready_at TIMESTAMP,
    animation_started_at TIMESTAMP,
    completed_at TIMESTAMP,
    failed_at TIMESTAMP,
    expires_at TIMESTAMP
);

CREATE INDEX idx_sync_jobs_document_id ON sync_jobs(document_id);
CREATE INDEX idx_sync_jobs_status ON sync_jobs(status);