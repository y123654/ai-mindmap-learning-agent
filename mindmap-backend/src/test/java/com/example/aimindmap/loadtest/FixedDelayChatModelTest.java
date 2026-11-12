package com.example.aimindmap.loadtest;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * 阶段八-3：打桩 ChatModel 行为测试（纯单元，不起 Spring 上下文、不连任何中间件）。
 * 锁死：固定内容、固定延迟（±容差）、流式分帧 + 末帧 usage、与真实用法（ChatClient
 * 流式管道，即 KnowledgeAgentService 的实际调用方式）编译期与运行期双重兼容。
 */
class FixedDelayChatModelTest {

    @Test
    void callReturnsFixedAnswerWithUsageAfterConfiguredLatency() {
        FixedDelayChatModel model = new FixedDelayChatModel(200L);
        long started = System.nanoTime();
        ChatResponse response = model.call(new Prompt("你好，请介绍一下数据结构"));
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000L;

        assertThat(response.getResult().getOutput().getText())
                .isEqualTo(FixedDelayChatModel.FIXED_ANSWER);
        Usage usage = response.getMetadata().getUsage();
        assertThat(usage.getPromptTokens()).isPositive();
        assertThat(usage.getCompletionTokens()).isPositive();
        // 固定延迟 200ms，容差 ±15%（Windows 计时精度）
        assertThat(elapsedMillis).isBetween(170L, 1000L);
    }

    @Test
    void streamEmitsFixedAnswerInChunksWithUsageInLastFrame() {
        FixedDelayChatModel model = new FixedDelayChatModel(240L);
        long started = System.nanoTime();
        List<ChatResponse> frames = model.stream(new Prompt("你好")).toStream().toList();
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000L;

        StringBuilder answer = new StringBuilder();
        Usage latestUsage = null;
        for (ChatResponse frame : frames) {
            String text = frame.getResult().getOutput().getText();
            if (text != null && !text.isEmpty()) {
                answer.append(text);
            }
            Usage usage = frame.getMetadata() == null ? null : frame.getMetadata().getUsage();
            if (usage != null && usage.getPromptTokens() != null && usage.getPromptTokens() > 0) {
                latestUsage = usage;
            }
        }
        assertThat(answer.toString()).isEqualTo(FixedDelayChatModel.FIXED_ANSWER);
        assertThat(frames).hasSizeGreaterThan(2);
        assertThat(latestUsage).isNotNull();
        assertThat(latestUsage.getCompletionTokens()).isPositive();
        // 延迟摊到各帧（delayElements 每帧约 latency/frames），总时长不低于延迟的 85%
        assertThat(elapsedMillis).isGreaterThanOrEqualTo(204L);
    }

    @Test
    void streamIsCompatibleWithRealChatClientUsage() {
        // 与 KnowledgeAgentService.streamAnswer 相同的真实用法：ChatClient.create(model)
        // → prompt().stream().chatResponse()；本测试通过即证明签名与管道兼容（场景 A 链路）。
        FixedDelayChatModel model = new FixedDelayChatModel(60L);
        ChatClient chatClient = ChatClient.create(model);
        List<ChatResponse> frames = new ArrayList<>(chatClient.prompt()
                .system("系统提示词")
                .messages(List.of(
                        new org.springframework.ai.chat.messages.UserMessage("什么是树结构？")))
                .stream()
                .chatResponse()
                .collectList()
                .block(Duration.ofSeconds(10)));

        assertThat(frames).isNotEmpty();
        StringBuilder answer = new StringBuilder();
        for (ChatResponse frame : frames) {
            if (frame.getResults() != null && !frame.getResults().isEmpty()) {
                AssistantMessage output = frame.getResult().getOutput();
                if (output.getText() != null) {
                    answer.append(output.getText());
                }
            }
        }
        assertThat(answer.toString()).isEqualTo(FixedDelayChatModel.FIXED_ANSWER);
    }

    @Test
    void zeroLatencyReturnsImmediately() {
        FixedDelayChatModel model = new FixedDelayChatModel(0L);
        ChatResponse response = model.call(new Prompt("你好"));
        assertThat(response.getResult().getOutput().getText())
                .isEqualTo(FixedDelayChatModel.FIXED_ANSWER);
    }
}
