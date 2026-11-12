import { getToken } from "../lib/http";
import { parseResponse } from "../lib/apiError";
import type {
  AgentRunDetail,
  AssistantSession,
  AssistantSessionDetail,
  StreamCompletedPayload
} from "../types/assistant";

export async function listAssistantSessions(): Promise<AssistantSession[]> {
  const response = await fetch("/api/ai-assistant/sessions", {
    headers: authHeaders()
  });
  if (response.status === 401) notifyUnauthorized();
  return parseResponse(response, "获取历史对话失败");
}

export async function createAssistantSession(): Promise<AssistantSession> {
  const response = await fetch("/api/ai-assistant/sessions", {
    method: "POST",
    headers: authHeaders(true),
    body: JSON.stringify({})
  });
  if (response.status === 401) notifyUnauthorized();
  return parseResponse(response, "创建对话失败");
}

export async function getAssistantSession(sessionId: string): Promise<AssistantSessionDetail> {
  const response = await fetch(`/api/ai-assistant/sessions/${sessionId}`, {
    headers: authHeaders()
  });
  if (response.status === 401) notifyUnauthorized();
  return parseResponse(response, "获取对话内容失败");
}

/**
 * P0-E2：「本次检索详情」数据源。
 *
 * <p>runId 由流式 completed 事件下发，是后端混淆过的对外字符串，前端不做任何解析，
 * 原样拼进 URL 即可；鉴权与错误处理沿用本文件的 authHeaders / parseResponse。</p>
 */
export async function fetchAgentRun(runId: string): Promise<AgentRunDetail> {
  const response = await fetch(`/api/agent/runs/${encodeURIComponent(runId)}`, {
    headers: authHeaders()
  });
  if (response.status === 401) notifyUnauthorized();
  return parseResponse(response, "获取本次检索详情失败");
}

/** 修订第 23 条：删除会话及其全部消息（204 无响应体）。 */
export async function deleteAssistantSession(sessionId: string): Promise<void> {
  const response = await fetch(`/api/ai-assistant/sessions/${sessionId}`, {
    method: "DELETE",
    headers: authHeaders()
  });
  if (response.status === 401) notifyUnauthorized();
  await parseResponse(response, "删除对话失败");
}

/** P0-2：工具调用过程事件（SSE `tool_call`）。文案由后端 label 提供，前端不翻译工具名。 */
export type ToolEventPhase = "start" | "succeed" | "failed";

export interface StreamToolEventPayload {
  seq: number;
  tool: string;
  label: string;
  detail?: string | null;
  phase: ToolEventPhase;
  fromCache?: boolean;
  durationMs?: number | null;
  errorCode?: string | null;
}

interface StreamHandlers {
  onDelta: (delta: string) => void;
  onCompleted: (payload: StreamCompletedPayload) => void | Promise<void>;
  /** P0-2：可选；旧调用方不传时行为与改造前完全一致。 */
  onToolEvent?: (event: StreamToolEventPayload) => void;
}

type SseHandleResult = "continue" | "completed";

export async function streamAssistantMessage(
  sessionId: string,
  content: string,
  handlers: StreamHandlers
): Promise<void> {
  const response = await fetch(`/api/ai-assistant/sessions/${sessionId}/stream`, {
    method: "POST",
    headers: authHeaders(true),
    body: JSON.stringify({ content })
  });

  if (response.status === 401) {
    notifyUnauthorized();
  }
  if (!response.ok || !response.body) {
    const data = await response.json().catch(() => null) as { message?: string } | null;
    throw new Error(data?.message ?? "AI 流式回答失败");
  }

  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";

  const processBuffer = async (flush: boolean): Promise<boolean> => {
    let separatorIndex = buffer.indexOf("\n\n");
    while (separatorIndex !== -1) {
      const rawEvent = buffer.slice(0, separatorIndex);
      buffer = buffer.slice(separatorIndex + 2);
      const result = await handleSseEvent(rawEvent, handlers);
      if (result === "completed") return true;
      separatorIndex = buffer.indexOf("\n\n");
    }

    if (flush) {
      const tail = buffer.trim();
      if (tail) {
        buffer = "";
        const result = await handleSseEvent(tail, handlers);
        if (result === "completed") return true;
      }
    }
    return false;
  };

  try {
    while (true) {
      const { value, done } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true }).replace(/\r\n/g, "\n");
      if (await processBuffer(false)) return;
    }
    buffer += decoder.decode();
    await processBuffer(true);
  } finally {
    try {
      await reader.cancel();
    } catch {
      // 服务端正常关闭流时，cancel 可能没有额外效果，忽略即可
    }
  }
}

async function handleSseEvent(rawEvent: string, handlers: StreamHandlers): Promise<SseHandleResult> {
  let eventName = "message";
  const dataLines: string[] = [];

  for (const line of rawEvent.split("\n")) {
    if (line.startsWith("event:")) {
      eventName = line.slice(6).trim();
    } else if (line.startsWith("data:")) {
      dataLines.push(line.slice(5).trimStart());
    }
  }

  if (dataLines.length === 0) return "continue";

  let data: Record<string, unknown>;
  try {
    data = JSON.parse(dataLines.join("\n")) as Record<string, unknown>;
  } catch {
    return "continue";
  }

  if (eventName === "delta") {
    const text = typeof data.content === "string" ? data.content : "";
    if (text) handlers.onDelta(text);
    return "continue";
  }

  // P0-2：工具调用过程事件；未注册回调时静默忽略（前向兼容）
  if (eventName === "tool_call") {
    handlers.onToolEvent?.(data as unknown as StreamToolEventPayload);
    return "continue";
  }

  if (eventName === "completed") {
    await handlers.onCompleted(data as unknown as StreamCompletedPayload);
    return "completed";
  }

  if (eventName === "error") {
    const message = typeof data.message === "string" ? data.message : "AI 流式回答失败";
    throw new Error(message);
  }

  return "continue";
}

function authHeaders(json = false): HeadersInit {
  const headers: Record<string, string> = {};
  const token = getToken();
  if (token) headers.Authorization = `Bearer ${token}`;
  if (json) headers["Content-Type"] = "application/json";
  return headers;
}

function notifyUnauthorized(): void {
  window.dispatchEvent(new CustomEvent("aimindmap:unauthorized"));
}
