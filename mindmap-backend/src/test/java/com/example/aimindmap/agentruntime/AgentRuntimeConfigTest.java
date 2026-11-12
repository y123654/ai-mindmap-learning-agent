package com.example.aimindmap.agentruntime;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.aimindmap.ai.AiChatRequest;
import com.example.aimindmap.ai.AiGateway;
import com.example.aimindmap.ai.AiJsonRequest;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

/**
 * P1-B1：{@link AgentRuntimeConfig} 的装配契约。
 *
 * <p>用最小 Spring 容器（{@link AnnotationConfigApplicationContext}）而不是 {@code @SpringBootTest}：
 * 不连库、不起 Web、不碰 Docker，只验证“beans 能被创建 + 默认开关是关”。
 */
class AgentRuntimeConfigTest {

    @Test
    void registersIntentClassifierBeanAndKeepsRuntimeSwitchOffByDefault() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(AiGateway.class, NeverCalledAiGateway::new);
            context.register(AgentRuntimeConfig.class);
            context.refresh();

            // 1) 分类器 bean 能被取到（不依赖 @Component，也没在分类器类上加注解）
            AgentIntentClassifier classifier = context.getBean(AgentIntentClassifier.class);
            assertThat(classifier).isNotNull();

            // 2) 配置绑定生效，且默认全关（旧路径不变的前提）
            AgentRuntimeProperties properties = context.getBean(AgentRuntimeProperties.class);
            assertThat(properties.isEnabled())
                    .as("app.agent.runtime.enabled 默认必须是 false")
                    .isFalse();
            assertThat(properties.getRouter().isLlmEnabled())
                    .as("app.agent.runtime.router.llm-enabled 默认必须是 false")
                    .isFalse();

            // 3) 默认配置下规则直接命中闲聊，网关（任何调用都会抛错）零交互
            assertThat(classifier.classify("你好")).isEqualTo(AgentIntent.CHITCHAT);
        }
    }

    /** 任何网络调用都会让测试失败：用来证明默认配置下分类器“零调用”。 */
    private static final class NeverCalledAiGateway implements AiGateway {

        @Override
        public <T> T completeJson(AiJsonRequest request, Class<T> responseType) {
            throw new AssertionError("默认配置下不应调用 AI 网关：completeJson");
        }

        @Override
        public String completeChat(AiChatRequest request) {
            throw new AssertionError("默认配置下不应调用 AI 网关：completeChat");
        }

        @Override
        public void streamChat(AiChatRequest request, Consumer<String> onDelta) {
            throw new AssertionError("默认配置下不应调用 AI 网关：streamChat");
        }

        @Override
        public boolean configured() {
            return false;
        }

        @Override
        public String provider() {
            return "stub";
        }

        @Override
        public String model() {
            return "stub-model";
        }
    }
}
