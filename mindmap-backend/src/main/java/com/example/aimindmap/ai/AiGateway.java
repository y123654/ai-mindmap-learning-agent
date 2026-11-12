package com.example.aimindmap.ai;

/**
 * v1.1 §13.1 AI 网关。以后可切换 OpenAI、通义千问、智谱、Ollama，
 * 同步引擎只依赖本接口，不感知具体服务商。
 */
public interface AiGateway {

    /** 发送对话补全请求并把 JSON 回复解析成 responseType。 */
    <T> T completeJson(AiJsonRequest request, Class<T> responseType);

    /** 发送普通多轮聊天请求，返回 Markdown 文本。 */
    String completeChat(AiChatRequest request);

    /** 发送流式聊天请求，每收到一段文本就回调一次。 */
    void streamChat(AiChatRequest request, java.util.function.Consumer<String> onDelta);

    /** 同 {@link #completeJson}，额外返回 token 用量与耗时（§16.4 成本记录，接口的增量扩展）。 */
    default <T> AiJsonResult<T> completeJsonForResult(AiJsonRequest request, Class<T> responseType) {
        return new AiJsonResult<>(completeJson(request, responseType), AiUsage.empty(), 0L);
    }

    /** 当前是否已配置可用（API Key / Base URL）。 */
    boolean configured();

    String provider();

    /** 当前配置的模型名（§16.4 记录用，接口增量扩展）。 */
    String model();
}