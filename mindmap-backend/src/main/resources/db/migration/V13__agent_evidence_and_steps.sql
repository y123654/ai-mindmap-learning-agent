-- P0-3 证据协议：agent_runs 补 7 列 + agent_steps / agent_evidence / agent_citations 三张表。
-- 依据：docs/学习Agent求职强化方案-评测自主性与行动能力-v1.0.md §6 V13 原文。
--
-- 已批准的偏差（所有者 2026-10-26 决定，方案 C）：
--   agent_evidence.resource_id 由方案原文的 BIGINT NOT NULL 改为 VARCHAR(64) NOT NULL。
--   原因：向量库真实 payload 里导图节点的 source_id 是非数字 nodeId（例如 "node-1"），
--   用 BIGINT 要么截断成 mindmapId、要么把 nodeId 哈希成数字，都会丢失 P1 来源卡片的可反查性。
--   其余列/表/索引/唯一约束严格按方案 §6 原文，不改任何已有列语义。
ALTER TABLE agent_runs ADD COLUMN IF NOT EXISTS completion_tokens INT;
ALTER TABLE agent_runs ADD COLUMN IF NOT EXISTS estimated_cost NUMERIC(12,6);
ALTER TABLE agent_runs ADD COLUMN IF NOT EXISTS intent VARCHAR(16);
ALTER TABLE agent_runs ADD COLUMN IF NOT EXISTS citation_count INT NOT NULL DEFAULT 0;
ALTER TABLE agent_runs ADD COLUMN IF NOT EXISTS grounded BOOLEAN;
ALTER TABLE agent_runs ADD COLUMN IF NOT EXISTS plan_json TEXT;
ALTER TABLE agent_runs ADD COLUMN IF NOT EXISTS repair_round INT NOT NULL DEFAULT 0;

CREATE TABLE agent_steps (
  id BIGSERIAL PRIMARY KEY,
  run_id BIGINT NOT NULL REFERENCES agent_runs(id) ON DELETE CASCADE,
  step_index INT NOT NULL,
  step_type VARCHAR(24) NOT NULL,      -- PLAN / RETRIEVE / SUFFICIENCY / SYNTH / VERIFY / REPAIR
  name VARCHAR(128),
  status VARCHAR(16) NOT NULL,         -- RUNNING / SUCCEED / FAILED / SKIPPED
  input_summary VARCHAR(1000),
  output_summary VARCHAR(1000),
  error_code VARCHAR(64),
  duration_ms BIGINT,
  prompt_tokens INT,
  completion_tokens INT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_agent_steps_run ON agent_steps(run_id, step_index);

CREATE TABLE agent_evidence (
  id BIGSERIAL PRIMARY KEY,
  run_id BIGINT NOT NULL REFERENCES agent_runs(id) ON DELETE CASCADE,
  evidence_key VARCHAR(16) NOT NULL,   -- E1...
  resource_type VARCHAR(32) NOT NULL,
  resource_id VARCHAR(64) NOT NULL,    -- 方案原文为 BIGINT；2026-10-26 批准改 VARCHAR(64)，理由见文件头
  chunk_index INT,
  score REAL,
  title VARCHAR(255),
  snippet TEXT,
  suspicious BOOLEAN NOT NULL DEFAULT FALSE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (run_id, evidence_key)
);

CREATE TABLE agent_citations (
  id BIGSERIAL PRIMARY KEY,
  run_id BIGINT NOT NULL REFERENCES agent_runs(id) ON DELETE CASCADE,
  evidence_key VARCHAR(16) NOT NULL,
  valid BOOLEAN NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
