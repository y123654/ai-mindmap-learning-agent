-- 学习 Agent 阶段 4-A：会话摘要字段（方案 §10.1）+ 长期记忆表 agent_memory（方案 §10.2）
-- 本迁移只做表结构；记忆业务代码（写入/注入/向量化/管理接口）属于 4-B，不在本阶段实现。

-- §10.1 给 ai_chat_sessions 增加三个摘要字段
ALTER TABLE ai_chat_sessions ADD COLUMN summary TEXT;
ALTER TABLE ai_chat_sessions ADD COLUMN summarized_until_message_id BIGINT;
ALTER TABLE ai_chat_sessions ADD COLUMN summary_updated_at TIMESTAMP;

-- §10.2 新增长期记忆表
CREATE TABLE agent_memory (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id),
    memory_type VARCHAR(32) NOT NULL
        CHECK (memory_type IN ('profile', 'preference', 'weakness', 'goal', 'task', 'fact')),
    content TEXT NOT NULL,
    importance SMALLINT NOT NULL CHECK (importance BETWEEN 1 AND 5),
    confidence SMALLINT NOT NULL CHECK (confidence BETWEEN 0 AND 100),
    status VARCHAR(16) NOT NULL
        CHECK (status IN ('candidate', 'active', 'archived', 'rejected')),
    source_session_id BIGINT REFERENCES ai_chat_sessions(id),
    source_message_id BIGINT,
    last_used_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_agent_memory_user_status ON agent_memory(user_id, status);
CREATE INDEX idx_agent_memory_user_type ON agent_memory(user_id, memory_type);
CREATE INDEX idx_agent_memory_source_session ON agent_memory(source_session_id);
CREATE INDEX idx_agent_memory_source_message ON agent_memory(source_message_id);
