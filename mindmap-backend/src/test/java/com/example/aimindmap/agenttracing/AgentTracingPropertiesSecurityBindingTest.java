package com.example.aimindmap.agenttracing;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

/**
 * P1-E3b：{@code app.agent.security.evidence-guard-enabled} 的绑定与“默认 true / 环境变量可关”。
 *
 * <p>为什么要专门测：开关名字写错（拼错 kebab 段、环境变量名拼错）在单测里根本看不出来，
 * 上线后表现是“防护静默失效”或“回滚开关按了没用”，是最难排查的一类问题。
 */
class AgentTracingPropertiesSecurityBindingTest {

    private static final String KEY = "app.agent.security.evidence-guard-enabled";
    private static final String ENV = "AGENT_SECURITY_EVIDENCE_GUARD_ENABLED";

    @Test
    void defaultIsOnWhenNothingConfigured() {
        AgentTracingProperties properties = Binder.get(new MockEnvironment())
                .bindOrCreate("app.agent", AgentTracingProperties.class);

        assertThat(properties.isEvidenceGuardEnabled())
                .as("没配置时防护必须默认开（不能靠人记得打开）")
                .isTrue();
    }

    @Test
    void kebabCaseKeyTurnsTheGuardOff() {
        MockEnvironment environment = new MockEnvironment().withProperty(KEY, "false");

        AgentTracingProperties properties = Binder.get(environment)
                .bindOrCreate("app.agent", AgentTracingProperties.class);

        assertThat(properties.isEvidenceGuardEnabled()).isFalse();
    }

    @Test
    void realApplicationYamlKeepsGuardOn() throws Exception {
        AgentTracingProperties properties = Binder.get(loadRealYaml(new MockEnvironment()))
                .bindOrCreate("app.agent", AgentTracingProperties.class);

        assertThat(properties.isEvidenceGuardEnabled())
                .as("application.yml 里 app.agent.security.evidence-guard-enabled 默认必须是 true")
                .isTrue();
    }

    @Test
    void environmentVariableNameInApplicationYamlOverridesTheDefault() throws Exception {
        // application.yml 写的是 ${AGENT_SECURITY_EVIDENCE_GUARD_ENABLED:true}：
        // 这里直接给这个“环境变量名”一个 false，验证占位符确实接在这条线上
        MockEnvironment environment = new MockEnvironment().withProperty(ENV, "false");

        AgentTracingProperties properties = Binder.get(loadRealYaml(environment))
                .bindOrCreate("app.agent", AgentTracingProperties.class);

        assertThat(properties.isEvidenceGuardEnabled()).isFalse();
    }

    @Test
    void nullSecuritySectionFailsSafeToOn() {
        AgentTracingProperties properties = new AgentTracingProperties();
        properties.setSecurity(null);

        assertThat(properties.isEvidenceGuardEnabled())
                .as("security 段被置空时按“开”处理，不能静默关掉防护")
                .isTrue();
    }

    private static MockEnvironment loadRealYaml(MockEnvironment environment) throws Exception {
        for (PropertySource<?> source : new YamlPropertySourceLoader()
                .load("applicationYaml", new ClassPathResource("application.yml"))) {
            environment.getPropertySources().addLast(source);
        }
        return environment;
    }
}
