package com.example.aimindmap.agentcontext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.aimindmap.ai.AiChatMessage;
import com.example.aimindmap.ai.AiProperties;
import com.example.aimindmap.common.error.ApiException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class TokenBudgetServiceTest {

    private final AiProperties properties = new AiProperties();
    private final TokenEstimator estimator = new TokenEstimator(properties);
    private final TokenBudgetService service = new TokenBudgetService(estimator, properties);

    private static AiChatMessage msg(String role, String content) {
        return new AiChatMessage(role, content);
    }

    /** 反复追加固定中英文片段，直到按消息口径估算达到目标 token 数。 */
    private String messageContentOfAtLeastTokens(String role, int targetTokens) {
        String unit = "这是一段用于测试预算裁剪的中英文混合内容 abc 123，token 计数稳定。";
        StringBuilder builder = new StringBuilder();
        while (estimator.estimateMessage(role, builder.toString()) < targetTokens) {
            builder.append(unit);
        }
        return builder.toString();
    }

    /** 反复追加固定中英文片段，直到按纯文本口径估算达到目标 token 数。 */
    private String textOfAtLeastTokens(int targetTokens) {
        String unit = "长期记忆块 / 会话摘要块的模拟文本，用来验证固定部分永不被裁剪。English words.";
        StringBuilder builder = new StringBuilder();
        while (estimator.estimateText(builder.toString()) < targetTokens) {
            builder.append(unit);
        }
        return builder.toString();
    }

    @Test
    void shortConversationIsNotTrimmedAndOutputStaysAtCap() {
        String systemPrompt = "你是知识助手";
        List<AiChatMessage> candidates = List.of(msg("user", "你好"));

        ContextBudgetResult result = service.compute(10L, systemPrompt, candidates);

        assertThat(result.droppedMessages()).isZero();
        assertThat(result.retained()).containsExactlyElementsOf(candidates);
        assertThat(result.effectiveMaxOutputTokens()).isEqualTo(2000);
        assertThat(result.estimatedInputTokens()).isEqualTo(
                estimator.estimateText(systemPrompt) + estimator.estimateMessage("user", "你好"));
    }

    @Test
    void trimmingRemovesOldestMessagesAndAlwaysKeepsLastUserQuestion() {
        String systemPrompt = "你是知识助手";
        String history = "旧的历史对话内容，需要占用一些 token。旧的历史对话内容。";
        String question = "这是本轮最新的问题，必须保留";
        List<AiChatMessage> candidates = List.of(
                msg("assistant", history), msg("user", history),
                msg("assistant", history), msg("user", history),
                msg("assistant", history), msg("user", question));
        // 输入预算只够“系统提示词 + 最新两条消息”
        properties.setMaxInputTokens(
                estimator.estimateText(systemPrompt)
                        + estimator.estimateMessage("assistant", history)
                        + estimator.estimateMessage("user", question));

        ContextBudgetResult result = service.compute(10L, systemPrompt, candidates);

        assertThat(result.droppedMessages()).isEqualTo(4);
        assertThat(result.retained()).containsExactly(
                msg("assistant", history), msg("user", question));
        assertThat(result.effectiveMaxOutputTokens()).isEqualTo(2000);
    }

    @Test
    void nearWindowCompressesOutputBudgetButKeepsMinimumOutput() {
        properties.setContextWindowTokens(5000);
        properties.setOutputReserveTokens(512);
        properties.setMinOutputTokens(512);
        String giantAnswer = messageContentOfAtLeastTokens("assistant", 3500);
        List<AiChatMessage> candidates = List.of(
                msg("assistant", giantAnswer), msg("user", "再解释一下"));

        ContextBudgetResult result = service.compute(10L, "你是知识助手", candidates);

        assertThat(result.droppedMessages()).isZero();
        assertThat(result.effectiveMaxOutputTokens()).isLessThan(2000);
        assertThat(result.effectiveMaxOutputTokens()).isGreaterThanOrEqualTo(512);
    }

    @Test
    void completelyInsufficientWindowThrowsExistingContextTooLongCode() {
        properties.setContextWindowTokens(1000);
        properties.setOutputReserveTokens(100);
        properties.setMinOutputTokens(200);
        String giantQuestion = messageContentOfAtLeastTokens("user", 2000);

        assertThatThrownBy(() -> service.compute(
                10L, "你是知识助手", List.of(msg("user", giantQuestion))))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus().value()).isEqualTo(400);
                    assertThat(ex.getCode()).isEqualTo("ASSISTANT_CONTEXT_TOO_LONG");
                });
    }

    @Test
    void inputBudgetTakesTheSmallerOfConfigCapAndWindowSpace() {
        // 场景一：默认窗口下 max-input-tokens=24000 是更紧的约束
        List<AiChatMessage> manyMessages = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            manyMessages.add(msg("assistant", messageContentOfAtLeastTokens("assistant", 5000)));
        }
        manyMessages.add(msg("user", "最后一个问题"));
        ContextBudgetResult cappedAtConfig = service.compute(10L, "你是知识助手", manyMessages);

        assertThat(cappedAtConfig.droppedMessages()).isPositive();
        assertThat(cappedAtConfig.estimatedInputTokens() - estimator.estimateText("你是知识助手"))
                .isLessThanOrEqualTo(24000);

        // 场景二：小窗口下“窗口 - 输出预留 - 最小输出”成为更紧的约束
        AiProperties smallWindowProps = new AiProperties();
        smallWindowProps.setContextWindowTokens(4000);
        smallWindowProps.setOutputReserveTokens(512);
        smallWindowProps.setMinOutputTokens(512);
        TokenEstimator smallWindowEstimator = new TokenEstimator(smallWindowProps);
        TokenBudgetService smallWindowService =
                new TokenBudgetService(smallWindowEstimator, smallWindowProps);
        List<AiChatMessage> nearWindowMessages = new ArrayList<>();
        nearWindowMessages.add(msg("assistant", messageContentOfAtLeastTokens("assistant", 3000)));
        nearWindowMessages.add(msg("assistant", messageContentOfAtLeastTokens("assistant", 3000)));
        nearWindowMessages.add(msg("user", "最后一个问题"));

        ContextBudgetResult cappedByWindow =
                smallWindowService.compute(10L, "你是知识助手", nearWindowMessages);

        int hardWindowInputBudget = 4000 - 512 - 512;
        assertThat(cappedByWindow.droppedMessages()).isPositive();
        assertThat(cappedByWindow.estimatedInputTokens() - smallWindowEstimator.estimateText("你是知识助手"))
                .isLessThanOrEqualTo(hardWindowInputBudget);
        assertThat(cappedByWindow.retained().get(cappedByWindow.retained().size() - 1).role())
                .isEqualTo("user");
    }

    @Test
    void memoryAndSummaryFixedBlockIsNeverTrimmedAndAlwaysCounted() {
        String systemPrompt = "你是知识助手。\n\n" + textOfAtLeastTokens(1500);
        properties.setMaxInputTokens(1000);
        String history = "较早的历史对话内容。";
        String question = "本轮提问";
        List<AiChatMessage> candidates = List.of(
                msg("assistant", history), msg("user", history),
                msg("assistant", history), msg("user", question));

        ContextBudgetResult result = service.compute(10L, systemPrompt, candidates);

        assertThat(result.retained()).containsExactly(msg("user", question));
        assertThat(result.droppedMessages()).isEqualTo(3);
        assertThat(result.estimatedInputTokens()).isEqualTo(
                estimator.estimateText(systemPrompt) + estimator.estimateMessage("user", question));
        assertThat(result.estimatedInputTokens())
                .isGreaterThanOrEqualTo(estimator.estimateText(systemPrompt));
        assertThat(result.effectiveMaxOutputTokens()).isEqualTo(2000);
    }
}
