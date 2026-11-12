package com.example.aimindmap.loadtest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.aimindmap.ai.AiChatMessage;
import com.example.aimindmap.ai.AiChatRequest;
import com.example.aimindmap.ai.AiGatewayException;
import com.example.aimindmap.ai.AiJsonRequest;
import com.example.aimindmap.ai.AiJsonResult;
import com.example.aimindmap.ai.dto.AiOperationResponse;
import com.example.aimindmap.ai.dto.AiQuestionResponse;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 阶段八-3：打桩 AiGateway 行为测试（纯单元，不起 Spring 上下文）。
 * 锁死：同步引擎所需 AiOperationResponse 固定返回（空操作集）、固定延迟（±容差）、
 * usage/耗时元数据齐全（change_summary 链路可用）、非压测场景类型明确抛
 * LOADTEST_STUB_UNSUPPORTED（绝不静默返回半截数据）。
 */
class FixedDelayAiGatewayTest {

    private static AiJsonRequest operationRequest() {
        return new AiJsonRequest("系统提示词", "用户提示词", true, 2000, 0.2d);
    }

    @Test
    void completeJsonForResultReturnsEmptyOperationsAfterLatency() {
        FixedDelayAiGateway gateway = new FixedDelayAiGateway(150L);
        long started = System.nanoTime();
        AiJsonResult<AiOperationResponse> result =
                gateway.completeJsonForResult(operationRequest(), AiOperationResponse.class);
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000L;

        assertThat(result.value().safeOperations()).isEmpty();
        assertThat(result.usage().promptTokens()).isPositive();
        assertThat(result.usage().totalTokens())
                .isEqualTo(result.usage().promptTokens() + result.usage().completionTokens());
        assertThat(result.elapsedMillis()).isEqualTo(150L);
        // 真实睡眠 150ms，容差 ±15%
        assertThat(elapsedMillis).isBetween(127L, 1000L);
    }

    @Test
    void completeJsonDelegatesToForResult() {
        FixedDelayAiGateway gateway = new FixedDelayAiGateway(0L);
        AiOperationResponse response = gateway.completeJson(operationRequest(), AiOperationResponse.class);
        assertThat(response.safeOperations()).isEmpty();
    }

    @Test
    void unsupportedResponseTypeThrowsStubExceptionNotSilentGarbage() {
        FixedDelayAiGateway gateway = new FixedDelayAiGateway(0L);
        AiJsonRequest request = new AiJsonRequest("s", "u", true, 1000, 0.2d);
        assertThatThrownBy(() -> gateway.completeJsonForResult(request, AiQuestionResponse.class))
                .isInstanceOf(AiGatewayException.class)
                .extracting(e -> ((AiGatewayException) e).getCode())
                .isEqualTo("LOADTEST_STUB_UNSUPPORTED");
    }

    @Test
    void completeChatReturnsFixedMarkdown() {
        FixedDelayAiGateway gateway = new FixedDelayAiGateway(0L);
        AiChatRequest request = new AiChatRequest(
                "系统提示词", List.of(new AiChatMessage("user", "你好")), 1000, 0.2d);
        assertThat(gateway.completeChat(request)).isEqualTo(FixedDelayAiGateway.FIXED_CHAT_ANSWER);
    }

    @Test
    void streamChatEmitsFixedAnswerInOrderedDeltas() {
        FixedDelayAiGateway gateway = new FixedDelayAiGateway(40L);
        AiChatRequest request = new AiChatRequest(
                "系统提示词", List.of(new AiChatMessage("user", "你好")), 1000, 0.2d);
        List<String> deltas = new ArrayList<>();
        gateway.streamChat(request, deltas::add);
        assertThat(String.join("", deltas)).isEqualTo(FixedDelayAiGateway.FIXED_CHAT_ANSWER);
        assertThat(deltas).hasSizeGreaterThan(1);
    }

    @Test
    void stubAlwaysConfiguredWithLoadTestLabels() {
        FixedDelayAiGateway gateway = new FixedDelayAiGateway(0L);
        assertThat(gateway.configured()).isTrue();
        assertThat(gateway.provider()).isEqualTo("loadtest");
        assertThat(gateway.model()).isEqualTo("loadtest-fixed-stub");
    }
}
