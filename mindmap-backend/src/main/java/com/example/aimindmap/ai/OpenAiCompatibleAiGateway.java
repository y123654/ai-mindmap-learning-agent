package com.example.aimindmap.ai;

import com.example.aimindmap.ai.dto.AiOperationResponse;
import com.example.aimindmap.metrics.BusinessMetrics;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * v1.1 §3.3 / §13.1：OpenAI 兼容 Chat Completions 网关（DeepSeek / OpenAI / 通义 / Ollama 同协议）。
 *
 * <p>安全与稳定要求（§3.3、§16.1、§16.3、§16.4）：
 * <ul>
 *   <li>密钥只从后端配置读取，不记录完整密钥；</li>
 *   <li>优先 response_format=json_object；</li>
 *   <li>超时、非 2xx、JSON 解析失败抛 {@link AiGatewayException}，由任务流标记 FAILED，旧图不变；</li>
 *   <li>网络错误 / 5xx / 429 按 AI_MAX_RETRIES 重试；</li>
 *   <li>记录模型、耗时、token 用量，不记录提示词正文。</li>
 * </ul>
 *
 * <p>阶段八-1：三个对外入口（{@link #streamChat}、{@link #completeChat}、
 * {@link #completeJsonForResult}）各记一次 {@code aimindmap.ai.call} 耗时 Timer 与失败计数
 * （方案 325 行「AI 耗时失败率」），tag 只用 operation（chat / chat_stream / json）、
 * model（配置值）、outcome（success / failure）、error_code（本类内固定的字面量错误码集合）。
 * <b>记录点全部落在既有 throw / return 之前，不新增 catch、不改变任何异常类型与返回值</b>；
 * {@link #completeJson} 委托 {@link #completeJsonForResult}，不重复计数。
 * 计时含本类内部的 AI_MAX_RETRIES 重试（= 调用方感知到的端到端耗时）。
 */
@Component
public class OpenAiCompatibleAiGateway implements AiGateway {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleAiGateway.class);

    private final AiProperties properties;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;
    private final HttpClient httpClient;
    private final BusinessMetrics businessMetrics;

    public OpenAiCompatibleAiGateway(AiProperties properties, ObjectMapper objectMapper,
                                     BusinessMetrics businessMetrics) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.businessMetrics = businessMetrics;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.restClient = RestClient.builder()
                .baseUrl(trimTrailingSlash(properties.getBaseUrl()))
                .requestFactory(requestFactory(properties.getTimeoutSeconds()))
                .build();
    }

    private static SimpleClientHttpRequestFactory requestFactory(int readTimeoutSeconds) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Duration.ofSeconds(10).toMillis());
        factory.setReadTimeout((int) Duration.ofSeconds(Math.max(1, readTimeoutSeconds)).toMillis());
        return factory;
    }

    @Override
    public boolean configured() {
        return properties.enabled();
    }

    @Override
    public String provider() {
        return properties.getProvider();
    }

    @Override
    public String model() {
        return properties.getModel();
    }

    @Override
    public void streamChat(AiChatRequest request, Consumer<String> onDelta) {
        long startedNanos = businessMetrics.startTimer();
        if (!configured()) {
            AiGatewayException notConfigured = AiGatewayException.notConfigured();
            businessMetrics.recordAiCallFailure(BusinessMetrics.AI_OP_CHAT_STREAM, properties.getModel(),
                    notConfigured.getCode(), startedNanos);
            throw notConfigured;
        }

        Map<String, Object> bodyMap = buildChatRequestBody(request);
        bodyMap.put("stream", true);
        String requestBody;
        try {
            requestBody = objectMapper.writeValueAsString(bodyMap);
        } catch (Exception e) {
            businessMetrics.recordAiCallFailure(BusinessMetrics.AI_OP_CHAT_STREAM, properties.getModel(),
                    "AI_REQUEST_INVALID", startedNanos);
            throw new AiGatewayException("AI_REQUEST_INVALID", "AI 请求 JSON 构造失败", e);
        }

        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(trimTrailingSlash(properties.getBaseUrl()) + "/v1/chat/completions"))
                .timeout(Duration.ofSeconds(Math.max(180, properties.getTimeoutSeconds())))
                .header("Authorization", "Bearer " + properties.getApiKey())
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                .build();

        HttpResponse<InputStream> response;
        try {
            response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
        } catch (Exception e) {
            businessMetrics.recordAiCallFailure(BusinessMetrics.AI_OP_CHAT_STREAM, properties.getModel(),
                    "AI_TIMEOUT", startedNanos);
            throw new AiGatewayException("AI_TIMEOUT",
                    "连接 AI 流式服务超时或网络不可达", e);
        }

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            String errorBody;
            try (InputStream input = response.body()) {
                errorBody = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            } catch (Exception ignored) {
                errorBody = "";
            }
            int status = response.statusCode();
            businessMetrics.recordAiCallFailure(BusinessMetrics.AI_OP_CHAT_STREAM, properties.getModel(),
                    "AI_HTTP_" + status, startedNanos);
            throw new AiGatewayException("AI_HTTP_" + status,
                    "AI 流式服务拒绝请求（HTTP " + status + "），请检查 AI_API_KEY、AI_BASE_URL、AI_MODEL 配置："
                            + summarize(errorBody));
        }

        try (InputStream input = response.body();
             BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data:")) {
                    continue;
                }
                String data = line.substring(5).trim();
                if (data.isEmpty() || "[DONE]".equals(data)) {
                    if ("[DONE]".equals(data)) {
                        break;
                    }
                    continue;
                }
                JsonNode root = objectMapper.readTree(data);
                JsonNode error = root.path("error").path("message");
                if (!error.isMissingNode() && !error.asText().isBlank()) {
                    throw new AiGatewayException("AI_STREAM_ERROR", error.asText());
                }
                JsonNode contentNode = root.path("choices").path(0).path("delta").path("content");
                if (contentNode.isTextual() && !contentNode.asText().isEmpty()) {
                    onDelta.accept(contentNode.asText());
                }
            }
        } catch (AiGatewayException e) {
            businessMetrics.recordAiCallFailure(BusinessMetrics.AI_OP_CHAT_STREAM, properties.getModel(),
                    e.getCode(), startedNanos);
            throw e;
        } catch (Exception e) {
            businessMetrics.recordAiCallFailure(BusinessMetrics.AI_OP_CHAT_STREAM, properties.getModel(),
                    "AI_STREAM_FAILED", startedNanos);
            throw new AiGatewayException("AI_STREAM_FAILED",
                    "读取 AI 流式响应失败：" + summarize(e.getMessage()), e);
        }
        businessMetrics.recordAiCallSuccess(BusinessMetrics.AI_OP_CHAT_STREAM, properties.getModel(),
                startedNanos);
    }

    @Override
    public <T> T completeJson(AiJsonRequest request, Class<T> responseType) {
        return completeJsonForResult(request, responseType).value();
    }

    @Override
    public String completeChat(AiChatRequest request) {
        long startedNanos = businessMetrics.startTimer();
        if (!configured()) {
            AiGatewayException notConfigured = AiGatewayException.notConfigured();
            businessMetrics.recordAiCallFailure(BusinessMetrics.AI_OP_CHAT, properties.getModel(),
                    notConfigured.getCode(), startedNanos);
            throw notConfigured;
        }
        long started = System.currentTimeMillis();
        Map<String, Object> body = buildChatRequestBody(request);

        int attempts = Math.max(1, properties.getMaxRetries() + 1);
        RuntimeException lastError = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                String rawBody = restClient.post()
                        .uri("/v1/chat/completions")
                        .header("Authorization", "Bearer " + properties.getApiKey())
                        .body(body)
                        .retrieve()
                        .body(String.class);
                Map<?, ?> response = parseEnvelope(rawBody);

                String content = extractContent(response);
                if (isLengthTruncated(response)) {
                    throw new AiGatewayException("AI_OUTPUT_TRUNCATED",
                            "AI 本次回答超过最大输出长度被截断，请把问题拆小后重试。");
                }
                AiUsage usage = extractUsage(response);
                long elapsed = System.currentTimeMillis() - started;
                log.info("AI 知识助手调用成功 provider={} model={} attempt={} elapsedMs={} promptTokens={} completionTokens={}",
                        properties.getProvider(), properties.getModel(), attempt, elapsed,
                        usage.promptTokens(), usage.completionTokens());
                businessMetrics.recordAiCallSuccess(BusinessMetrics.AI_OP_CHAT, properties.getModel(),
                        startedNanos);
                return content;
            } catch (AiGatewayException e) {
                if ("AI_OUTPUT_TRUNCATED".equals(e.getCode()) && attempt < attempts) {
                    lastError = e;
                } else {
                    businessMetrics.recordAiCallFailure(BusinessMetrics.AI_OP_CHAT, properties.getModel(),
                            e.getCode(), startedNanos);
                    throw e;
                }
            } catch (ResourceAccessException e) {
                lastError = new AiGatewayException("AI_TIMEOUT",
                        "连接 AI 服务超时或网络不可达（" + properties.getTimeoutSeconds() + " 秒）", e);
            } catch (HttpStatusCodeException e) {
                int status = e.getStatusCode().value();
                if (status == 429 || status >= 500) {
                    lastError = new AiGatewayException("AI_HTTP_" + status,
                            "AI 服务暂时不可用（HTTP " + status + "），可稍后重试", e);
                } else {
                    businessMetrics.recordAiCallFailure(BusinessMetrics.AI_OP_CHAT, properties.getModel(),
                            "AI_HTTP_" + status, startedNanos);
                    throw new AiGatewayException("AI_HTTP_" + status,
                            "AI 服务拒绝请求（HTTP " + status + "），请检查 AI_API_KEY、AI_BASE_URL、AI_MODEL 配置",
                            e);
                }
            } catch (RestClientException e) {
                // DeepSeek 偶发返回空体 / 非 JSON 内容却声明 application/json，
                // 直接转 Map 会抛笼统的 "Error while extracting response ..."，这里按瞬时错误重试
                lastError = new AiGatewayException("AI_RESPONSE_INVALID",
                        "AI 服务返回内容异常（" + summarize(e.getMessage()) + "），正在自动重试", e);
            }
            if (attempt < attempts) {
                sleepBeforeRetry(attempt);
            }
        }
        businessMetrics.recordAiCallFailure(BusinessMetrics.AI_OP_CHAT, properties.getModel(),
                lastError instanceof AiGatewayException exhausted ? exhausted.getCode() : "AI_CALL_FAILED",
                startedNanos);
        log.warn("AI 知识助手调用最终失败 provider={} model={} key={} error={}",
                properties.getProvider(), properties.getModel(),
                properties.maskedApiKey(), lastError == null ? "unknown" : lastError.getMessage());
        throw lastError != null ? lastError : new AiGatewayException("AI_CALL_FAILED", "AI 调用失败");
    }

    @Override
    public <T> AiJsonResult<T> completeJsonForResult(AiJsonRequest request, Class<T> responseType) {
        long startedNanos = businessMetrics.startTimer();
        if (!configured()) {
            AiGatewayException notConfigured = AiGatewayException.notConfigured();
            businessMetrics.recordAiCallFailure(BusinessMetrics.AI_OP_JSON, properties.getModel(),
                    notConfigured.getCode(), startedNanos);
            throw notConfigured;
        }
        long started = System.currentTimeMillis();
        Map<String, Object> body = buildRequestBody(request);

        int attempts = Math.max(1, properties.getMaxRetries() + 1);
        RuntimeException lastError = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                String rawBody = restClient.post()
                        .uri("/v1/chat/completions")
                        .header("Authorization", "Bearer " + properties.getApiKey())
                        .body(body)
                        .retrieve()
                        .body(String.class);
                Map<?, ?> response = parseEnvelope(rawBody);

                String content = extractContent(response);
                if (isLengthTruncated(response)) {
                    throw new AiGatewayException("AI_OUTPUT_TRUNCATED",
                            "AI 本次返回内容超过最大输出长度，JSON 被截断。后端已按小批次发送，"
                                    + "请确认单个段落是否过长，或适当调大 AI_MAX_OUTPUT_TOKENS 后重试。");
                }
                String json = AiJsonExtractor.extract(content);
                T parsed;
                try {
                    parsed = objectMapper.readValue(json, responseType);
                } catch (Exception e) {
                    throw new AiGatewayException("AI_JSON_PARSE_FAILED",
                            "AI 返回的 JSON 无法解析为操作结构：" + summarize(e.getMessage())
                                    + "。这通常是返回内容被截断或 JSON 格式异常，可重试；连续失败请拆小文档。", e);
                }
                AiUsage usage = extractUsage(response);
                long elapsed = System.currentTimeMillis() - started;
                log.info("AI 调用成功 provider={} model={} attempt={} elapsedMs={} promptTokens={} completionTokens={}",
                        properties.getProvider(), properties.getModel(), attempt, elapsed,
                        usage.promptTokens(), usage.completionTokens());
                businessMetrics.recordAiCallSuccess(BusinessMetrics.AI_OP_JSON, properties.getModel(),
                        startedNanos);
                return new AiJsonResult<>(parsed, usage, elapsed);
            } catch (AiGatewayException e) {
                // 模型偶发生成半截 JSON；截断/格式错误允许按 AI_MAX_RETRIES 再试一次。
                if (isRetriableContentError(e) && attempt < attempts) {
                    lastError = e;
                } else {
                    businessMetrics.recordAiCallFailure(BusinessMetrics.AI_OP_JSON, properties.getModel(),
                            e.getCode(), startedNanos);
                    throw e;
                }
            } catch (ResourceAccessException e) {
                lastError = new AiGatewayException("AI_TIMEOUT",
                        "连接 AI 服务超时或网络不可达（" + properties.getTimeoutSeconds() + " 秒）", e);
            } catch (HttpStatusCodeException e) {
                int status = e.getStatusCode().value();
                if (status == 429 || status >= 500) {
                    lastError = new AiGatewayException("AI_HTTP_" + status,
                            "AI 服务暂时不可用（HTTP " + status + "），可稍后重试", e);
                } else {
                    // 401/400 等配置或请求问题，重试无意义
                    businessMetrics.recordAiCallFailure(BusinessMetrics.AI_OP_JSON, properties.getModel(),
                            "AI_HTTP_" + status, startedNanos);
                    throw new AiGatewayException("AI_HTTP_" + status,
                            "AI 服务拒绝请求（HTTP " + status + "），请检查 AI_API_KEY、AI_BASE_URL、AI_MODEL 配置",
                            e);
                }
            } catch (RestClientException e) {
                // 响应体为空 / 非 JSON 但声明 application/json 等解码异常，按瞬时错误重试
                lastError = new AiGatewayException("AI_RESPONSE_INVALID",
                        "AI 服务返回内容异常（" + summarize(e.getMessage()) + "），正在自动重试", e);
            }
            if (attempt < attempts) {
                sleepBeforeRetry(attempt);
            }
        }
        businessMetrics.recordAiCallFailure(BusinessMetrics.AI_OP_JSON, properties.getModel(),
                lastError instanceof AiGatewayException exhausted ? exhausted.getCode() : "AI_CALL_FAILED",
                startedNanos);
        log.warn("AI 调用最终失败 provider={} model={} key={} error={}",
                properties.getProvider(), properties.getModel(),
                properties.maskedApiKey(), lastError == null ? "unknown" : lastError.getMessage());
        throw lastError != null ? lastError
                : new AiGatewayException("AI_CALL_FAILED", "AI 调用失败");
    }

    private Map<String, Object> buildRequestBody(AiJsonRequest request) {
        List<AiChatMessage> messages = List.of(new AiChatMessage("user", request.userPrompt()));
        Map<String, Object> body = buildChatRequestBody(new AiChatRequest(
                request.systemPrompt(), messages, request.maxOutputTokens(), request.temperature()));
        if (request.requireJson() && properties.isJsonMode()) {
            body.put("response_format", Map.of("type", "json_object"));
        }
        return body;
    }

    private Map<String, Object> buildChatRequestBody(AiChatRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", properties.getModel());

        List<Map<String, String>> messages = new ArrayList<>();
        if (request.systemPrompt() != null && !request.systemPrompt().isBlank()) {
            messages.add(Map.of("role", "system", "content", request.systemPrompt()));
        }
        for (AiChatMessage message : request.messages()) {
            messages.add(Map.of("role", message.role(), "content", message.content()));
        }
        body.put("messages", messages);

        body.put("temperature", request.temperature());
        if (request.maxOutputTokens() > 0) {
            body.put("max_tokens", request.maxOutputTokens());
        }
        return body;
    }

    @SuppressWarnings("unchecked")
    private boolean isLengthTruncated(Map<?, ?> response) {
        Object choices = response.get("choices");
        if (!(choices instanceof List<?> list) || list.isEmpty()) {
            return false;
        }
        Object first = list.get(0);
        if (!(first instanceof Map<?, ?> choice)) {
            return false;
        }
        Object finishReason = choice.get("finish_reason");
        return finishReason != null && "length".equalsIgnoreCase(finishReason.toString().trim());
    }

    private static boolean isRetriableContentError(AiGatewayException e) {
        return "AI_OUTPUT_TRUNCATED".equals(e.getCode())
                || "AI_JSON_PARSE_FAILED".equals(e.getCode())
                || "AI_RESPONSE_INVALID".equals(e.getCode())
                || "AI_RESPONSE_EMPTY".equals(e.getCode());
    }

    /**
     * 把 AI 服务的原始响应文本解析成信封 Map。
     * 不直接让 RestClient 转 Map：DeepSeek 偶发返回空体或非 JSON 内容却带 application/json，
     * 那种情况下 RestClient 会抛笼统的 "Error while extracting response ..."，无法重试也无法定位。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseEnvelope(String rawBody) {
        if (rawBody == null || rawBody.isBlank()) {
            throw new AiGatewayException("AI_RESPONSE_EMPTY", "AI 返回了空响应");
        }
        try {
            JsonNode node = objectMapper.readTree(rawBody);
            if (node == null || !node.isObject()) {
                throw new AiGatewayException("AI_RESPONSE_INVALID",
                        "AI 返回不是 JSON 对象：" + summarize(rawBody));
            }
            return objectMapper.convertValue(node, Map.class);
        } catch (AiGatewayException e) {
            throw e;
        } catch (Exception e) {
            throw new AiGatewayException("AI_RESPONSE_INVALID",
                    "AI 返回内容无法解析为 JSON：" + summarize(rawBody), e);
        }
    }

    @SuppressWarnings("unchecked")
    private String extractContent(Map<?, ?> response) {
        if (response == null) {
            throw new AiGatewayException("AI_HTTP_ERROR", "AI 返回为空");
        }
        Object choices = response.get("choices");
        if (!(choices instanceof List<?> list) || list.isEmpty()) {
            throw new AiGatewayException("AI_RESPONSE_INVALID", "AI 返回缺少 choices");
        }
        Object first = list.get(0);
        if (!(first instanceof Map<?, ?> choice)) {
            throw new AiGatewayException("AI_RESPONSE_INVALID", "AI 返回 choices[0] 结构不正确");
        }
        Object message = choice.get("message");
        if (!(message instanceof Map<?, ?> messageMap)) {
            throw new AiGatewayException("AI_RESPONSE_INVALID", "AI 返回缺少 message");
        }
        Object content = messageMap.get("content");
        if (content == null) {
            // 部分服务商在 reasoning_content 之外可能给 null 内容（被限流/审核）
            throw new AiGatewayException("AI_RESPONSE_EMPTY", "AI 没有返回文本内容");
        }
        return content.toString();
    }

    @SuppressWarnings("unchecked")
    private AiUsage extractUsage(Map<?, ?> response) {
        Object usageObj = response.get("usage");
        if (!(usageObj instanceof Map<?, ?> usage)) {
            return AiUsage.empty();
        }
        return new AiUsage(
                intOrDefault(usage.get("prompt_tokens")),
                intOrDefault(usage.get("completion_tokens")),
                intOrDefault(usage.get("total_tokens")));
    }

    private static int intOrDefault(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    private static String trimTrailingSlash(String url) {
        if (url == null) {
            return "";
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static String summarize(String message) {
        if (message == null) {
            return "未知解析错误";
        }
        return message.length() > 120 ? message.substring(0, 120) : message;
    }

    private static void sleepBeforeRetry(int attempt) {
        try {
            Thread.sleep(300L * attempt);
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
        }
    }

    /** 供其它服务确认 DTO 类型时引用，避免未使用告警的同时固定泛型用法。 */
    public static Class<AiOperationResponse> operationResponseType() {
        return AiOperationResponse.class;
    }
}
