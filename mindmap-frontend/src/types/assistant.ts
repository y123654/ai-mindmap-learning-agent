export type AssistantRole = "user" | "assistant"

export interface AssistantSession {
  id: string
  title: string
  createdAt: string
  updatedAt: string
}

export interface AssistantMessage {
  id?: string
  role: AssistantRole
  content: string
  provider?: string | null
  model?: string | null
  createdAt?: string
  synthetic?: boolean
}

export interface AssistantSessionDetail extends AssistantSession {
  messages: AssistantMessage[]
}

export interface StreamCompletedPayload {
  message: AssistantMessage
  session: AssistantSession
  /** P0-E2：后端 completed 事件新增的可选字段（对外混淆串），旧后端/旧会话没有该字段 */
  runId?: string | null
}

/**
 * P0-E2：`GET /api/agent/runs/{runId}` 的响应（对应后端 AgentRunDtos）。
 * 只读投影，字段原样透传，前端不做二次改名。
 */
export interface AgentRunToolCall {
  toolName: string
  arguments?: string | null
  resultSummary?: string | null
  status?: string | null
  errorMessage?: string | null
  durationMs?: number | null
  fromCache?: boolean | null
  createdAt?: string | null
}

export interface AgentRunEvidence {
  evidenceKey: string
  resourceType?: string | null
  resourceId?: string | null
  chunkIndex?: number | null
  score?: number | null
  title?: string | null
  snippet?: string | null
  suspicious?: boolean | null
  createdAt?: string | null
}

export interface AgentRunCitation {
  evidenceKey: string
  valid?: boolean | null
  createdAt?: string | null
}

export interface AgentRunSummary {
  runId: string
  sessionId: string
  status?: string | null
  model?: string | null
  userMessage?: string | null
  answerSummary?: string | null
  toolCallCount?: number | null
  cacheHits?: number | null
  promptChars?: number | null
  promptTokens?: number | null
  completionTokens?: number | null
  estimatedCost?: number | null
  citationCount?: number | null
  grounded?: boolean | null
  startedAt?: string | null
  completedAt?: string | null
  durationMs?: number | null
  errorCode?: string | null
  errorMessage?: string | null
}

/** P0-E1 的 agent_steps 在 P0 阶段恒为空数组（P1 显式编排才产出）。 */
export interface AgentRunStep {
  stepIndex: number
  stepType?: string | null
  name?: string | null
  status?: string | null
  inputSummary?: string | null
  outputSummary?: string | null
  errorCode?: string | null
  durationMs?: number | null
  promptTokens?: number | null
  completionTokens?: number | null
  createdAt?: string | null
}

export interface AgentRunDetail {
  run: AgentRunSummary
  steps: AgentRunStep[]
  evidence: AgentRunEvidence[]
  citations: AgentRunCitation[]
  toolCalls: AgentRunToolCall[]
}
