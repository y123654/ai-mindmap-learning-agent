package com.example.aimindmap.assistant;

import com.example.aimindmap.ai.AiChatMessage;
import com.example.aimindmap.ai.AiProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

/**
 * 阶段 1：基于 Spring AI ChatClient 的知识助手流式回答服务。
 * ChatClient 直接从现有 OpenAiChatModel（DeepSeek）bean 构造，不引入 Advisor / Tool / RAG。
 */
@Service
public class KnowledgeAgentService {

    private final ChatClient chatClient;
    private final AiProperties aiProperties;

    public KnowledgeAgentService(ChatModel chatModel, AiProperties aiProperties) {
        this.chatClient = ChatClient.create(chatModel);
        this.aiProperties = aiProperties;
    }

    /**
     * 发起流式回答。
     *
     * @param systemPrompt    系统提示词，只随请求放置一次
     * @param messages        对话上下文（已含本轮用户消息），role 仅 user / assistant
     * @param maxOutputTokens 本次回答最大 token 数
     * @param temperature     本次回答温度
     * @param toolCallbacks   本次请求挂载的工具回调（带追踪/限制装饰）
     * @param toolContext     透传给工具的上下文（userId / runId；R-03）
     * 阶段二 D：返回完整 ChatResponse 流（.stream().chatResponse()），由 Streamer 逐块取文本，
     * 并从末帧读取平台真实 usage；streamUsage(true) 要求服务商在流式最后一帧回传用量。
     * 若平台不回传 usage，Streamer 按降级标准处理（usage 返回 null，不视为失败）。
     *
     * @return ChatResponse 片段 Flux
     */
    public Flux<ChatResponse> streamAnswer(String systemPrompt,
                                     List<AiChatMessage> messages,
                                     int maxOutputTokens,
                                     double temperature,
                                     List<ToolCallback> toolCallbacks,
                                     Map<String, Object> toolContext) {
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .maxTokens(maxOutputTokens)
                .streamUsage(true)
                .temperature(temperature)
                .build();
        return chatClient.prompt()
                .system(systemPrompt)
                .messages(toSpringMessages(messages))
                .options(options)
                .toolCallbacks(toolCallbacks)
                .toolContext(toolContext)
                .stream()
                .chatResponse();
    }

    /** 落库标签：服务商，与改造前自研网关标签保持一致。 */
    public String provider() {
        return aiProperties.getProvider();
    }

    /** 落库标签：模型名，取 app.ai.model 配置值。 */
    public String model() {
        return aiProperties.getModel();
    }

    private List<Message> toSpringMessages(List<AiChatMessage> messages) {
        List<Message> result = new ArrayList<>();
        for (AiChatMessage message : messages) {
            if ("assistant".equals(message.role())) {
                result.add(new AssistantMessage(message.content()));
            } else {
                result.add(new UserMessage(message.content()));
            }
        }
        return result;
    }
}
