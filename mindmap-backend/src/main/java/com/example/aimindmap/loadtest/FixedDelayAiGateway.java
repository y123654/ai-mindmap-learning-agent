package com.example.aimindmap.loadtest;

import com.example.aimindmap.ai.AiChatRequest;
import com.example.aimindmap.ai.AiGateway;
import com.example.aimindmap.ai.AiGatewayException;
import com.example.aimindmap.ai.AiJsonRequest;
import com.example.aimindmap.ai.AiJsonResult;
import com.example.aimindmap.ai.AiUsage;
import com.example.aimindmap.ai.dto.AiOperationResponse;
import java.util.List;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 阶段八-3：压测打桩 AiGateway——固定延迟 + 固定假数据，零真实 AI 调用、零 token 费用。
 *
 * <p><b>仅 loadtest profile 生效，生产零影响</b>：只被 {@link LoadTestStubConfiguration}
 * （@Profile("loadtest")）实例化为 @Primary bean；profile 未激活时
 * OpenAiCompatibleAiGateway（DeepSeek HTTP）原样保留。
 *
 * <p>覆盖面：思维导图同步引擎 SyncEngine（场景 C 的核心 AI 面）经
 * {@link #completeJsonForResult} 拿到"零 AI 操作"的固定 {@link AiOperationResponse}——
 * 同步链路本身（解析/diff/规则操作/校验/预览）保持真实执行，只把付费的 LLM 归纳替换为
 * 固定空结果，因此场景 C 压的是真实处理链路且零成本。
 *
 * <p>出题链路（QuestionService）不在阶段八-3 三个压测场景范围内；其响应类型
 * （AiQuestionResponse 等）没有可信的最小固定实现，打桩网关对其明确抛
 * {@link AiGatewayException}（code=LOADTEST_STUB_UNSUPPORTED），任务按既有失败路径落
 * FAILED 并打日志，绝不静默返回半截数据。
 *
 * <p><b>日志红线</b>：只打操作名与延迟毫秒数，绝不打提示词或正文。
 */
public final class FixedDelayAiGateway implements AiGateway {

    private static final Logger log = LoggerFactory.getLogger(FixedDelayAiGateway.class);

    /** completeChat / streamChat 的固定 Markdown 假回答。 */
    static final String FIXED_CHAT_ANSWER =
            "## 压测打桩回答\n\n这是 loadtest 打桩网关返回的固定假数据，"
                    + "用于模拟固定延迟，不调用任何真实 AI 服务，不产生费用。";

    /** 固定 usage（估算假值，仅用于打通 change_summary 的 AI 元数据链路）。 */
    private static final AiUsage FIXED_USAGE = new AiUsage(64, 16, 80);

    private static final int STREAM_CHUNK_COUNT = 4;

    private final long latencyMs;

    public FixedDelayAiGateway(long latencyMs) {
        this.latencyMs = Math.max(0L, latencyMs);
    }

    @Override
    public <T> T completeJson(AiJsonRequest request, Class<T> responseType) {
        return completeJsonForResult(request, responseType).value();
    }

    @Override
    public <T> AiJsonResult<T> completeJsonForResult(AiJsonRequest request, Class<T> responseType) {
        log.debug("loadtest 打桩 AiGateway 调用 operation=json responseType={} latencyMs={}",
                responseType.getSimpleName(), latencyMs);
        sleep(latencyMs);
        if (!AiOperationResponse.class.equals(responseType)) {
            log.warn("loadtest 打桩 AiGateway 收到不支持的 JSON 响应类型，按既有 AI 失败路径处理 "
                            + "operation=json responseType={} latencyMs={}",
                    responseType.getSimpleName(), latencyMs);
            throw new AiGatewayException("LOADTEST_STUB_UNSUPPORTED",
                    "压测打桩网关不支持该响应类型：" + responseType.getSimpleName()
                            + "（阶段八-3 压测场景只覆盖思维导图同步归纳）");
        }
        @SuppressWarnings("unchecked")
        T value = (T) new AiOperationResponse(List.of());
        return new AiJsonResult<>(value, FIXED_USAGE, latencyMs);
    }

    @Override
    public String completeChat(AiChatRequest request) {
        log.debug("loadtest 打桩 AiGateway 调用 operation=chat latencyMs={}", latencyMs);
        sleep(latencyMs);
        return FIXED_CHAT_ANSWER;
    }

    @Override
    public void streamChat(AiChatRequest request, Consumer<String> onDelta) {
        log.debug("loadtest 打桩 AiGateway 调用 operation=chat_stream latencyMs={} chunks={}",
                latencyMs, STREAM_CHUNK_COUNT);
        long perChunkMs = Math.max(1L, latencyMs / STREAM_CHUNK_COUNT);
        int chunkSize = Math.max(1,
                (FIXED_CHAT_ANSWER.length() + STREAM_CHUNK_COUNT - 1) / STREAM_CHUNK_COUNT);
        for (int start = 0; start < FIXED_CHAT_ANSWER.length(); start += chunkSize) {
            sleep(perChunkMs);
            onDelta.accept(FIXED_CHAT_ANSWER.substring(
                    start, Math.min(FIXED_CHAT_ANSWER.length(), start + chunkSize)));
        }
    }

    /** 打桩网关永远"已配置"，避免同步链路走 AI_NOT_CONFIGURED 分支。 */
    @Override
    public boolean configured() {
        return true;
    }

    @Override
    public String provider() {
        return "loadtest";
    }

    @Override
    public String model() {
        return "loadtest-fixed-stub";
    }

    private static void sleep(long millis) {
        if (millis <= 0L) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("loadtest 打桩 AiGateway 固定延迟被中断 operation=gateway latencyMs={}", millis);
        }
    }
}
