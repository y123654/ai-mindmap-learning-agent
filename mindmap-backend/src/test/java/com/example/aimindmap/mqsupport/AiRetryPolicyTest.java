package com.example.aimindmap.mqsupport;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 阶段六-1：AI 错误码分级策略码表边界单测（6-2/6-3 将复用同一策略）。 */
class AiRetryPolicyTest {

    @Test
    void timeoutAndRateLimitAndServerErrorsAreRetriable() {
        assertThat(AiRetryPolicy.isRetriableCode("AI_TIMEOUT")).isTrue();
        assertThat(AiRetryPolicy.isRetriableCode("AI_HTTP_429")).isTrue();
        assertThat(AiRetryPolicy.isRetriableCode("AI_HTTP_500")).isTrue();
        assertThat(AiRetryPolicy.isRetriableCode("AI_HTTP_502")).isTrue();
        assertThat(AiRetryPolicy.isRetriableCode("AI_HTTP_503")).isTrue();
        assertThat(AiRetryPolicy.isRetriableCode("AI_HTTP_599")).isTrue();
    }

    @Test
    void clientErrorsExcept429AreNotRetriable() {
        assertThat(AiRetryPolicy.isRetriableCode("AI_HTTP_400")).isFalse();
        assertThat(AiRetryPolicy.isRetriableCode("AI_HTTP_401")).isFalse();
        assertThat(AiRetryPolicy.isRetriableCode("AI_HTTP_403")).isFalse();
        assertThat(AiRetryPolicy.isRetriableCode("AI_HTTP_404")).isFalse();
        assertThat(AiRetryPolicy.isRetriableCode("AI_HTTP_499")).isFalse();
    }

    @Test
    void contentAndResponseAndStreamCodesAreNotRetriable() {
        assertThat(AiRetryPolicy.isRetriableCode("AI_NOT_CONFIGURED")).isFalse();
        assertThat(AiRetryPolicy.isRetriableCode("AI_INPUT_TOO_LARGE")).isFalse();
        assertThat(AiRetryPolicy.isRetriableCode("AI_RESPONSE_INVALID")).isFalse();
        assertThat(AiRetryPolicy.isRetriableCode("AI_RESPONSE_EMPTY")).isFalse();
        assertThat(AiRetryPolicy.isRetriableCode("AI_JSON_PARSE_FAILED")).isFalse();
        assertThat(AiRetryPolicy.isRetriableCode("AI_STREAM_ERROR")).isFalse();
        assertThat(AiRetryPolicy.isRetriableCode("AI_STREAM_FAILED")).isFalse();
        assertThat(AiRetryPolicy.isRetriableCode("AI_CALL_FAILED")).isFalse();
        assertThat(AiRetryPolicy.isRetriableCode("AI_HTTP_ERROR")).isFalse();
    }

    @Test
    void nullUnknownAndMalformedCodesAreNotRetriable() {
        assertThat(AiRetryPolicy.isRetriableCode(null)).isFalse();
        assertThat(AiRetryPolicy.isRetriableCode("")).isFalse();
        assertThat(AiRetryPolicy.isRetriableCode("NOT_AN_AI_CODE")).isFalse();
        assertThat(AiRetryPolicy.isRetriableCode("AI_HTTP_")).isFalse();
        assertThat(AiRetryPolicy.isRetriableCode("AI_HTTP_5xx")).isFalse();
        assertThat(AiRetryPolicy.isRetriableCode("AI_HTTP_5000")).isFalse();
    }

    @Test
    void notConfiguredAndClientErrorsExcept429AreConfigurationCodes() {
        assertThat(AiRetryPolicy.isConfigurationCode("AI_NOT_CONFIGURED")).isTrue();
        assertThat(AiRetryPolicy.isConfigurationCode("AI_HTTP_400")).isTrue();
        assertThat(AiRetryPolicy.isConfigurationCode("AI_HTTP_401")).isTrue();
        assertThat(AiRetryPolicy.isConfigurationCode("AI_HTTP_403")).isTrue();
        assertThat(AiRetryPolicy.isConfigurationCode("AI_HTTP_499")).isTrue();
        // 429 是限流可重试，不是配置问题
        assertThat(AiRetryPolicy.isConfigurationCode("AI_HTTP_429")).isFalse();
    }

    @Test
    void serverErrorsAndContentCodesAreNotConfigurationCodes() {
        assertThat(AiRetryPolicy.isConfigurationCode("AI_HTTP_500")).isFalse();
        assertThat(AiRetryPolicy.isConfigurationCode("AI_HTTP_503")).isFalse();
        assertThat(AiRetryPolicy.isConfigurationCode("AI_HTTP_599")).isFalse();
        assertThat(AiRetryPolicy.isConfigurationCode("AI_TIMEOUT")).isFalse();
        assertThat(AiRetryPolicy.isConfigurationCode("AI_INPUT_TOO_LARGE")).isFalse();
        assertThat(AiRetryPolicy.isConfigurationCode("AI_RESPONSE_INVALID")).isFalse();
        assertThat(AiRetryPolicy.isConfigurationCode(null)).isFalse();
        assertThat(AiRetryPolicy.isConfigurationCode("AI_HTTP_")).isFalse();
        assertThat(AiRetryPolicy.isConfigurationCode("AI_HTTP_abc")).isFalse();
    }
}
