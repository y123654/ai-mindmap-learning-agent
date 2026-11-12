-- 综合升级方案 第一部分 阶段一：单轮工具结果缓存（V10）
-- agent_tool_calls 标记该记录是否为缓存命中落库；agent_runs 汇总本次 run 的缓存命中次数。
ALTER TABLE agent_tool_calls ADD COLUMN from_cache BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE agent_runs ADD COLUMN cache_hits INTEGER NOT NULL DEFAULT 0;
