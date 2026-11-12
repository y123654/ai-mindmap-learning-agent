package com.example.aimindmap.agentcontext;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.aimindmap.ai.AiProperties;
import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;
import org.junit.jupiter.api.Test;

class TokenEstimatorTest {

    private final Encoding cl100k =
            Encodings.newDefaultEncodingRegistry().getEncoding(EncodingType.CL100K_BASE);

    private TokenEstimator estimator(double multiplier) {
        AiProperties properties = new AiProperties();
        properties.setTokenEstimateMultiplier(multiplier);
        return new TokenEstimator(properties);
    }

    @Test
    void nullAndEmptyTextReturnZero() {
        TokenEstimator estimator = estimator(1.0d);

        assertThat(estimator.estimateText(null)).isZero();
        assertThat(estimator.estimateText("")).isZero();
        assertThat(estimator.estimateMessage("user", "")).isEqualTo(TokenEstimator.MESSAGE_OVERHEAD_TOKENS);
    }

    @Test
    void estimateIsStableAcrossRepeatedCallsAndInstances() {
        TokenEstimator first = new TokenEstimator(new AiProperties());
        TokenEstimator second = new TokenEstimator(new AiProperties());
        String text = "请解释进程与线程的区别，并给出一段 Java 示例代码。";

        assertThat(first.estimateText(text)).isEqualTo(second.estimateText(text));
        assertThat(first.estimateText(text)).isEqualTo(first.estimateText(text));
        assertThat(first.estimateMessage("assistant", text))
                .isEqualTo(second.estimateMessage("assistant", text));
    }

    @Test
    void multiplierOneMatchesRawCl100kCountForEnglishChineseAndCode() {
        TokenEstimator estimator = estimator(1.0d);
        String english = "Hello, world! This is a token counting test.";
        String chinese = "你好，世界！这是一个分词计数测试，进程与线程是操作系统的核心概念。";
        String code = "int total = items.stream().mapToInt(String::length).sum();\n"
                + "if (total > 100) { System.out.println(\"long\"); }";

        for (String text : new String[]{english, chinese, code}) {
            assertThat(estimator.estimateText(text)).isEqualTo(cl100k.countTokens(text));
            assertThat(estimator.estimateText(text)).isPositive();
        }
        assertThat(cl100k.countTokens(chinese)).isGreaterThan(10);
    }

    @Test
    void defaultMultiplierAppliesCeilingSafetyMargin() {
        TokenEstimator estimator = new TokenEstimator(new AiProperties());
        String text = "保守估算应当在原始 cl100k 计数之上放大 10% 并向上取整。";
        int raw = cl100k.countTokens(text);

        assertThat(estimator.estimateText(text)).isEqualTo((int) Math.ceil(raw * 1.10d));
        assertThat(estimator.estimateText(text)).isGreaterThanOrEqualTo(raw);
    }

    @Test
    void messageEstimateAddsChatmlOverheadAndCountsContentOnly() {
        TokenEstimator estimator = estimator(1.0d);
        String content = "同一段正文";

        int expected = cl100k.countTokens(content) + TokenEstimator.MESSAGE_OVERHEAD_TOKENS;
        assertThat(estimator.estimateMessage("user", content)).isEqualTo(expected);
        assertThat(estimator.estimateMessage("assistant", content)).isEqualTo(expected);
        assertThat(estimator.estimateMessage("assistant", null))
                .isEqualTo(TokenEstimator.MESSAGE_OVERHEAD_TOKENS);
    }
}
