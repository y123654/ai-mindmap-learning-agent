package com.example.aimindmap.agentruntime;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

/**
 * P1-A：{@code app.agent.runtime.*} 的键名与 Java 字段能对上，且<b>默认全关</b>。
 *
 * <p>第三种情况直接读仓库里真实的 {@code application.yml}（用 Binder 松散绑定实测），
 * 用来证明“P1 编排开关默认关闭、小模型分类默认关闭”不是只写在注释里。
 */
class AgentRuntimePropertiesBindingTest {

    @Test
    void kebabCaseKeysBindToFields() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("app.agent.runtime.enabled", "true")
                .withProperty("app.agent.runtime.router.llm-enabled", "true")
                .withProperty("app.agent.runtime.router.llm-timeout-ms", "500")
                .withProperty("app.agent.runtime.router.llm-max-tokens", "30");

        AgentRuntimeProperties properties = Binder.get(environment)
                .bindOrCreate("app.agent.runtime", AgentRuntimeProperties.class);

        assertThat(properties.isEnabled()).isTrue();
        assertThat(properties.getRouter().isLlmEnabled()).isTrue();
        assertThat(properties.getRouter().getLlmTimeoutMs()).isEqualTo(500);
        assertThat(properties.getRouter().getLlmMaxTokens()).isEqualTo(30);
    }

    @Test
    void defaultsAreAllOffWhenNothingConfigured() {
        AgentRuntimeProperties properties = Binder.get(new MockEnvironment())
                .bindOrCreate("app.agent.runtime", AgentRuntimeProperties.class);

        assertThat(properties.isEnabled()).isFalse();
        assertThat(properties.getRouter().isLlmEnabled()).isFalse();
        assertThat(properties.getRouter().getLlmTimeoutMs()).isEqualTo(2000);
        assertThat(properties.getRouter().getLlmMaxTokens()).isEqualTo(50);
    }

    @Test
    void realApplicationYamlKeepsOrchestrationAndLlmRouterOff() throws Exception {
        MockEnvironment environment = new MockEnvironment();
        for (PropertySource<?> source : new YamlPropertySourceLoader()
                .load("applicationYaml", new ClassPathResource("application.yml"))) {
            environment.getPropertySources().addLast(source);
        }

        AgentRuntimeProperties properties = Binder.get(environment)
                .bindOrCreate("app.agent.runtime", AgentRuntimeProperties.class);

        assertThat(properties.isEnabled())
                .as("app.agent.runtime.enabled 默认必须是 false（旧路径行为不变）")
                .isFalse();
        assertThat(properties.getRouter().isLlmEnabled())
                .as("app.agent.runtime.router.llm-enabled 默认必须是 false（分类器零网络调用）")
                .isFalse();
    }
}
