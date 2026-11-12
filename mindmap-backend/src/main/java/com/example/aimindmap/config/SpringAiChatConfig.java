package com.example.aimindmap.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 阶段 0：Spring AI Chat 模型配置（DeepSeek）。
 * OpenAI starter 的 chat 自动装配已通过 spring.ai.model.chat=none 关闭，
 * 这里显式定义指向 DeepSeek 官方接口的 OpenAiChatModel，供后续阶段使用。
 * 连接信息复用现有 app.ai.* 配置（AI_BASE_URL / AI_API_KEY / AI_MODEL），不新增密钥配置。
 */
@Configuration
public class SpringAiChatConfig {

    private static final Logger log = LoggerFactory.getLogger(SpringAiChatConfig.class);

    @Bean
    public OpenAiChatModel openAiChatModel(
            @Value("${app.ai.base-url}") String baseUrl,
            @Value("${app.ai.api-key}") String apiKey,
            @Value("${app.ai.model}") String model,
            @Value("${app.ai.temperature}") double temperature) {
        OpenAiApi openAiApi = OpenAiApi.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .build();
        OpenAiChatOptions defaultOptions = OpenAiChatOptions.builder()
                .model(model)
                .temperature(temperature)
                .build();
        log.info("Spring AI Chat 模型已配置: provider=deepseek, baseUrl={}, model={}", baseUrl, model);
        return OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(defaultOptions)
                .build();
    }
}
