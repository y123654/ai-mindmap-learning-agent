-- v1.1 修订第 16 条：登录/注册体系与数据按账号隔离
CREATE TABLE users (
    id BIGSERIAL PRIMARY KEY,
    username VARCHAR(64) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    major VARCHAR(128) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX idx_users_username_lower ON users(LOWER(username));

ALTER TABLE documents ADD COLUMN user_id BIGINT REFERENCES users(id);
ALTER TABLE mindmaps ADD COLUMN user_id BIGINT REFERENCES users(id);
ALTER TABLE questions ADD COLUMN user_id BIGINT REFERENCES users(id);

CREATE INDEX idx_documents_user_id ON documents(user_id);
CREATE INDEX idx_mindmaps_user_id ON mindmaps(user_id);
CREATE INDEX idx_questions_user_id ON questions(user_id);