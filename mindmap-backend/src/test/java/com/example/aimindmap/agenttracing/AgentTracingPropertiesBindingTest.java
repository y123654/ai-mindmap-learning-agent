package com.example.aimindmap.agenttracing;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

/**
 * P0-E1：{@code app.agent.cost.*} 的配置键与 Java 字段能对上
 * （kebab-case → camelCase 的松散绑定由 Spring Boot 负责，这里用 Binder 实测）。
 */
class AgentTracingPropertiesBindingTest {

    @Test
    void costKeysBindFromKebabCaseConfiguration() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("app.agent.cost.input-per-1k-tokens", "0.5")
                .withProperty("app.agent.cost.output-per-1k-tokens", "1.5");

        AgentTracingProperties properties = Binder.get(environment)
                .bindOrCreate("app.agent", AgentTracingProperties.class);

        assertThat(properties.getCost().getInputPer1kTokens()).isEqualTo(0.5d);
        assertThat(properties.getCost().getOutputPer1kTokens()).isEqualTo(1.5d);
    }

    @Test
    void costDefaultsMatchDocumentedPriceWhenNothingConfigured() {
        AgentTracingProperties properties = Binder.get(new MockEnvironment())
                .bindOrCreate("app.agent", AgentTracingProperties.class);

        assertThat(properties.getCost().getInputPer1kTokens()).isEqualTo(0.002d);
        assertThat(properties.getCost().getOutputPer1kTokens()).isEqualTo(0.008d);
        // 既有配置项的默认值不受本次改动影响
        assertThat(properties.getMaxToolCalls()).isEqualTo(3);
        assertThat(properties.getToolCache().isEnabled()).isTrue();
    }

    // ===== P1-D1（方案 §P1-4）：新键能绑定、默认值符合规格、真实 yml 生效 =====

    @Test
    void toolBudgetAndTimeoutKeysBindFromKebabCaseConfiguration() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("app.agent.tool-budget-soft", "false")
                .withProperty("app.agent.tool-timeout-seconds", "25");

        AgentTracingProperties properties = Binder.get(environment)
                .bindOrCreate("app.agent", AgentTracingProperties.class);

        assertThat(properties.isToolBudgetSoft()).isFalse();
        assertThat(properties.getToolTimeoutSeconds()).isEqualTo(25);
    }

    @Test
    void p1d1DefaultsAreSoftBudgetAndTenSecondTimeout() {
        AgentTracingProperties properties = Binder.get(new MockEnvironment())
                .bindOrCreate("app.agent", AgentTracingProperties.class);

        assertThat(properties.isToolBudgetSoft()).isTrue();
        assertThat(properties.getToolTimeoutSeconds()).isEqualTo(10);
        // 已有的总预算键值不动
        assertThat(properties.getTimeoutSeconds()).isEqualTo(75);
    }

    @Test
    void p1d1KeysBindFromRealApplicationYml() throws IOException {
        MockEnvironment environment = new MockEnvironment();
        for (PropertySource<?> source : new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"))) {
            environment.getPropertySources().addFirst(source);
        }

        AgentTracingProperties properties = Binder.get(environment)
                .bindOrCreate("app.agent", AgentTracingProperties.class);

        // 真实 yml：tool-budget-soft 默认 true、tool-timeout-seconds 默认 10（环境变量未覆盖时）
        assertThat(properties.isToolBudgetSoft()).isTrue();
        assertThat(properties.getToolTimeoutSeconds()).isEqualTo(10);
    }
}
