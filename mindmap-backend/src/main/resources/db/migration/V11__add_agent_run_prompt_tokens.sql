-- 综合升级方案 第一部分 阶段二：真实 Token 计量与动态输出预算（V11）
-- agent_runs 增加本轮估算输入 token（cl100k 保守估算，可空）；
-- 平台真实 usage 只走日志 / SSE，不入库（避免过度设计）。
ALTER TABLE agent_runs ADD COLUMN prompt_tokens INTEGER;
