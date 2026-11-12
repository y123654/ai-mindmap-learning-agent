package com.example.aimindmap.mindmap;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

/**
 * {@code app.mindmap.*} 的键名与 Java 字段能对上，且默认值、回滚开关都按方案生效
 * （《思维导图节点预算与折叠实施方案-v1.0》§4.1 / §4.5）。
 *
 * <p>全部用 {@link Binder} + {@link MockEnvironment} 做纯绑定测试，<b>不起 Spring 上下文</b>，
 * 毫秒级完成。第三条直接读仓库里真实的 {@code application.yml}，用来证明
 * “折叠默认开启（max-new-nodes=200 / fold-enabled=true / summary-max-chars=500）”
 * 不是只写在注释里，而是真实配置。
 */
class MindMapPropertiesBindingTest {

    @Test
    void kebabCaseKeysBindToFields() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("app.mindmap.max-new-nodes", "150")
                .withProperty("app.mindmap.fold-enabled", "false")
                .withProperty("app.mindmap.summary-max-chars", "300");

        MindMapProperties properties = Binder.get(environment)
                .bindOrCreate("app.mindmap", MindMapProperties.class);

        assertThat(properties.getMaxNewNodes())
                .as("app.mindmap.max-new-nodes 应能绑定到 maxNewNodes")
                .isEqualTo(150);
        assertThat(properties.isFoldEnabled())
                .as("app.mindmap.fold-enabled 应能绑定到 foldEnabled")
                .isFalse();
        assertThat(properties.getSummaryMaxChars())
                .as("app.mindmap.summary-max-chars 应能绑定到 summaryMaxChars")
                .isEqualTo(300);
    }

    @Test
    void defaultsWhenNothingConfigured() {
        MindMapProperties properties = Binder.get(new MockEnvironment())
                .bindOrCreate("app.mindmap", MindMapProperties.class);

        assertThat(properties.getMaxNewNodes())
                .as("默认必须为 200：单次同步允许新增的节点上限")
                .isEqualTo(200);
        assertThat(properties.isFoldEnabled())
                .as("默认必须开启：fold-enabled 默认 true 是本方案唯一的“改默认行为”点，"
                        + "默认值被改成 false 就等于新功能整体失效")
                .isTrue();
        assertThat(properties.getSummaryMaxChars())
                .as("默认必须为 500：折叠摘要的最大字符数")
                .isEqualTo(500);
    }

    @Test
    void realApplicationYamlDefaultsToFoldEnabled() throws Exception {
        MockEnvironment environment = new MockEnvironment();
        for (PropertySource<?> source : new YamlPropertySourceLoader()
                .load("applicationYaml", new ClassPathResource("application.yml"))) {
            environment.getPropertySources().addLast(source);
        }

        MindMapProperties properties = Binder.get(environment)
                .bindOrCreate("app.mindmap", MindMapProperties.class);

        assertThat(properties.getMaxNewNodes())
                .as("真实 application.yml 里 max-new-nodes 默认必须是 200")
                .isEqualTo(200);
        assertThat(properties.isFoldEnabled())
                .as("真实 application.yml 里 fold-enabled 默认必须开启（默认必须开启，否则新方案不生效）")
                .isTrue();
        assertThat(properties.getSummaryMaxChars())
                .as("真实 application.yml 里 summary-max-chars 默认必须是 500")
                .isEqualTo(500);
    }

    @Test
    void rollbackEnvVarDisablesFold() throws Exception {
        MockEnvironment environment = new MockEnvironment();
        for (PropertySource<?> source : new YamlPropertySourceLoader()
                .load("applicationYaml", new ClassPathResource("application.yml"))) {
            environment.getPropertySources().addLast(source);
        }
        // 模拟环境变量 MINDMAP_FOLD_ENABLED=false（全大写下划线，与 application.yml 里
        // app.mindmap.fold-enabled: ${MINDMAP_FOLD_ENABLED:true} 的占位符同名）。
        // Binder.get(environment) 自带 PropertySourcesPlaceholdersResolver，会走占位符解析。
        environment.setProperty("MINDMAP_FOLD_ENABLED", "false");

        MindMapProperties properties = Binder.get(environment)
                .bindOrCreate("app.mindmap", MindMapProperties.class);

        assertThat(properties.isFoldEnabled())
                .as("回滚必须能关：MINDMAP_FOLD_ENABLED=false 时 foldEnabled 必须绑定为 false")
                .isFalse();
    }
}
