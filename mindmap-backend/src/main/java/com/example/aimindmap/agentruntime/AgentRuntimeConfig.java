package com.example.aimindmap.agentruntime;

import com.example.aimindmap.ai.AiGateway;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 注册 P1 显式编排配置（{@code app.agent.runtime.*}）与编排相关 bean。
 *
 * <p>P1-A 只做“配置可绑定 + 默认关”；P1-B1 起在这里注册
 * {@link AgentIntentClassifier}（它本身不依赖 Spring，因此不加 {@code @Component}）。
 *
 * <p>开关仍由 {@link AgentRuntimeProperties#isEnabled()}（{@code app.agent.runtime.enabled}，
 * 默认 false）控制：关着时 bean 存在但不会被业务流程调用，旧路径行为不变，可一键回滚。
 *
 * <p>{@link AiGateway} 是接口：正常 profile 下唯一实现是 {@code OpenAiCompatibleAiGateway}，
 * 压测 profile 下 {@code FixedDelayAiGateway} 是 {@code @Primary}，单点注入即可。
 */
@Configuration
@EnableConfigurationProperties(AgentRuntimeProperties.class)
public class AgentRuntimeConfig {

    /**
     * 意图分类器 bean（P1-B1）。分类器只“判意图”，是否启用由调用方看总开关，
     * 因此这里不做 {@code @ConditionalOnProperty}：单测/运维都可通过 ObjectProvider 取到它。
     */
    @Bean
    public AgentIntentClassifier agentIntentClassifier(AiGateway aiGateway,
                                                       AgentRuntimeProperties properties) {
        return new AgentIntentClassifier(aiGateway, properties);
    }
}
