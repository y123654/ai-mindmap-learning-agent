package com.example.aimindmap.loadtest;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

/**
 * 阶段八-3：压测打桩 ChatModel——固定延迟 + 固定中文假回答，零真实 AI 调用、零 token 费用。
 *
 * <p><b>仅 loadtest profile 生效，生产零影响</b>：本类只被
 * {@link LoadTestStubConfiguration}（@Profile("loadtest")）实例化为 @Primary bean；
 * profile 未激活时 Spring 根本不会创建它，既有 OpenAiChatModel 装配原样保留。
 *
 * <p>覆盖面（对照阶段八-3 三个压测场景的完整调用链）：
 * <ul>
 *   <li>知识助手流式问答：KnowledgeAgentService 经 ChatClient 调 {@link #stream(Prompt)}，
 *       按真实服务商习惯把固定回答切成多帧下发，末帧只带 usage（空文本），
 *       与 KnowledgeAssistantStreamer 的"空块跳过 + 末帧回收 usage"逻辑完全兼容；</li>
 *   <li>记忆合并 LLM（MemoryVectorService.mergeWithLlm）与会话摘要
 *       （SessionSummaryService）：走 {@link #call(Prompt)}，返回固定文本。</li>
 * </ul>
 *
 * <p>usage 为固定估算值（非真实计费数据），仅用于打通阶段二 D 的 usage 回收链路。
 * <b>日志红线</b>：只打操作名与延迟毫秒数，绝不打提示词、消息正文或回答内容。
 */
public final class FixedDelayChatModel implements ChatModel {

    private static final Logger log = LoggerFactory.getLogger(FixedDelayChatModel.class);

    /** 固定假回答（纯假数据，与任何真实模型输出无关）。 */
    static final String FIXED_ANSWER =
            "这是压测打桩回答：AI 链路已被 loadtest 固定实现替代，"
                    + "返回固定假数据并模拟固定延迟，不产生任何真实模型调用与费用。"
                    + "系统链路运行正常，可以继续压测。";

    /** 流式下发帧数（不含末帧 usage），模拟真实 SSE 分帧节奏。 */
    private static final int STREAM_CHUNK_COUNT = 6;

    /** 固定 usage（估算值）：prompt 按输入字符数/4 粗估，completion 按回答字符数/2 粗估。 */
    private static final int MIN_USAGE_TOKENS = 1;

    private final long latencyMs;

    public FixedDelayChatModel(long latencyMs) {
        this.latencyMs = Math.max(0L, latencyMs);
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        log.debug("loadtest 打桩 ChatModel 同步调用 operation=chat latencyMs={}", latencyMs);
        sleep(latencyMs);
        return new ChatResponse(
                List.of(new Generation(new AssistantMessage(FIXED_ANSWER))),
                ChatResponseMetadata.builder().usage(usageOf(prompt)).build());
    }

    /**
     * 流式接口签名与真实 ChatModel 完全一致（编译期即证）：返回 Flux&lt;ChatResponse&gt;，
     * 文本帧均匀摊开总延迟，末帧空文本只带 usage——与真实服务商"streamUsage=true 末帧回传
     * 用量"的行为对齐，KnowledgeAssistantStreamer 无需任何改动即可走通完整链路。
     */
    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        log.debug("loadtest 打桩 ChatModel 流式调用 operation=chat_stream latencyMs={} chunks={}",
                latencyMs, STREAM_CHUNK_COUNT);
        List<ChatResponse> frames = new ArrayList<>();
        for (String chunk : splitAnswer(FIXED_ANSWER, STREAM_CHUNK_COUNT)) {
            frames.add(new ChatResponse(List.of(new Generation(new AssistantMessage(chunk)))));
        }
        frames.add(new ChatResponse(
                List.of(new Generation(new AssistantMessage(""))),
                ChatResponseMetadata.builder().usage(usageOf(prompt)).build()));
        long perFrameMs = Math.max(1L, latencyMs / frames.size());
        return Flux.fromIterable(frames).delayElements(Duration.ofMillis(perFrameMs));
    }

    private DefaultUsage usageOf(Prompt prompt) {
        int promptChars = 0;
        if (prompt != null && prompt.getInstructions() != null) {
            for (Message message : prompt.getInstructions()) {
                String text = message.getText();
                promptChars += text == null ? 0 : text.length();
            }
        }
        int promptTokens = Math.max(MIN_USAGE_TOKENS, promptChars / 4);
        int completionTokens = Math.max(MIN_USAGE_TOKENS, FIXED_ANSWER.length() / 2);
        return new DefaultUsage(promptTokens, completionTokens);
    }

    private static List<String> splitAnswer(String answer, int chunkCount) {
        List<String> chunks = new ArrayList<>();
        int chunkSize = Math.max(1, (answer.length() + chunkCount - 1) / chunkCount);
        for (int start = 0; start < answer.length(); start += chunkSize) {
            chunks.add(answer.substring(start, Math.min(answer.length(), start + chunkSize)));
        }
        return chunks;
    }

    private static void sleep(long millis) {
        if (millis <= 0L) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("loadtest 打桩 ChatModel 固定延迟被中断 operation=chat latencyMs={}", millis);
        }
    }
}
