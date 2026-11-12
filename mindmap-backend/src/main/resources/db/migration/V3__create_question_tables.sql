CREATE TABLE questions (
    id BIGSERIAL PRIMARY KEY,
    mindmap_id BIGINT NOT NULL REFERENCES mindmaps(id),
    node_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
    subject VARCHAR(64),
    grade VARCHAR(64),
    question_type VARCHAR(32) NOT NULL,
    difficulty VARCHAR(32) NOT NULL,
    stem TEXT NOT NULL,
    options JSONB,
    answer JSONB NOT NULL,
    explanation TEXT,
    ai_model VARCHAR(128),
    status VARCHAR(32) NOT NULL DEFAULT 'active',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_questions_mindmap_id ON questions(mindmap_id);

CREATE TABLE question_generation_jobs (
    id BIGSERIAL PRIMARY KEY,
    mindmap_id BIGINT NOT NULL REFERENCES mindmaps(id),
    status VARCHAR(32) NOT NULL,
    request JSONB NOT NULL,
    result_question_ids JSONB,
    error_code VARCHAR(64),
    error_message TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMP
);
