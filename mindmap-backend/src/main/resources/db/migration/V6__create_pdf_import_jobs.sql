-- 阶段 5（v1.1 修订第 28 条，方案 A）：PDF 导入任务，强制用户归属
CREATE TABLE pdf_import_jobs (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id),
    document_id BIGINT REFERENCES documents(id),
    original_file_name VARCHAR(255) NOT NULL,
    stored_file_path TEXT NOT NULL,
    status VARCHAR(32) NOT NULL,
    page_count INTEGER,
    extracted_text_length INTEGER,
    markdown_draft TEXT,
    suspected_scanned BOOLEAN NOT NULL DEFAULT FALSE,
    error_code VARCHAR(64),
    error_message TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMP
);

CREATE INDEX idx_pdf_import_jobs_user ON pdf_import_jobs(user_id, id DESC);
