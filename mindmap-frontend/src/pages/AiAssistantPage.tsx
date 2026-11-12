import {
  FormEvent,
  KeyboardEvent as ReactKeyboardEvent,
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState
} from "react";
import { createPortal } from "react-dom";
import {
  createAssistantSession,
  deleteAssistantSession,
  fetchAgentRun,
  getAssistantSession,
  listAssistantSessions,
  streamAssistantMessage
} from "../api/assistant";
import type { StreamToolEventPayload } from "../api/assistant";
import { MarkdownAnswer } from "../components/assistant/MarkdownAnswer";
import { useAuthStore } from "../stores/authStore";
import type {
  AgentRunDetail,
  AssistantMessage,
  AssistantSession,
  AssistantSessionDetail
} from "../types/assistant";

const SESSION_ID_KEY = "aimindmap.assistantSessionId";
const WELCOME = "我是你的知识助手，可以用本科阶段容易理解的方式解释概念、原理、优缺点、适用场景和易混淆点。你可以直接输入问题。";

function localMessage(role: AssistantMessage["role"], content: string): AssistantMessage {
  return { role, content };
}

function welcomeMessage(): AssistantMessage {
  return { role: "assistant", content: WELCOME, synthetic: true };
}

function formatTime(value: string): string {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return "";
  return `${date.getMonth() + 1}/${date.getDate()} ${String(date.getHours()).padStart(2, "0")}:${String(date.getMinutes()).padStart(2, "0")}`;
}

function messagesFromDetail(detail: AssistantSessionDetail): AssistantMessage[] {
  return detail.messages.length > 0 ? detail.messages : [welcomeMessage()];
}

/**
 * 悬浮预览摘要（修订第 40 条，对齐 Codex 会话预览风格）：
 * 去掉 Markdown 格式后按原文空行切段，段内压缩空白、段间保留分段（\n\n），
 * 气泡里分段渲染，整体最多 3 行截断。
 */
function toPlainPreview(content: string): string {
  const text = content
    .replace(/```[\s\S]*?```/g, " ")
    .replace(/`([^`]*)`/g, "$1")
    .replace(/!\[[^\]]*\]\([^)]*\)/g, " ")
    .replace(/\[([^\]]*)\]\([^)]*\)/g, "$1")
    .replace(/^\s{0,3}#{1,6}\s+/gm, "")
    .replace(/^\s*>+\s?/gm, "")
    .replace(/^\s*[-+]\s+/gm, "")
    .replace(/[*_~]{1,3}/g, "");
  const paragraphs: string[] = [];
  for (const block of text.split(/\n\s*\n/)) {
    const lines = block
      .split(/\n/)
      .map((line) => line.replace(/\s+/g, " ").trim())
      .filter(Boolean);
    if (lines.length > 0) paragraphs.push(lines.join(" "));
  }
  return paragraphs.length > 0 ? paragraphs.join("\n\n") : "暂无对话内容";
}

/** 修订第 40 条：气泡摘要按段落分段显示；loading 占位用弱化灰字。 */
function TooltipPreviewBody({ text, loading = false }: { text: string; loading?: boolean }) {
  return (
    <div className={`assistant-history-tooltip-preview${loading ? " is-loading" : ""}`}>
      {text.split("\n\n").map((paragraph, index) => (
        <span key={index} className="assistant-history-tooltip-paragraph">{paragraph}</span>
      ))}
    </div>
  );
}

/**
 * P0-2：按 seq 合并同一次工具调用的 start / 结束事件。
 * 后端保证一次调用只分配一个 seq，start 与 succeed/failed 共用；
 * 结束事件到达时替换同 seq 条目，避免时间线出现两行。
 */
function mergeToolEvent(
  events: StreamToolEventPayload[],
  incoming: StreamToolEventPayload
): StreamToolEventPayload[] {
  const index = events.findIndex((event) => event.seq === incoming.seq);
  if (index < 0) return [...events, incoming];
  const next = [...events];
  next[index] = { ...events[index], ...incoming };
  return next;
}

/** 成功行右侧耗时（缓存命中为 0，不展示）。 */
function formatToolDuration(durationMs?: number | null): string {
  if (durationMs == null || durationMs <= 0) return "";
  return durationMs < 1000 ? `${durationMs} ms` : `${(durationMs / 1000).toFixed(1)} s`;
}

/** P0-2：「检索过程」时间线：开始转圈、成功打勾、失败灰掉；流式结束后清空。 */
function ToolEventTimeline({ events }: { events: StreamToolEventPayload[] }) {
  return (
    <div className="assistant-tool-events" aria-label="检索过程">
      <p className="assistant-tool-events-title">检索过程</p>
      <ul className="assistant-tool-events-list">
        {events.map((event) => (
          <li key={event.seq} className={`assistant-tool-event is-${event.phase}`}>
            <span className="assistant-tool-event-icon" aria-hidden="true">
              {event.phase === "start" ? (
                <span className="assistant-tool-event-spinner" />
              ) : event.phase === "succeed" ? (
                "✓"
              ) : (
                "✕"
              )}
            </span>
            <span className="assistant-tool-event-label">{event.label}</span>
            {event.detail ? (
              <span className="assistant-tool-event-detail">（{event.detail}）</span>
            ) : null}
            {event.phase === "succeed" && event.fromCache ? (
              <span className="assistant-tool-event-badge">来自缓存</span>
            ) : null}
            {event.phase === "succeed" && !event.fromCache
              && formatToolDuration(event.durationMs) ? (
                <span className="assistant-tool-event-duration">
                  {formatToolDuration(event.durationMs)}
                </span>
              ) : null}
            {event.phase === "failed" ? (
              <span className="assistant-tool-event-failed">调用失败</span>
            ) : null}
          </li>
        ))}
      </ul>
    </div>
  );
}

/* ===================== P0-E2：本次检索详情（抽屉） ===================== */

/** 证据片段在抽屉里截断到 72 个字符，避免撑爆侧栏。 */
const EVIDENCE_SNIPPET_MAX = 72;

/** 向量资料类型的中文显示（后端 wireName 原值，仅用于显示，不改数据）。 */
const RESOURCE_TYPE_LABELS: Record<string, string> = {
  document_chunk: "文档",
  mindmap_node: "导图节点",
  question: "题目",
  memory: "记忆"
};

/** 工具调用状态（后端枚举 RUNNING / SUCCEEDED / FAILED）。 */
const TOOL_STATUS_LABELS: Record<string, string> = {
  RUNNING: "进行中",
  SUCCEEDED: "成功",
  FAILED: "失败"
};

function formatDuration(ms?: number | null): string {
  if (ms == null) return "—";
  return ms < 1000 ? `${ms} ms` : `${(ms / 1000).toFixed(1)} s`;
}

/** 后端 estimatedCost 是 BigDecimal（单位：元），按方案要求保留 6 位小数。 */
function formatCost(cost?: number | null): string {
  return cost == null ? "—" : `${cost.toFixed(6)} 元`;
}

function truncateSnippet(snippet?: string | null): string {
  const text = (snippet ?? "").replace(/\s+/g, " ").trim();
  if (!text) return "（无片段）";
  return text.length > EVIDENCE_SNIPPET_MAX
    ? `${text.slice(0, EVIDENCE_SNIPPET_MAX)}…`
    : text;
}

function resourceTypeLabel(resourceType?: string | null): string {
  if (!resourceType) return "未知类型";
  return RESOURCE_TYPE_LABELS[resourceType] ?? resourceType;
}

function groundedLabel(grounded?: boolean | null): string {
  return grounded == null ? "—" : grounded ? "是" : "否";
}

/**
 * P0-E2：单轮 run 详情抽屉（右侧滑出，遮罩点击/Esc 关闭）。
 *
 * <p>只展示后端 `GET /api/agent/runs/{runId}` 已返回的字段，不在前端二次计算指标；
 * 加载中与失败态都只影响抽屉内容，不阻塞聊天输入与历史列表。</p>
 */
function RunDetailDrawer({
  detail,
  loading,
  failed,
  onClose
}: {
  detail: AgentRunDetail | null;
  loading: boolean;
  failed: boolean;
  onClose: () => void;
}) {
  const run = detail?.run ?? null;
  return (
    <>
      <div className="assistant-run-mask" onClick={onClose} aria-hidden="true" />
      <aside
        className="assistant-run-drawer"
        role="dialog"
        aria-modal="true"
        aria-label="本次检索详情"
      >
        <header className="assistant-run-drawer-header">
          <div>
            <h2>本次检索详情</h2>
            <p>这一轮回答用到的工具、证据与开销</p>
          </div>
          <button
            type="button"
            className="assistant-run-drawer-close"
            onClick={onClose}
            aria-label="关闭检索详情"
          >
            ✕
          </button>
        </header>

        <div className="assistant-run-drawer-body">
          {loading && <p className="assistant-run-hint">详情加载中…</p>}
          {!loading && failed && (
            <p className="assistant-run-hint is-failed" role="alert">详情加载失败</p>
          )}
          {!loading && !failed && detail && (
            <>
              <section className="assistant-run-section">
                <h3>时间线（{detail.toolCalls.length}）</h3>
                {detail.toolCalls.length === 0 ? (
                  <p className="assistant-run-empty">
                    这一轮没有工具调用（闲聊或直接作答）。
                  </p>
                ) : (
                  <ol className="assistant-run-timeline">
                    {detail.toolCalls.map((call, index) => (
                      <li
                        key={`${call.toolName}-${index}`}
                        className={`assistant-run-timeline-item is-${(call.status ?? "").toLowerCase()}`}
                      >
                        <span className="assistant-run-timeline-index">{index + 1}</span>
                        <div className="assistant-run-timeline-main">
                          <div className="assistant-run-timeline-head">
                            <strong>{call.toolName}</strong>
                            <span className="assistant-run-timeline-status">
                              {TOOL_STATUS_LABELS[call.status ?? ""] ?? call.status ?? "未知"}
                            </span>
                            {call.fromCache ? (
                              <span className="assistant-run-timeline-badge">来自缓存</span>
                            ) : null}
                            <span className="assistant-run-timeline-duration">
                              {formatDuration(call.durationMs)}
                            </span>
                          </div>
                          {call.errorMessage ? (
                            <p className="assistant-run-timeline-error">{call.errorMessage}</p>
                          ) : null}
                        </div>
                      </li>
                    ))}
                  </ol>
                )}
              </section>

              <section className="assistant-run-section">
                <h3>证据（{detail.evidence.length}）</h3>
                {detail.evidence.length === 0 ? (
                  <p className="assistant-run-empty">这一轮没有登记证据。</p>
                ) : (
                  <ul className="assistant-run-evidence-list">
                    {detail.evidence.map((item, index) => (
                      <li key={`${item.evidenceKey}-${index}`} className="assistant-run-evidence">
                        <div className="assistant-run-evidence-head">
                          <span className="assistant-run-evidence-key">{item.evidenceKey}</span>
                          <span className="assistant-run-evidence-type">
                            {resourceTypeLabel(item.resourceType)}
                          </span>
                        </div>
                        <p className="assistant-run-evidence-title">
                          {item.title?.trim() || "（无标题）"}
                        </p>
                        <p className="assistant-run-evidence-snippet">
                          {truncateSnippet(item.snippet)}
                        </p>
                      </li>
                    ))}
                  </ul>
                )}
              </section>

              <section className="assistant-run-section">
                <h3>本轮统计</h3>
                <dl className="assistant-run-stats">
                  <div>
                    <dt>prompt tokens</dt>
                    <dd>{run?.promptTokens ?? "—"}</dd>
                  </div>
                  <div>
                    <dt>completion tokens</dt>
                    <dd>{run?.completionTokens ?? "—"}</dd>
                  </div>
                  <div>
                    <dt>estimatedCost</dt>
                    <dd>{formatCost(run?.estimatedCost)}</dd>
                  </div>
                  <div>
                    <dt>durationMs</dt>
                    <dd>{formatDuration(run?.durationMs)}</dd>
                  </div>
                  <div>
                    <dt>citationCount</dt>
                    <dd>{run?.citationCount ?? "—"}</dd>
                  </div>
                  <div>
                    <dt>grounded</dt>
                    <dd>{groundedLabel(run?.grounded)}</dd>
                  </div>
                </dl>
              </section>
            </>
          )}
        </div>
      </aside>
    </>
  );
}

interface TooltipCoord { left: number; top: number }

/** 修订第 40 条：气泡加宽到 320px，对齐 Codex 会话预览卡片。 */
const TOOLTIP_WIDTH = 320;
/** 修订第 39 条：本会话目录气泡与条目固定间距 8px（左侧历史会话气泡仍为 10px）。 */
const OUTLINE_TOOLTIP_GAP = 8;
const OUTLINE_LOADING_HINT = "预览加载中…";
const OUTLINE_FAILED_HINT = "预览加载失败";
const OUTLINE_STREAMING_HINT = "AI 回答生成中…";
/** 修订第 41 条：导轨短横线默认长度（px），按序号循环，形成长短错落。 */
/** 修订第 43 条：导轨显示阈值（超过 5 个问题才出现）。 */
const TURN_RAIL_MIN_TURNS = 6;
/** 单条导轨自然高度：3px 线 + 上下各 6px 按钮内边距（须与 CSS 保持一致）。 */
const RAIL_ITEM_H = 15;
/** 导轨列上下内边距合计（CSS 24px × 2）。 */
const RAIL_LIST_PADDING_Y = 48;
const RAIL_GAP_MIN = 2;
const RAIL_GAP_MAX = 16;

/**
 * 气泡定位（修订第 37 条）：锚定在 hover 条目右侧、与条目顶部对齐，
 * 不跟随鼠标；右侧空间不足时翻转到条目左侧；纵向超出窗口时回拉。
 */
function tooltipAnchorStyle(
  el: HTMLElement,
  prefer: "right" | "left" = "right",
  gap = 10
): TooltipCoord & { width: number } {
  const rect = el.getBoundingClientRect();
  const spaceRight = window.innerWidth - 8 - rect.right;
  const spaceLeft = rect.left - 8;
  const placeRight = rect.right + gap;
  const placeLeft = rect.left - gap - TOOLTIP_WIDTH;
  let left: number;
  if (prefer === "right") {
    // 历史会话栏在左侧：优先把气泡放到条目右侧，空间不足翻转到左侧
    if (spaceRight >= gap + TOOLTIP_WIDTH) {
      left = placeRight;
    } else if (spaceLeft >= gap + TOOLTIP_WIDTH) {
      left = placeLeft;
    } else {
      // 两侧都放不下：停靠空间更大一侧并距视口边缘 8px，不遮挡目录条目
      left = spaceRight >= spaceLeft ? window.innerWidth - TOOLTIP_WIDTH - 8 : 8;
    }
  } else {
    // 本会话目录在右侧：优先把气泡放到条目左侧（聊天区方向），空间不足翻转到右侧
    if (spaceLeft >= gap + TOOLTIP_WIDTH) {
      left = placeLeft;
    } else if (spaceRight >= gap + TOOLTIP_WIDTH) {
      left = placeRight;
    } else {
      // 修订第 39 条：两侧都不足时停靠空间更大一侧，距视口边缘 8px
      left = spaceLeft >= spaceRight ? 8 : window.innerWidth - TOOLTIP_WIDTH - 8;
    }
  }
  left = Math.max(8, Math.min(left, window.innerWidth - TOOLTIP_WIDTH - 8));
  // 气泡内容最多两行，估算高度约 96px，防止底部超出窗口
  const estimatedHeight = 132;
  const top = Math.max(8, Math.min(rect.top, window.innerHeight - estimatedHeight - 8));
  return { left, top, width: TOOLTIP_WIDTH };
}

/** 会话内问答目录条目（修订第 38 条；第 39 条增加 order 用于懒加载配对）：每个用户提问一条。 */
interface TurnOutlineItem {
  /** 该问题是会话中的第几个用户提问（从 1 开始），与服务端详情按序配对。 */
  order: number;
  anchorId: string;
  question: string;
  /** 内存中该轮最新回答的纯文本摘要；流式中回答尚为空时为占位文案。 */
  answerPreview: string;
}

/** 目录气泡摘要缓存键：会话 id + 锚点，避免切换会话后串内容。 */
function turnCacheKey(sessionId: string | null, anchorId: string): string {
  return `${sessionId ?? "local"}:${anchorId}`;
}

/**
 * 修订第 39 条（数据方案 2 最新）：按“第 N 个用户提问”配对，
 * 取每轮最后一条非欢迎语 assistant 回答，并转成纯文本摘要。
 */
function pairLatestAnswers(messages: AssistantMessage[]): Map<number, string> {
  const result = new Map<number, string>();
  let order = 0;
  let latest = "";
  const flush = () => {
    if (order > 0 && latest.trim()) result.set(order, toPlainPreview(latest));
  };
  for (const message of messages) {
    if (message.role === "user") {
      flush();
      order += 1;
      latest = "";
    } else if (message.role === "assistant" && !message.synthetic && message.content.trim()) {
      latest = message.content;
    }
  }
  flush();
  return result;
}

export function AiAssistantPage() {
  const user = useAuthStore((state) => state.user);
  const major = user?.major ?? "通用";

  const [sessions, setSessions] = useState<AssistantSession[]>([]);
  const [currentSession, setCurrentSession] = useState<AssistantSession | null>(null);
  const [messages, setMessages] = useState<AssistantMessage[]>([welcomeMessage()]);
  const [input, setInput] = useState("");
  const [streaming, setStreaming] = useState(false);
  const [error, setError] = useState("");
  const [historyLoading, setHistoryLoading] = useState(true);
  const [menuSessionId, setMenuSessionId] = useState<string | null>(null);
  const [hoveredSessionId, setHoveredSessionId] = useState<string | null>(null);
  const [tooltipCoord, setTooltipCoord] = useState<TooltipCoord | null>(null);
  // 本会话问答导轨（修订第 38 条引入；第 42 条移入聊天面板内部左侧常驻）
  const [hoveredTurnId, setHoveredTurnId] = useState<string | null>(null);
  const [outlineTooltipCoord, setOutlineTooltipCoord] = useState<TooltipCoord | null>(null);
  // P0-2：本轮流式回答的「检索过程」事件（仅流式中展示，completed / 失败后清空）
  const [activeToolEvents, setActiveToolEvents] = useState<StreamToolEventPayload[]>([]);
  // P0-E2：本轮回答的 runId（completed 事件下发）；为空说明是旧会话/失败，隐藏「本次检索详情」
  const [lastRunId, setLastRunId] = useState<string | null>(null);
  const [runDetailOpen, setRunDetailOpen] = useState(false);
  const [runDetail, setRunDetail] = useState<AgentRunDetail | null>(null);
  const [runDetailLoading, setRunDetailLoading] = useState(false);
  const [runDetailFailed, setRunDetailFailed] = useState(false);
  const [previewCache, setPreviewCache] = useState<Record<string, string>>({});
  const listRef = useRef<HTMLOListElement>(null);
  const historyListRef = useRef<HTMLDivElement>(null);
  const turnRailRef = useRef<HTMLDivElement>(null);
  const mountedRef = useRef(true);
  const composingRef = useRef(false);
  const previewCacheRef = useRef<Map<string, string>>(new Map());
  const previewInflightRef = useRef<Set<string>>(new Set());
  // 修订第 39 条：本会话目录气泡摘要懒加载缓存（键：会话 id + 锚点）
  const [turnPreviewCache, setTurnPreviewCache] = useState<Record<string, string>>({});
  const turnPreviewCacheRef = useRef<Map<string, string>>(new Map());
  const turnInflightRef = useRef<Set<string>>(new Set());

  const refreshSessions = useCallback(async (selectSessionId?: string) => {
    const list = await listAssistantSessions();
    if (!mountedRef.current) return list;
    setSessions(list);
    if (selectSessionId !== undefined) {
      const target = list.find((session) => session.id === selectSessionId);
      if (target) setCurrentSession(target);
    }
    return list;
  }, []);

  /** 修订第 36 条（交互微调）：悬停时按需拉取会话详情，只取最新一条非欢迎语消息做摘要并缓存。 */
  const ensurePreview = useCallback(async (sessionId: string) => {
    if (previewCacheRef.current.has(sessionId) || previewInflightRef.current.has(sessionId)) return;
    previewInflightRef.current.add(sessionId);
    try {
      const detail = await getAssistantSession(sessionId);
      if (!mountedRef.current) return;
      const latest = [...detail.messages].reverse().find((message) => !message.synthetic);
      const preview = latest ? toPlainPreview(latest.content) : "暂无对话内容";
      previewCacheRef.current.set(sessionId, preview);
      setPreviewCache(Object.fromEntries(previewCacheRef.current));
    } catch {
      if (!mountedRef.current) return;
      previewCacheRef.current.set(sessionId, "预览加载失败");
      setPreviewCache(Object.fromEntries(previewCacheRef.current));
    } finally {
      previewInflightRef.current.delete(sessionId);
    }
  }, []);

  const applySessionDetail = useCallback((detail: AssistantSessionDetail) => {
    setCurrentSession({
      id: detail.id,
      title: detail.title,
      createdAt: detail.createdAt,
      updatedAt: detail.updatedAt
    });
    setMessages(messagesFromDetail(detail));
  }, []);

  /** P0-E2：关闭抽屉并清空缓存（切换会话 / 新对话 / 重新提问时调用）。 */
  const closeRunDetail = useCallback(() => {
    setRunDetailOpen(false);
    setRunDetail(null);
    setRunDetailFailed(false);
    setRunDetailLoading(false);
  }, []);

  /**
   * P0-E2：点开「本次检索详情」时才按需拉取一次 run 详情。
   * 失败只写抽屉内的提示，不动全局 error，也不影响正在进行的聊天。
   */
  const openRunDetail = useCallback(async (runId: string) => {
    setRunDetailOpen(true);
    setRunDetail(null);
    setRunDetailFailed(false);
    setRunDetailLoading(true);
    try {
      const detail = await fetchAgentRun(runId);
      if (!mountedRef.current) return;
      setRunDetail(detail);
    } catch (reason) {
      if (!mountedRef.current) return;
      console.warn("[run-detail] 本次检索详情加载失败", reason);
      setRunDetailFailed(true);
    } finally {
      if (mountedRef.current) setRunDetailLoading(false);
    }
  }, []);

  // P0-E2：抽屉打开时按 Esc 关闭
  useEffect(() => {
    if (!runDetailOpen) return;
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") setRunDetailOpen(false);
    };
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [runDetailOpen]);

  const openSession = useCallback(async (sessionId: string) => {
    if (streaming) return;
    setError("");
    const detail = await getAssistantSession(sessionId);
    if (!mountedRef.current) return;
    window.localStorage.setItem(SESSION_ID_KEY, sessionId);
    applySessionDetail(detail);
    // P0-E2：历史会话没有 completed 事件里的 runId，按钮隐藏
    setLastRunId(null);
    closeRunDetail();
  }, [applySessionDetail, closeRunDetail, streaming]);

  useEffect(() => {
    mountedRef.current = true;
    async function bootstrap() {
      setHistoryLoading(true);
      try {
        const list = await listAssistantSessions();
        if (!mountedRef.current) return;
        setSessions(list);
        const savedId = window.localStorage.getItem(SESSION_ID_KEY);
        const target = list.find((session) => session.id === savedId) ?? list[0];
        if (target) {
          const detail = await getAssistantSession(target.id);
          if (!mountedRef.current) return;
          window.localStorage.setItem(SESSION_ID_KEY, target.id);
          applySessionDetail(detail);
        }
      } catch (reason) {
        if (mountedRef.current) {
          setError(reason instanceof Error ? reason.message : "历史对话加载失败");
        }
      } finally {
        if (mountedRef.current) setHistoryLoading(false);
      }
    }
    void bootstrap();
    return () => {
      mountedRef.current = false;
    };
  }, [applySessionDetail]);

  useEffect(() => {
    listRef.current?.scrollTo({ top: listRef.current.scrollHeight, behavior: "smooth" });
  }, [messages, streaming]);

  async function handleDeleteSession(sessionId: string) {
    if (streaming) return;
    setMenuSessionId(null);
    const target = sessions.find((session) => session.id === sessionId);
    const confirmed = window.confirm(`确定删除会话「${target?.title ?? ""}」吗？删除后该会话的全部消息不可恢复。`);
    if (!confirmed) return;
    try {
      setError("");
      await deleteAssistantSession(sessionId);
      const remaining = sessions.filter((session) => session.id !== sessionId);
      setSessions(remaining);
      if (currentSession?.id === sessionId) {
        window.localStorage.removeItem(SESSION_ID_KEY);
        setCurrentSession(null);
        setMessages([welcomeMessage()]);
        setInput("");
        setLastRunId(null);
        closeRunDetail();
      }
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : "删除会话失败");
    }
  }

  function startNewChat() {
    if (streaming) return;
    setMenuSessionId(null);
    window.localStorage.removeItem(SESSION_ID_KEY);
    setCurrentSession(null);
    setMessages([welcomeMessage()]);
    setInput("");
    setError("");
    // P0-E2：新对话没有 runId，隐藏「本次检索详情」按钮并关掉抽屉
    setLastRunId(null);
    closeRunDetail();
  }

  async function sendMessage() {
    const question = input.trim();
    if (!question || streaming) return;
    if (question.length > 4000) {
      setError("单次提问不能超过 4000 字，请拆成几个问题。");
      return;
    }

    setError("");
    setStreaming(true);
    setActiveToolEvents([]);
    // P0-E2：新一轮开始，上一轮的 runId 与抽屉内容立即作废
    setLastRunId(null);
    closeRunDetail();

    try {
      let session = currentSession;
      if (!session) {
        session = await createAssistantSession();
        if (!mountedRef.current) return;
        window.localStorage.setItem(SESSION_ID_KEY, session.id);
        setCurrentSession(session);
      }

      const targetSession = session;
      setMessages((current) => [...current, localMessage("user", question)]);
      setMessages((current) => [...current, localMessage("assistant", "")]);
      setInput("");

      let answer = "";
      await streamAssistantMessage(targetSession.id, question, {
        onDelta: (delta) => {
          answer += delta;
          if (!mountedRef.current) return;
          setMessages((current) => {
            const next = [...current];
            const last = next[next.length - 1];
            if (last && last.role === "assistant") {
              next[next.length - 1] = { ...last, content: answer };
            }
            return next;
          });
        },
        onToolEvent: (event) => {
          if (!mountedRef.current) return;
          if (event.phase === "failed" && event.errorCode) {
            // errorCode 只用于排查，不展示给用户
            console.debug("[tool_call] 工具调用失败", event.tool, event.errorCode);
          }
          setActiveToolEvents((current) => mergeToolEvent(current, event));
        },
        onCompleted: async (payload) => {
          if (!mountedRef.current) return;
          // P0-E2：completed 事件末尾的 runId（对外混淆串）；旧后端没有该字段时为 null
          setLastRunId(payload?.runId ?? null);
          const detail = await getAssistantSession(targetSession.id);
          if (!mountedRef.current) return;
          applySessionDetail(detail);
          setActiveToolEvents([]);
          await refreshSessions(detail.id);
        }
      });
    } catch (reason) {
      setActiveToolEvents([]);
      setError(reason instanceof Error ? reason.message : "AI 流式回答失败");
      setMessages((current) => {
        const next = [...current];
        const last = next[next.length - 1];
        if (last && last.role === "assistant" && last.content === "") {
          next.pop();
        }
        return next;
      });
    } finally {
      // 检索过程只是流式提示，结束后不残留
      setActiveToolEvents([]);
      if (mountedRef.current) setStreaming(false);
    }
  }

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    void sendMessage();
  }

  function handleInputKeyDown(event: ReactKeyboardEvent<HTMLTextAreaElement>) {
    if (
      event.key === "Enter"
      && !event.shiftKey
      && !event.nativeEvent.isComposing
      && !composingRef.current
    ) {
      event.preventDefault();
      void sendMessage();
    }
  }

  /** 本会话目录：每个用户提问一条，摘要取该问题后第一条 AI 回答。 */
  const turns = useMemo<TurnOutlineItem[]>(() => {
    const items: TurnOutlineItem[] = [];
    for (let i = 0; i < messages.length; i++) {
      const message = messages[i];
      if (message.role !== "user") continue;
      // 修订第 39 条（2 最新）：取该问题之后、下一条用户消息之前最新一条非空 AI 回答
      let answer = "";
      for (let j = i + 1; j < messages.length; j++) {
        if (messages[j].role === "user") break;
        if (messages[j].role === "assistant" && !messages[j].synthetic && messages[j].content.trim()) {
          answer = messages[j].content;
        }
      }
      items.push({
        order: items.length + 1,
        anchorId: `assistant-turn-${i}`,
        question: message.content,
        answerPreview: answer.trim() ? toPlainPreview(answer) : OUTLINE_STREAMING_HINT
      });
    }
    return items;
  }, [messages]);

  const hoveredTurn = hoveredTurnId === null
    ? null
    : turns.find((turn) => turn.anchorId === hoveredTurnId) ?? null;
  /** 修订第 41 条：当前悬停条目下标，用于导轨相邻线条联动变长。 */
  const hoveredTurnIndex = hoveredTurn ? turns.indexOf(hoveredTurn) : null;
  /** 修订第 43 条：导轨间距实时计算（条目少时居中且宽松，条目多时自动压缩）。 */
  const [railLayout, setRailLayout] = useState<{ gap: number; centered: boolean }>({ gap: RAIL_GAP_MAX, centered: true });

  /**
   * 修订第 39 条（1A 悬停懒加载 + 2 最新回答）：
   * 首次悬停先用当前内存里的最新回答即时填充；内存暂无（流式中/详情未就绪）时
   * 显示“预览加载中…”，并按需拉取一次会话详情，按“第 N 个用户提问”配对取最新回答。
   */
  const ensureTurnPreview = useCallback(async (turn: TurnOutlineItem, sessionId: string | null) => {
    const key = turnCacheKey(sessionId, turn.anchorId);
    if (turn.answerPreview && turn.answerPreview !== OUTLINE_STREAMING_HINT) {
      if (turnPreviewCacheRef.current.get(key) !== turn.answerPreview) {
        turnPreviewCacheRef.current.set(key, turn.answerPreview);
        setTurnPreviewCache(Object.fromEntries(turnPreviewCacheRef.current));
      }
      return;
    }
    if (sessionId === null || turnInflightRef.current.has(key)) return;
    turnInflightRef.current.add(key);
    try {
      const detail = await getAssistantSession(sessionId);
      if (!mountedRef.current) return;
      const latest = pairLatestAnswers(detail.messages).get(turn.order);
      if (latest) {
        turnPreviewCacheRef.current.set(key, latest);
        setTurnPreviewCache(Object.fromEntries(turnPreviewCacheRef.current));
      }
    } catch {
      if (!mountedRef.current) return;
      turnPreviewCacheRef.current.set(key, OUTLINE_FAILED_HINT);
      setTurnPreviewCache(Object.fromEntries(turnPreviewCacheRef.current));
    } finally {
      turnInflightRef.current.delete(key);
    }
  }, []);

  // 流式增量/会话详情刷新后，若鼠标仍停留在条目上，自动把摘要刷新为最新内容
  useEffect(() => {
    if (hoveredTurnId === null) return;
    const turn = turns.find((item) => item.anchorId === hoveredTurnId);
    if (turn) void ensureTurnPreview(turn, currentSession?.id ?? null);
  }, [messages, hoveredTurnId, turns, currentSession, ensureTurnPreview]);

  function jumpToTurn(anchorId: string) {
    document.getElementById(anchorId)?.scrollIntoView({ behavior: "smooth", block: "start" });
  }

  const hoveredSession = hoveredSessionId === null
    ? null
    : sessions.find((session) => session.id === hoveredSessionId) ?? null;

  // 导轨气泡锚定条目（第 42 条起导轨在聊天区左侧，气泡优先弹向条目右侧）：滚动或窗口尺寸变化时重新计算位置
  useEffect(() => {
    if (hoveredTurnId === null) return;
    const recompute = () => {
      const el = turnRailRef.current?.querySelector<HTMLElement>(
        `[data-turn-anchor="${hoveredTurnId}"]`
      );
      if (!el) {
        setOutlineTooltipCoord(null);
        return;
      }
      setOutlineTooltipCoord(tooltipAnchorStyle(el, "right", OUTLINE_TOOLTIP_GAP));
    };
    window.addEventListener("resize", recompute);
    window.addEventListener("scroll", recompute, true);
    return () => {
      window.removeEventListener("resize", recompute);
      window.removeEventListener("scroll", recompute, true);
    };
  }, [hoveredTurnId]);

  // 修订第 43 条：导轨整体垂直居中；条目间距随问题数量/列高自动压缩，超出时顶部对齐并可滚动
  useEffect(() => {
    if (turns.length < TURN_RAIL_MIN_TURNS) return;
    const el = turnRailRef.current;
    if (!el) return;
    const compute = () => {
      const n = turns.length;
      const available = el.clientHeight - RAIL_LIST_PADDING_Y;
      const contentH = n * RAIL_ITEM_H;
      if (available <= contentH + (n - 1) * RAIL_GAP_MIN) {
        // 内容放不下：间距压到最小，顶部对齐，列内滚动
        setRailLayout({ gap: RAIL_GAP_MIN, centered: false });
      } else {
        const gap = (available - contentH) / Math.max(1, n - 1);
        setRailLayout({ gap: Math.max(RAIL_GAP_MIN, Math.min(RAIL_GAP_MAX, gap)), centered: true });
      }
    };
    compute();
    const observer = new ResizeObserver(compute);
    observer.observe(el);
    return () => observer.disconnect();
  }, [turns.length]);
  // 历史气泡锚定条目：列表滚动或窗口尺寸变化时重新计算位置，保证始终贴着条目且不出屏
  useEffect(() => {
    if (hoveredSessionId === null) return;
    const recompute = () => {
      const el = historyListRef.current?.querySelector<HTMLElement>(
        `[data-session-id="${hoveredSessionId}"]`
      );
      if (!el) {
        setTooltipCoord(null);
        return;
      }
      setTooltipCoord(tooltipAnchorStyle(el));
    };
    window.addEventListener("resize", recompute);
    window.addEventListener("scroll", recompute, true);
    return () => {
      window.removeEventListener("resize", recompute);
      window.removeEventListener("scroll", recompute, true);
    };
  }, [hoveredSessionId]);

  return (
    <main className="page-workspace assistant-workspace">
      <section className="assistant-page with-history">
        <aside className="assistant-history">
          <div className="assistant-history-title">
            <strong>历史对话</strong>
            <button type="button" onClick={startNewChat} disabled={streaming}>新对话</button>
          </div>
          {historyLoading && <p className="assistant-history-hint">正在加载...</p>}
          {!historyLoading && sessions.length === 0 && (
            <p className="assistant-history-hint">还没有历史对话</p>
          )}
          <div className="assistant-history-list" ref={historyListRef}>
            {sessions.map((session) => (
              <div
                key={session.id}
                data-session-id={session.id}
                className={
                  (currentSession?.id === session.id ? "assistant-history-item active" : "assistant-history-item")
                  + (menuSessionId === session.id ? " menu-open" : "")
                }
                onMouseEnter={(event) => {
                  setHoveredSessionId(session.id);
                  setTooltipCoord(tooltipAnchorStyle(event.currentTarget));
                  void ensurePreview(session.id);
                }}
                onMouseLeave={() => {
                  setHoveredSessionId(null);
                  setTooltipCoord(null);
                }}
              >
                <button
                  type="button"
                  className="assistant-history-open"
                  onClick={() => void openSession(session.id)}
                  disabled={streaming}
                >
                  <strong>{session.title}</strong>
                  <em>{formatTime(session.updatedAt)}</em>
                </button>
                <button
                  type="button"
                  className="kebab-trigger assistant-kebab-trigger"
                  title="更多操作"
                  aria-label={`会话操作 ${session.title}`}
                  disabled={streaming}
                  onClick={() => setMenuSessionId(menuSessionId === session.id ? null : session.id)}
                >
                  ⋮
                </button>
                {menuSessionId === session.id && (
                  <>
                    <div
                      className="note-card-menu-overlay"
                      onClick={() => setMenuSessionId(null)}
                    />
                    <div className="assistant-history-menu">
                      <button
                        type="button"
                        className="danger"
                        onClick={() => void handleDeleteSession(session.id)}
                      >
                        删除会话
                      </button>
                    </div>
                  </>
                )}
              </div>
            ))}
          </div>
        </aside>

        <section className="assistant-chat-pane">
          <header className="assistant-header">
            <div>
              <h2>AI 知识助手</h2>
              <p>当前专业：<strong>{major}</strong>。切换页面后，历史对话会自动保留。</p>
            </div>
          </header>

          <div className="assistant-chat-body">
            {turns.length >= TURN_RAIL_MIN_TURNS && (
            <nav className="assistant-turn-rail" aria-label="本会话问答导轨">
              <div
                className={`assistant-turn-rail-list${hoveredTurnIndex !== null ? " is-railing" : ""}`}
                ref={turnRailRef}
                style={{
                  gap: `${railLayout.gap}px`,
                  justifyContent: railLayout.centered ? "center" : "flex-start"
                }}
              >
                {turns.map((turn, index) => {
                  // 修订第 41 条：悬停条目最长最深，上下相邻第 1/2 条逐级变长
                  const distance = hoveredTurnIndex === null
                    ? null
                    : Math.abs(index - hoveredTurnIndex);
                  const levelClass = distance === null
                    ? ""
                    : distance === 0
                      ? " is-hover"
                      : distance === 1
                        ? " near-1"
                        : distance === 2
                          ? " near-2"
                          : "";
                  return (
                    <button
                      key={turn.anchorId}
                      type="button"
                      className={`assistant-turn-rail-item${levelClass}`}
                      data-turn-anchor={turn.anchorId}
                      aria-label={`跳转到第 ${index + 1} 个问题：${turn.question}`}
                      onMouseEnter={(event) => {
                        setHoveredTurnId(turn.anchorId);
                        // 第 42 条：导轨在聊天区左侧，气泡弹向条目右侧
                        setOutlineTooltipCoord(tooltipAnchorStyle(event.currentTarget, "right", OUTLINE_TOOLTIP_GAP));
                        void ensureTurnPreview(turn, currentSession?.id ?? null);
                      }}
                      onMouseLeave={() => {
                        setHoveredTurnId(null);
                        setOutlineTooltipCoord(null);
                      }}
                      onClick={() => jumpToTurn(turn.anchorId)}
                    >
                      <span className="assistant-turn-rail-line" />
                    </button>
                  );
                })}
              </div>
            </nav>
            )}

            <ol className="assistant-message-list" ref={listRef}>
            {messages.map((message, index) => (
              <li
                key={message.id ?? `local-${index}`}
                id={message.role === "user" ? `assistant-turn-${index}` : undefined}
                className={message.role === "user" ? "assistant-message user" : "assistant-message ai"}
              >
                <div className="assistant-message-avatar">
                  {message.role === "user" ? "我" : "AI"}
                </div>
                <div className="assistant-bubble">
                  {message.role === "assistant" ? (
                    <>
                      {streaming && index === messages.length - 1 && activeToolEvents.length > 0 && (
                        <ToolEventTimeline events={activeToolEvents} />
                      )}
                      <MarkdownAnswer content={message.content} />
                      {/* P0-E2：只有拿到 completed.runId 的本轮回答才显示入口 */}
                      {lastRunId && !streaming && index === messages.length - 1 && (
                        <div className="assistant-run-actions">
                          <button
                            type="button"
                            className="assistant-run-detail-trigger"
                            onClick={() => void openRunDetail(lastRunId)}
                          >
                            本次检索详情
                          </button>
                        </div>
                      )}
                    </>
                  ) : (
                    <p>{message.content}</p>
                  )}
                </div>
              </li>
            ))}
            </ol>
          </div>

          {error && <div className="assistant-error" role="alert">{error}</div>}

          <form className="assistant-input-row" onSubmit={handleSubmit}>
            <textarea
              value={input}
              onChange={(event) => setInput(event.target.value)}
              onKeyDown={handleInputKeyDown}
              onCompositionStart={() => { composingRef.current = true; }}
              onCompositionEnd={() => { composingRef.current = false; }}
              placeholder={`请输入关于${major}的理论问题，例如：请解释进程和线程的区别。Enter 发送，Shift+Enter 换行`}
              maxLength={4000}
              rows={3}
              disabled={streaming}
            />
            <button className="primary" type="submit" disabled={streaming || !input.trim()}>
              {streaming ? "回答中" : "发送"}
            </button>
          </form>
          <p className="assistant-footnote">
            Enter 发送，Shift+Enter 换行；历史对话已保存在当前账号下，AI 回答请结合教材核对。
          </p>
        </section>
      </section>
      {runDetailOpen && createPortal(
        <RunDetailDrawer
          detail={runDetail}
          loading={runDetailLoading}
          failed={runDetailFailed}
          onClose={() => setRunDetailOpen(false)}
        />,
        document.body
      )}
      {hoveredTurn && outlineTooltipCoord && createPortal(
        <div
          className="assistant-history-tooltip assistant-turn-rail-tooltip"
          style={{ left: outlineTooltipCoord.left, top: outlineTooltipCoord.top, width: TOOLTIP_WIDTH }}
        >
          <strong>{hoveredTurn.question}</strong>
          {(() => {
            const preview = turnPreviewCache[turnCacheKey(currentSession?.id ?? null, hoveredTurn.anchorId)];
            return preview
              ? <TooltipPreviewBody text={preview} />
              : <TooltipPreviewBody text={OUTLINE_LOADING_HINT} loading />;
          })()}
        </div>,
        document.body
      )}
      {hoveredSession && menuSessionId !== hoveredSession.id && tooltipCoord && createPortal(
        <div
          className="assistant-history-tooltip"
          style={{ left: tooltipCoord.left, top: tooltipCoord.top, width: TOOLTIP_WIDTH }}
        >
          <strong>{hoveredSession.title}</strong>
          {previewCache[hoveredSession.id]
            ? <TooltipPreviewBody text={previewCache[hoveredSession.id]} />
            : <TooltipPreviewBody text="预览加载中…" loading />}
        </div>,
        document.body
      )}
    </main>
  );
}
