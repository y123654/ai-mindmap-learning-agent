package com.example.aimindmap.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * 阶段八-1：非 MQ 类业务指标（方案 325 行第 1 项中的 ②PDF 上传 RT、③同步 RT、
 * ④AI/Embedding 耗时与失败率）。与 {@link MqTaskMetrics} 分工：那边管任务队列，
 * 这边管接口响应时间与外部模型调用。
 *
 * <p><b>指标清单（Prometheus 名 = 点换下划线，Counter 追加 {@code _total}，Timer 追加 {@code _seconds}）：</b>
 * <ul>
 *   <li>{@code aimindmap.pdf.upload}（Timer，无 tag）：PDF 上传受理耗时，
 *       埋在 {@code PdfImportService#uploadPdf}（校验 + 落盘 + 建任务行 + 同事务写 outbox）。
 *       只记受理成功的一次；被 400 拒绝的上传由自动装配的
 *       {@code http_server_requests_seconds{uri="/api/imports/pdf",status="400"}} 覆盖，不重复埋。</li>
 *   <li>{@code aimindmap.sync.job}（Timer，tag {@code outcome}）：同步任务处理主流程耗时，
 *       埋在 {@code SyncJobService#processJob}（MQ 消费者与 mq=false 的 @Async 兜底共用同一入口，
 *       一处埋点覆盖两条链路）；outcome 取 {@code ProcessOutcome} 枚举名小写，
 *       抛出未捕获异常时记 {@code exception}。</li>
 *   <li>{@code aimindmap.ai.call}（Timer，tag {@code operation} + {@code model} + {@code outcome}）
 *       与 {@code aimindmap.ai.call.failure}（Counter，tag 前两个 + {@code error_code}）：
 *       AI 网关三个入口（chat / chat_stream / json）的耗时与失败率，
 *       失败率 = failure 计数 / call 计数，或按 outcome 分组求比值。</li>
 *   <li>{@code aimindmap.embedding.call}（Timer，tag {@code operation} + {@code model} + {@code outcome}）
 *       与 {@code aimindmap.embedding.call.failure}（Counter，tag {@code operation} + {@code model}）：
 *       四个 embedding 调用点（向量索引 / 检索 / 记忆检索 / 记忆写点）。</li>
 * </ul>
 *
 * <p><b>埋点铁律：</b>tag 只用 operation / model / outcome / error_code 这类低基数枚举级值
 * （operation 是常量、model 来自配置、error_code 是网关内固定的字面量错误码集合），
 * 严禁 userId / sessionId / jobId / 提示词正文 / 密钥；所有 record* 方法纯记录，
 * 不改变业务分支与返回值，也不新增 catch —— 失败计数一律写进调用点<b>既有</b>的 catch 分支。
 */
@Component
public class BusinessMetrics {

    static final String PDF_UPLOAD_NAME = "aimindmap.pdf.upload";
    static final String SYNC_JOB_NAME = "aimindmap.sync.job";
    static final String AI_CALL_NAME = "aimindmap.ai.call";
    static final String AI_FAILURE_NAME = "aimindmap.ai.call.failure";
    static final String EMBEDDING_CALL_NAME = "aimindmap.embedding.call";
    static final String EMBEDDING_FAILURE_NAME = "aimindmap.embedding.call.failure";

    static final String TAG_OPERATION = "operation";
    static final String TAG_MODEL = "model";
    static final String TAG_OUTCOME = "outcome";
    static final String TAG_ERROR_CODE = "error_code";

    /** Timer 的 outcome 只有两值（失败原因看 failure 计数器的 error_code，避免 Timer 序列爆炸）。 */
    public static final String OUTCOME_SUCCESS = "success";
    public static final String OUTCOME_FAILURE = "failure";

    /** 同步主流程抛出未捕获异常时的 outcome（processJob 正常返回时用 ProcessOutcome 枚举名小写）。 */
    public static final String SYNC_OUTCOME_EXCEPTION = "exception";

    /** AI 网关入口 operation 取值（对应 streamChat / completeChat / completeJsonForResult）。 */
    public static final String AI_OP_CHAT_STREAM = "chat_stream";
    public static final String AI_OP_CHAT = "chat";
    public static final String AI_OP_JSON = "json";

    /** embedding 调用点 operation 取值（四处，全部低基数常量）。 */
    public static final String EMBEDDING_OP_INDEX = "index";
    public static final String EMBEDDING_OP_SEARCH = "search";
    public static final String EMBEDDING_OP_MEMORY_SEARCH = "memory_search";
    public static final String EMBEDDING_OP_MEMORY_UPSERT = "memory_upsert";

    /** 模型名缺失时的兜底 tag 值，避免产生空 tag。 */
    public static final String MODEL_UNKNOWN = "unknown";

    /** AI 错误码缺失时的兜底 tag 值。 */
    public static final String ERROR_CODE_UNKNOWN = "unknown";

    private final MeterRegistry registry;

    public BusinessMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** 计时起点（纳秒）：调用方在入口取一次、出口交回，业务代码不引入 micrometer 类型。 */
    public long startTimer() {
        return System.nanoTime();
    }

    /** ② PDF 上传受理成功（325 行「PDF 上传 RT」）。 */
    public void recordPdfUploadAccepted(long startedNanos) {
        registry.timer(PDF_UPLOAD_NAME).record(elapsed(startedNanos));
    }

    /** ③ 同步任务处理主流程结束（325 行「同步 RT」），outcome 见类注释。 */
    public void recordSyncJobProcess(String outcome, long startedNanos) {
        registry.timer(SYNC_JOB_NAME, TAG_OUTCOME, safe(outcome, SYNC_OUTCOME_EXCEPTION))
                .record(elapsed(startedNanos));
    }

    /** ④ AI 网关调用成功：记 Timer（outcome=success）。 */
    public void recordAiCallSuccess(String operation, String model, long startedNanos) {
        registry.timer(AI_CALL_NAME, TAG_OPERATION, safe(operation, "unknown"),
                        TAG_MODEL, safe(model, MODEL_UNKNOWN), TAG_OUTCOME, OUTCOME_SUCCESS)
                .record(elapsed(startedNanos));
    }

    /**
     * ④ AI 网关调用失败：记 Timer（outcome=failure）+ 失败计数器（带网关错误码）。
     * errorCode 取 {@code AiGatewayException#getCode()}，是网关内固定的字面量集合
     * （AI_TIMEOUT / AI_HTTP_xxx / AI_RESPONSE_INVALID / AI_OUTPUT_TRUNCATED / AI_NOT_CONFIGURED 等）。
     */
    public void recordAiCallFailure(String operation, String model, String errorCode, long startedNanos) {
        String operationTag = safe(operation, "unknown");
        String modelTag = safe(model, MODEL_UNKNOWN);
        registry.timer(AI_CALL_NAME, TAG_OPERATION, operationTag,
                        TAG_MODEL, modelTag, TAG_OUTCOME, OUTCOME_FAILURE)
                .record(elapsed(startedNanos));
        registry.counter(AI_FAILURE_NAME, TAG_OPERATION, operationTag,
                TAG_MODEL, modelTag, TAG_ERROR_CODE, safe(errorCode, ERROR_CODE_UNKNOWN)).increment();
    }

    /** ④ embedding 调用成功：记 Timer（outcome=success）。 */
    public void recordEmbeddingSuccess(String operation, String model, long startedNanos) {
        registry.timer(EMBEDDING_CALL_NAME, TAG_OPERATION, safe(operation, "unknown"),
                        TAG_MODEL, safe(model, MODEL_UNKNOWN), TAG_OUTCOME, OUTCOME_SUCCESS)
                .record(elapsed(startedNanos));
    }

    /** ④ embedding 调用失败：记 Timer（outcome=failure）+ 失败计数器（325 行「失败率」的分子）。 */
    public void recordEmbeddingFailure(String operation, String model, long startedNanos) {
        String operationTag = safe(operation, "unknown");
        String modelTag = safe(model, MODEL_UNKNOWN);
        registry.timer(EMBEDDING_CALL_NAME, TAG_OPERATION, operationTag,
                        TAG_MODEL, modelTag, TAG_OUTCOME, OUTCOME_FAILURE)
                .record(elapsed(startedNanos));
        registry.counter(EMBEDDING_FAILURE_NAME, TAG_OPERATION, operationTag, TAG_MODEL, modelTag)
                .increment();
    }

    private static Duration elapsed(long startedNanos) {
        return Duration.ofNanos(Math.max(0L, System.nanoTime() - startedNanos));
    }

    private static String safe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
