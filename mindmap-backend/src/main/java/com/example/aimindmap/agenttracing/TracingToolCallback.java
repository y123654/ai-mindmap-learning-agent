package com.example.aimindmap.agenttracing;

import com.example.aimindmap.agenttools.AgentToolFailureException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.ai.tool.metadata.ToolMetadata;

/**
 * ToolCallback 装饰器：统一做调用次数上限拦截、结果长度截断与 agent_tool_calls 落库。
 * 同一 run 的全部装饰器共享一个 AgentToolCallGuard。
 *
 * <p>P0-2：执行前 / 成功 / 失败分别向 ToolContext 里的 {@link AgentToolEventSink}
 * 推送 start / succeed / failed 事件；sink 缺失时走空实现，旧调用方零改动。
 * 事件推送失败绝不影响工具执行（sink 内部吞异常，这里再加一层兜底）。</p>
 *
 * <p>P1-D1（方案 §P1-4）：
 * <ul>
 *   <li><b>预算软降级</b>：{@code app.agent.tool-budget-soft=true}（默认）时，名额用完不抛异常，
 *       直接返回 {@link #TOOL_BUDGET_EXHAUSTED_TEXT}；不落 {@code agent_tool_calls} 行、
 *       不推高 {@code guard.acquiredCount()}，只发一条 failed 事件并打 warn。
 *       置 false 则恢复"第 max+1 次抛 {@link AgentRunAbortException}（AGENT_TOOL_LIMIT_EXCEEDED）"。</li>
 *   <li><b>单工具超时</b>：{@code app.agent.tool-timeout-seconds}（默认 10s），用虚拟线程 + 定时等待
 *       包住 delegate 调用；超时中断并转成 {@code AGENT_TOOL_TIMEOUT}（fatal=false、retryable=true）。</li>
 *   <li><b>1 次重试</b>：仅 {@code retryable=true}、{@code fatal=false} 且不是参数错误的失败，等 200ms
 *       重试 1 次；重试不额外占用调用名额，成功时只落一行 {@code agent_tool_calls}、只发一对 start/succeed。
 *       参数错误（{@code AGENT_ARGUMENT_INVALID}）<b>不重试</b>——同一组参数重跑必然同样失败，纠错重试由模型完成。</li>
 *   <li><b>P1-D2b 预算门控</b>：重试前读 {@code ToolContext} 的
 *       {@link AgentToolContextKeys#REMAINING_BUDGET_MS}；若“剩余预算 ≤ 200ms 退避 + 一次完整工具超时”
 *       （即再跑一次也跑不完），<b>不重试</b>，直接把原来的 retryable 失败文本交给模型。
 *       该 key 不存在（老调用方 / 老单测）时保持“重试 1 次”的既有行为。</li>
 *   <li><b>参数纠错</b>：retryable 的失败不中止整轮，而是把带纠错提示的文本交给模型
 *       （{@code AGENT_ARGUMENT_INVALID} 用"参数不合法：…请检查参数格式后重试一次。"）。</li>
 * </ul>
 */
public final class TracingToolCallback implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(TracingToolCallback.class);

    /** 预算用完时交给模型的原话（方案 §P1-4 原文，一字不改）。 */
    public static final String TOOL_BUDGET_EXHAUSTED_TEXT =
            "工具调用预算已用完。请基于已有检索结果作答；若无足够依据，请明确说明。";

    /** 单工具超时错误码（retryable）。 */
    public static final String CODE_TOOL_TIMEOUT = "AGENT_TOOL_TIMEOUT";

    /** 参数非法错误码：只有它会在失败文本前加"参数不合法"纠错提示。 */
    static final String CODE_ARGUMENT_INVALID = "AGENT_ARGUMENT_INVALID";

    /** 可重试失败的退避毫秒数（方案 §P1-4：1 次指数退避 200ms，只重试 1 次）。 */
    static final long RETRY_DELAY_MS = 200L;

    /**
     * P1-E3b：证据块的开/闭标签字面量。
     *
     * <p>这里<b>刻意</b>不引用 {@code EvidenceEnvelope}：截断逻辑只需要认“字面量标记”，
     * 引过去会把 agentruntime 的包装实现和 tracing 绑定在一起；两边都有单测钉住同一串字符即可
     * （{@code EvidenceEnvelopeTest} 断言格式，{@code TracingToolCallbackTest} 断言补齐）。
     */
    static final String EVIDENCE_TAG_PREFIX = "<evidence";

    /** 证据块闭标签字面量。 */
    static final String EVIDENCE_TAG_CLOSE = "</evidence>";

    private final ToolCallback delegate;
    private final AgentTracingService tracingService;
    private final AgentToolCallGuard guard;
    private final AgentTracingProperties properties;

    public TracingToolCallback(ToolCallback delegate,
                               AgentTracingService tracingService,
                               AgentToolCallGuard guard,
                               AgentTracingProperties properties) {
        this.delegate = delegate;
        this.tracingService = tracingService;
        this.guard = guard;
        this.properties = properties;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return delegate.getToolMetadata();
    }

    @Override
    public String call(String toolInput) {
        return call(toolInput, null);
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        Long userId = readLong(toolContext, AgentToolContextKeys.USER_ID);
        Long runId = readLong(toolContext, AgentToolContextKeys.RUN_ID);
        String toolName = delegate.getToolDefinition().name();
        AgentToolEventSink sink = readSink(toolContext);
        String label = AgentToolDisplay.label(toolName);
        String detail = AgentToolDisplay.detail(toolName, toolInput);

        if (!acquireBudget(runId, userId, toolName, sink, label, detail)) {
            // 软降级：预算已满不是一次真实调用——不落库、不占名额，只把固定提示交回模型
            return TOOL_BUDGET_EXHAUSTED_TEXT;
        }

        Long toolCallId = tracingService.startToolCall(runId, userId, toolName, toolInput);
        // 一次工具调用只分配一个 seq，start 与结束事件共用，前端据此合并（重试不重新发 start）
        long seq = sink.nextSeq();
        emitQuietly(sink, AgentToolCallEvent.start(seq, toolName, label, detail), runId);
        long startedNanos = System.nanoTime();
        try {
            String result = truncateResult(
                    callWithRetry(toolInput, toolContext, runId, userId, toolName));
            long durationMs = elapsedMs(startedNanos);
            tracingService.succeedToolCall(toolCallId, result, durationMs);
            emitQuietly(sink, AgentToolCallEvent.succeed(seq, toolName, label, detail,
                    Boolean.FALSE, durationMs), runId);
            return result;
        } catch (AgentToolFailureException failure) {
            long durationMs = elapsedMs(startedNanos);
            tracingService.failToolCall(toolCallId, failure.code() + ": " + failure.getMessage(),
                    durationMs);
            emitQuietly(sink, AgentToolCallEvent.failed(seq, toolName, label, detail,
                    failure.code()), runId);
            return handleToolFailure(failure, runId, userId, toolName, durationMs);
        } catch (AgentRunAbortException e) {
            long durationMs = elapsedMs(startedNanos);
            tracingService.failToolCall(toolCallId, e.getMessage(), durationMs);
            emitQuietly(sink, AgentToolCallEvent.failed(seq, toolName, label, detail,
                    e.code()), runId);
            log.warn("工具调用中止（透传） runId={} userId={} toolName={} code={} durationMs={}",
                    runId, userId, toolName, e.code(), durationMs);
            throw e;
        } catch (RuntimeException e) {
            long durationMs = elapsedMs(startedNanos);
            tracingService.failToolCall(toolCallId, e.toString(), durationMs);
            emitQuietly(sink, AgentToolCallEvent.failed(seq, toolName, label, detail,
                    "AGENT_TOOL_FAILED"), runId);
            log.error("工具调用未预期异常 runId={} userId={} toolName={} durationMs={}",
                    runId, userId, toolName, durationMs, e);
            throw new AgentRunAbortException("AGENT_TOOL_FAILED", "工具执行失败，请稍后重试", e);
        }
    }

    /**
     * 占用一次工具调用名额。
     *
     * @return true 可以执行；false 预算已满且当前是软降级模式（调用方直接返回固定提示文本）
     * @throws AgentRunAbortException 硬中止模式（{@code tool-budget-soft=false}）下预算已满
     */
    private boolean acquireBudget(Long runId, Long userId, String toolName,
                                  AgentToolEventSink sink, String label, String detail) {
        if (properties.isToolBudgetSoft()) {
            if (guard.tryAcquireSoft()) {
                return true;
            }
            // 软降级不落 agent_tool_calls、不推高计数，但要留一条 failed 事件 + 日志便于排查
            log.warn("工具调用预算已用完（软降级，不落库） runId={} userId={} toolName={} max={} acquired={}",
                    runId, userId, toolName, properties.getMaxToolCalls(), guard.acquiredCount());
            emitQuietly(sink, AgentToolCallEvent.failed((long) sink.nextSeq(), toolName, label,
                    detail, "AGENT_TOOL_LIMIT_EXCEEDED"), runId);
            return false;
        }

        if (guard.tryAcquire()) {
            return true;
        }
        log.warn("工具调用次数超限被拦截 runId={} sessionId 见上下文 userId={} toolName={} acquired={}",
                runId, userId, toolName, guard.acquiredCount());
        emitQuietly(sink, AgentToolCallEvent.failed((long) sink.nextSeq(), toolName, label,
                detail, "AGENT_TOOL_LIMIT_EXCEEDED"), runId);
        throw new AgentRunAbortException("AGENT_TOOL_LIMIT_EXCEEDED",
                "本次提问工具调用已超过上限（" + properties.getMaxToolCalls()
                        + " 次），已自动中止，请把问题拆小后重试");
    }

    /**
     * 执行一次工具调用；失败若值得重试（retryable + 非致命 + 非参数错误）<b>且剩余预算够再跑一次</b>，
     * 则退避 {@link #RETRY_DELAY_MS} 后重试 1 次。
     *
     * <p>重试<b>不再调用 guard</b>，因此不额外占用调用名额；重试与首次调用共用同一个
     * {@code toolCallId} 与 seq，落库仍是一行。</p>
     *
     * <p>P1-D2b：预算不够时把首次失败原样抛出，由 {@link #handleToolFailure} 走现有
     * “非致命 + retryable → 返回提示文本给模型”的路径，<b>不中止整轮</b>。</p>
     */
    private String callWithRetry(String toolInput, ToolContext toolContext, Long runId,
                                 Long userId, String toolName) {
        try {
            return executeWithTimeout(toolInput, toolContext);
        } catch (AgentToolFailureException failure) {
            if (!isRetryWorthwhile(failure)) {
                throw failure;
            }
            if (!hasBudgetForRetry(toolContext, runId, toolName)) {
                throw failure;
            }
            log.warn("工具调用失败（retryable），{}ms 后重试 1 次（不占名额） runId={} userId={} toolName={} code={}",
                    RETRY_DELAY_MS, runId, userId, toolName, failure.code());
            sleepBackoff(runId, toolName);
            return executeWithTimeout(toolInput, toolContext);
        }
    }

    /**
     * P1-D2b：剩余总预算是否够再跑一次完整尝试（200ms 退避 + 一次工具超时）。
     *
     * <p>判定口径严格按规格：{@code 剩余预算 <= RETRY_DELAY_MS + toolTimeoutSeconds*1000} → 不重试。
     * 之所以要把退避也算进去：退避本身也在消耗同一份 run 预算，只算工具超时会让最后一次尝试
     * 刚刚好跑到预算之外。
     *
     * <p>{@code ToolContext} 里没有 {@link AgentToolContextKeys#REMAINING_BUDGET_MS}（老调用方/单测）
     * 或值不是数字时<b>返回 true</b>，保持“重试 1 次”的既有行为，避免破坏既有用例。
     */
    private boolean hasBudgetForRetry(ToolContext toolContext, Long runId, String toolName) {
        Long remainingMs = readRemainingBudgetMs(toolContext);
        if (remainingMs == null) {
            return true;
        }
        long requiredMs = RETRY_DELAY_MS + toolTimeoutMillis();
        if (remainingMs > requiredMs) {
            return true;
        }
        log.warn("因剩余预算不足跳过工具重试 runId={} toolName={} remainingMs={} requiredMs={} "
                        + "（退避 {}ms + 单工具超时 {}ms）",
                runId, toolName, remainingMs, requiredMs, RETRY_DELAY_MS, toolTimeoutMillis());
        return false;
    }

    /** 读取本轮剩余预算快照；缺失或类型不认识时返回 null（= 不做预算门控）。 */
    private static Long readRemainingBudgetMs(ToolContext toolContext) {
        if (toolContext == null || toolContext.getContext() == null) {
            return null;
        }
        Object value = toolContext.getContext().get(AgentToolContextKeys.REMAINING_BUDGET_MS);
        if (value instanceof Number number) {
            return Math.max(0L, number.longValue());
        }
        return null;
    }

    /** 单工具超时毫秒数（与 {@link #executeWithTimeout} 用同一个下限，保证两处口径一致）。 */
    private long toolTimeoutMillis() {
        return Math.max(1L, properties.getToolTimeoutSeconds() * 1_000L);
    }

    /**
     * 是否值得用<b>同一组参数</b>再跑一次。
     *
     * <p>参数错误（{@code AGENT_ARGUMENT_INVALID}）明确排除：重试主体是模型，不是框架——
     * 同一组参数重跑必然同样失败，只会白花 200ms 与一次工具执行；框架直接返回
     * {@link #failureTextForModel} 的纠错文本，由模型改参数后重新发起调用。</p>
     */
    private static boolean isRetryWorthwhile(AgentToolFailureException failure) {
        return failure.retryable()
                && !failure.fatal()
                && !CODE_ARGUMENT_INVALID.equals(failure.code());
    }

    /**
     * 用虚拟线程 + 定时等待包住一次 delegate 调用（与 AgentPlanner/PlanExecutor 同一写法）。
     *
     * <p>包级可见是为了让单测直接断言超时异常（code=AGENT_TOOL_TIMEOUT、retryable=true），
     * 不改变对外契约。</p>
     */
    String executeWithTimeout(String toolInput, ToolContext toolContext) {
        FutureTask<String> task = new FutureTask<>(() -> delegate.call(toolInput, toolContext));
        Thread worker = Thread.ofVirtual().name("agent-tool").start(task);
        long timeoutMs = toolTimeoutMillis();
        try {
            return task.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            // 尽力让底层调用尽快退出；已经超时就不再等它
            worker.interrupt();
            task.cancel(true);
            log.warn("工具执行超时 toolName={} timeoutMs={}",
                    delegate.getToolDefinition().name(), timeoutMs);
            throw new AgentToolFailureException(CODE_TOOL_TIMEOUT,
                    "工具执行超时（超过 " + timeoutMs + " 毫秒），已中断", false, true, e);
        } catch (InterruptedException e) {
            worker.interrupt();
            task.cancel(true);
            Thread.currentThread().interrupt();
            log.warn("工具执行被中断 toolName={} timeoutMs={}",
                    delegate.getToolDefinition().name(), timeoutMs, e);
            throw new AgentRunAbortException("AGENT_RUN_INTERRUPTED", "工具执行被中断，已中止本轮", e);
        } catch (ExecutionException e) {
            throw normalizeFailure(e);
        }
    }

    /** 把 delegate 抛出的异常归一成 {@link AgentToolFailureException}（ToolExecutionException 拆一层）。 */
    private AgentToolFailureException normalizeFailure(ExecutionException e) {
        Throwable cause = e.getCause() == null ? e : e.getCause();
        if (cause instanceof ToolExecutionException toolException
                && toolException.getCause() != null) {
            cause = toolException.getCause();
        }
        if (cause instanceof AgentToolFailureException failure) {
            return failure;
        }
        if (cause instanceof AgentRunAbortException abort) {
            throw abort;
        }
        // 非工具自身声明的异常按致命处理：保持原“AGENT_TOOL_FAILED 中止整轮”的对外行为
        return new AgentToolFailureException("AGENT_TOOL_FAILED", "工具执行失败，请稍后重试",
                true, false, cause);
    }

    /**
     * 重试后仍失败的终态处理。
     *
     * @return 交给模型的错误文本（retryable 且非致命：不中止整轮）
     * @throws AgentRunAbortException 致命失败
     * @throws ToolExecutionException 非 retryable 的非致命失败（保持旧行为：由 ToolCallingManager
     *         转成工具错误文本）
     */
    private String handleToolFailure(AgentToolFailureException failure, Long runId, Long userId,
                                     String toolName, long durationMs) {
        if (failure.fatal()) {
            log.error("工具致命失败 runId={} userId={} toolName={} code={} durationMs={}",
                    runId, userId, toolName, failure.code(), durationMs, failure);
            throw new AgentRunAbortException(failure.code(), failure.getMessage(), failure);
        }
        if (failure.retryable()) {
            // 不写死“已重试 1 次”：P1-D2b 起剩余预算不足时不会重试，文案按“重试策略已结束”中性描述
            log.warn("工具可重试失败（重试策略已结束），返回纠错文本给模型 runId={} userId={} toolName={} code={} durationMs={}",
                    runId, userId, toolName, failure.code(), durationMs);
            return failureTextForModel(failure);
        }
        log.warn("工具业务失败 runId={} userId={} toolName={} code={} durationMs={}",
                runId, userId, toolName, failure.code(), durationMs);
        throw new ToolExecutionException(delegate.getToolDefinition(), failure);
    }

    /**
     * 可重试失败不再中止整轮，把原因交给模型自己决定下一步；
     * 参数类错误额外带"参数不合法 + 检查格式后重试一次"的纠错提示（方案 §P1-4）。
     */
    private static String failureTextForModel(AgentToolFailureException failure) {
        String code = failure.code() == null ? "" : failure.code();
        String reason = failure.getMessage() == null ? "" : failure.getMessage().strip();
        // 工具消息自带 "CODE: " 前缀时去掉，避免 "参数不合法：AGENT_ARGUMENT_INVALID: …" 重复
        String prefix = code.isEmpty() ? "" : code + ": ";
        if (!prefix.isEmpty() && reason.startsWith(prefix)) {
            reason = reason.substring(prefix.length()).strip();
        }
        if (CODE_ARGUMENT_INVALID.equals(code)) {
            return "参数不合法：" + reason + "。请检查参数格式后重试一次。";
        }
        return "工具调用失败（" + code + "）：" + reason
                + "。请调整调用方式后重试，或基于已有结果作答。";
    }

    /** 重试前的固定 200ms 退避；被中断说明整轮已被取消，直接中止。 */
    private static void sleepBackoff(Long runId, String toolName) {
        try {
            Thread.sleep(RETRY_DELAY_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("工具重试等待被中断，已中止本轮 runId={} toolName={}", runId, toolName, e);
            throw new AgentRunAbortException("AGENT_RUN_INTERRUPTED", "工具重试等待被中断，已中止本轮", e);
        }
    }

    /**
     * 事件推送只是过程展示，任何异常都不得改变工具执行结果；
     * sink 实现本身要求吞异常，这里兜底避免自定义 sink 破坏主流程。
     */
    private static void emitQuietly(AgentToolEventSink sink, AgentToolCallEvent event, Long runId) {
        try {
            sink.emit(event);
        } catch (RuntimeException e) {
            log.warn("工具过程事件推送失败（不影响工具执行） runId={} tool={} phase={} error={}",
                    runId, event.tool(), event.phase(), e.toString());
        }
    }

    /**
     * 结果超长截断（方案 §4.3 的 {@code app.agent.tool-result-max-chars}）。
     *
     * <p><b>P1-E3b 修正</b>：加了 {@code <evidence>} 包装之后，盲截会把
     * {@code <evidence id="E3" source="document_chunk:884201">…} 切成“半个标签”——
     * 模型看到的证据边界就不成立了。所以截断后要做一次<b>标签补齐</b>
     * （{@link #closeDanglingEvidence}），再追加截断说明。
     *
     * <p>没有 {@code <evidence} 时<b>完全走旧逻辑</b>（逐字节等于改造前的
     * {@code substring + 截断说明}），开关关/没有包装的路径不受影响。
     */
    private String truncateResult(String result) {
        int maxChars = properties.getToolResultMaxChars();
        if (result == null || result.length() <= maxChars) {
            return result;
        }
        log.warn("工具结果超长截断 toolName={} originalChars={} maxChars={}",
                delegate.getToolDefinition().name(), result.length(), maxChars);
        String head = closeDanglingEvidence(result.substring(0, maxChars));
        return head + "\n\n[系统提示：工具返回内容超过 " + maxChars
                + " 字符已截断，请缩小搜索范围或指定片段后重试]";
    }

    /**
     * P1-E3b：让截断片段里的 {@code <evidence>} 开闭成对，且不以“半个开标签”结尾。
     *
     * <p>规则（按顺序）：
     * <ol>
     *   <li>截断点落在某个开标签内部（最后一个 {@code <evidence} 之后没有 {@code >}，
     *       或末尾只剩 {@code <evi} 这种标签前缀）→ 把这段残缺开标签整个去掉；</li>
     *   <li>去掉后如果<b>开标签数 &gt; 闭标签数</b> → 在末尾补一个 {@code </evidence>}；</li>
     *   <li>直接把结果返回，调用方把“截断说明”接在后面 ⇒ 说明天然在所有标签之外。</li>
     * </ol>
     *
     * <p>不含 {@code <evidence} 的文本原样返回（不改变任何旧行为）。
     */
    static String closeDanglingEvidence(String head) {
        if (head == null || head.isEmpty() || !head.contains(EVIDENCE_TAG_PREFIX)) {
            return head;
        }
        String repaired = trimPartialOpenTag(head);
        int opens = countOccurrences(repaired, EVIDENCE_TAG_PREFIX);
        int closes = countOccurrences(repaired, EVIDENCE_TAG_CLOSE);
        if (opens > closes) {
            repaired = repaired + EVIDENCE_TAG_CLOSE;
        }
        return repaired;
    }

    /** 去掉末尾残缺的开标签：{@code <evidence ...}（未闭合）或 {@code <evi} 这种标签名前缀。 */
    private static String trimPartialOpenTag(String head) {
        int lastOpen = head.lastIndexOf(EVIDENCE_TAG_PREFIX);
        if (lastOpen >= 0 && head.indexOf('>', lastOpen) < 0) {
            // 整个 <evidence 开标签没写完（截断点落在标签内部）
            return head.substring(0, lastOpen);
        }
        int lastLt = head.lastIndexOf('<');
        if (lastLt >= 0) {
            String tail = head.substring(lastLt);
            // 截断点落在标签名中间："<", "<e", "<ev" … "<evidence"
            if (EVIDENCE_TAG_PREFIX.startsWith(tail)) {
                return head.substring(0, lastLt);
            }
        }
        return head;
    }

    private static int countOccurrences(String text, String marker) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(marker, index)) >= 0) {
            count++;
            index += marker.length();
        }
        return count;
    }

    private static long elapsedMs(long startedNanos) {
        return Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
    }

    private static AgentToolEventSink readSink(ToolContext toolContext) {
        if (toolContext == null || toolContext.getContext() == null) {
            return AgentToolEventSink.noop();
        }
        Object value = toolContext.getContext().get(AgentToolContextKeys.TOOL_EVENT_SINK);
        return value instanceof AgentToolEventSink sink ? sink : AgentToolEventSink.noop();
    }

    private static Long readLong(ToolContext toolContext, String key) {
        if (toolContext == null) {
            throw new AgentRunAbortException("AGENT_CONTEXT_MISSING", "缺少工具上下文");
        }
        Object value = toolContext.getContext().get(key);
        if (value instanceof Long longValue) {
            return longValue;
        }
        throw new AgentRunAbortException("AGENT_CONTEXT_MISSING", "工具上下文缺少必要参数：" + key);
    }
}
