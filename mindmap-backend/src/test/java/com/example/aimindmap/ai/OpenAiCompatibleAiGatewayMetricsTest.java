package com.example.aimindmap.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.withSettings;

import com.example.aimindmap.ai.dto.AiOperationResponse;
import com.example.aimindmap.metrics.BusinessMetrics;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 阶段八-1：AI 网关指标埋点单测（方案 325 行「AI 耗时失败率」）。
 *
 * <p><b>不发起任何网络请求：</b>三个入口都走「未配置 API Key」这条既有短路分支
 * （{@code AiProperties.enabled()} 为 false → 立刻抛 {@code AI_NOT_CONFIGURED}），
 * 既覆盖失败计数与 operation/model/error_code 三个 tag，又不需要打桩 HTTP 服务、
 * 不触碰任何真实 LLM（方案 §3.2「单测禁止真实 LLM」）。成功路径的 Timer 语义由
 * {@code BusinessMetricsTest} 在组件层覆盖。
 *
 * <p><b>为什么用 {@code mock(..., CALLS_REAL_METHODS)} + 反射注字段而不是 new：</b>
 * 网关构造器会 {@code HttpClient.newBuilder().build()}，本机命令行沙盒禁止 JDK 回环通道，
 * 直接 new 会抛 {@code IOException: Unable to establish loopback connection}
 * （与既有 {@code ExternalIdEndToEndWiringTest} 把 {@code AiGateway} 整个 mock 掉同一原因）。
 * 用 Objenesis 绕过构造器拿到实例、再回填三个被测路径真正用到的字段，
 * 即可执行<b>真实</b>的 {@code completeChat/completeJsonForResult/streamChat} 方法体；
 * 未配置分支不会触碰 restClient/httpClient，故这两个字段留空。
 * 生产代码为本测试零改动。
 */
class OpenAiCompatibleAiGatewayMetricsTest {

    private static final String MODEL = "test-model";

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final BusinessMetrics businessMetrics = new BusinessMetrics(registry);
    private final OpenAiCompatibleAiGateway gateway = newGatewayWithoutHttpClient();

    /** apiKey 留空 = 未配置；model 显式设值以便断言 tag。 */
    private OpenAiCompatibleAiGateway newGatewayWithoutHttpClient() {
        AiProperties properties = new AiProperties();
        properties.setApiKey("");
        properties.setModel(MODEL);
        OpenAiCompatibleAiGateway instance = mock(OpenAiCompatibleAiGateway.class,
                withSettings().defaultAnswer(Mockito.CALLS_REAL_METHODS));
        ReflectionTestUtils.setField(instance, "properties", properties);
        ReflectionTestUtils.setField(instance, "objectMapper", new ObjectMapper());
        ReflectionTestUtils.setField(instance, "businessMetrics", businessMetrics);
        return instance;
    }

    private static AiChatRequest chatRequest() {
        return new AiChatRequest("系统提示词", List.of(new AiChatMessage("user", "你好")), 512, 0.2d);
    }

    private static AiJsonRequest jsonRequest() {
        return new AiJsonRequest("系统提示词", "用户提示词", true, 512, 0.2d);
    }

    @Test
    void completeChatFailureRecordsTimerAndFailureCounter() {
        assertThatThrownBy(() -> gateway.completeChat(chatRequest()))
                .isInstanceOf(AiGatewayException.class)
                .extracting(e -> ((AiGatewayException) e).getCode())
                .isEqualTo("AI_NOT_CONFIGURED");

        assertThat(registry.timer("aimindmap.ai.call",
                "operation", "chat", "model", MODEL, "outcome", "failure").count()).isEqualTo(1L);
        assertThat(registry.counter("aimindmap.ai.call.failure",
                "operation", "chat", "model", MODEL, "error_code", "AI_NOT_CONFIGURED").count())
                .isEqualTo(1.0);
        assertThat(registry.find("aimindmap.ai.call").tag("outcome", "success").timer()).isNull();
    }

    @Test
    void completeJsonFailureRecordsTimerWithJsonOperationTag() {
        assertThatThrownBy(() -> gateway.completeJsonForResult(jsonRequest(), AiOperationResponse.class))
                .isInstanceOf(AiGatewayException.class);

        assertThat(registry.timer("aimindmap.ai.call",
                "operation", "json", "model", MODEL, "outcome", "failure").count()).isEqualTo(1L);
        assertThat(registry.counter("aimindmap.ai.call.failure",
                "operation", "json", "model", MODEL, "error_code", "AI_NOT_CONFIGURED").count())
                .isEqualTo(1.0);
    }

    @Test
    void streamChatFailureRecordsTimerWithStreamOperationTag() {
        assertThatThrownBy(() -> gateway.streamChat(chatRequest(), delta -> {
        }))
                .isInstanceOf(AiGatewayException.class);

        assertThat(registry.timer("aimindmap.ai.call",
                "operation", "chat_stream", "model", MODEL, "outcome", "failure").count()).isEqualTo(1L);
    }

    /** completeJson 委托 completeJsonForResult，必须只记一次，避免失败率被重复计数放大。 */
    @Test
    void completeJsonDelegationRecordsExactlyOneFailure() {
        assertThatThrownBy(() -> gateway.completeJson(jsonRequest(), AiOperationResponse.class))
                .isInstanceOf(AiGatewayException.class);

        assertThat(registry.timer("aimindmap.ai.call",
                "operation", "json", "model", MODEL, "outcome", "failure").count()).isEqualTo(1L);
        assertThat(registry.counter("aimindmap.ai.call.failure",
                "operation", "json", "model", MODEL, "error_code", "AI_NOT_CONFIGURED").count())
                .isEqualTo(1.0);
    }

    /** 埋点不得改变既有异常契约：仍然是 AiGatewayException，错误码与文案与埋点前一致。 */
    @Test
    void instrumentationDoesNotChangeExceptionContract() {
        assertThat(gateway.configured()).isFalse();

        assertThatThrownBy(() -> gateway.completeChat(chatRequest()))
                .isInstanceOf(AiGatewayException.class)
                .hasMessageContaining("尚未配置 AI 接口");
    }
}
