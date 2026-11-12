-- 学习 Agent 阶段 3：Agent 执行记录表与工具调用记录表（方案 §10.3 / §10.4）
-- 只建本阶段两张表；agent_memory 顺延阶段 4（V9）
CREATE TABLE agent_runs (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id),
    session_id BIGINT NOT NULL REFERENCES ai_chat_sessions(id),
    status VARCHAR(16) NOT NULL CHECK (status IN ('RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
    model VARCHAR(128),
    user_message TEXT,
    answer_summary TEXT,
    tool_call_count INTEGER NOT NULL DEFAULT 0,
    prompt_chars INTEGER,
    error_code VARCHAR(64),
    error_message TEXT,
    started_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMP
);

CREATE TABLE agent_tool_calls (
    id BIGSERIAL PRIMARY KEY,
    run_id BIGINT NOT NULL REFERENCES agent_runs(id),
    user_id BIGINT NOT NULL REFERENCES users(id),
    tool_name VARCHAR(64) NOT NULL,
    arguments TEXT,
    result_summary TEXT,
    status VARCHAR(16) NOT NULL CHECK (status IN ('RUNNING', 'SUCCEEDED', 'FAILED')),
    error_message TEXT,
    duration_ms BIGINT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_agent_runs_user_started ON agent_runs(user_id, started_at DESC);
CREATE INDEX idx_agent_runs_session ON agent_runs(session_id);
CREATE INDEX idx_agent_tool_calls_run ON agent_tool_calls(run_id);
CREATE INDEX idx_agent_tool_calls_user_created ON agent_tool_calls(user_id, created_at DESC);
