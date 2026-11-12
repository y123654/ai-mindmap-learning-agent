package com.example.aimindmap.agentruntime;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * P1-5 检索配置的契约（所有者 2026-10-27 裁定 2）+ P1-E2c 的 LLM 改写默认关（所有者 2026-10-28 决定）。
 *
 * <p>硬要求：
 * <ol>
 *   <li><b>多查询与两个上限的默认值与改造前一致</b>（开关 true、条数 5、每篇 2 个 chunk），
 *       不配置时行为不变；</li>
 *   <li>两个上限支持下一段扫参（cap 5 vs 8、每篇 1 vs 2），越界要回退默认值而不是让程序配错跑歪；</li>
 *   <li><b>{@code rewrite-llm-enabled} 默认 false</b>（依据 E2/E2b：LLM 改写 +780~863ms、召回 +0.0pp），
 *       代码默认值与真实 yml 绑定值都必须为 false，否则“默认关”只是一句注释。</li>
 * </ol>
 *
 * <p>第四个用例直接读<b>真实的</b> {@code src/main/resources/application.yml} 做绑定，
 * 防止“代码里加了字段但 yml 没写 / 写错 key”这种只在启动后才暴露的问题。
 */
class RetrievalPropertiesTest {

    @Test
    void defaultsMatchBehaviourBeforeThisChange() {
        RetrievalProperties properties = new RetrievalProperties();

        assertThat(properties.isMultiQueryEnabled())
                .as("多查询默认开（回滚靠显式置 false）")
                .isTrue();
        assertThat(properties.isRewriteLlmEnabled())
                .as("LLM 改写默认关（P1-E2c，所有者 2026-10-28 决定）")
                .isFalse()
                .isEqualTo(RetrievalProperties.DEFAULT_REWRITE_LLM_ENABLED);
        assertThat(properties.getMaxResults())
                .as("默认条数必须等于改造前的 5")
                .isEqualTo(5)
                .isEqualTo(RetrievalProperties.DEFAULT_MAX_RESULTS);
        assertThat(properties.getMaxChunksPerDocument())
                .as("默认每篇 2 个 chunk（方案 §P1-5 原文）")
                .isEqualTo(2)
                .isEqualTo(RetrievalProperties.DEFAULT_MAX_CHUNKS_PER_DOCUMENT);
    }

    @Test
    void outOfRangeValuesFallBackToDefaults() {
        RetrievalProperties properties = new RetrievalProperties();

        // max-results 允许 1~10
        properties.setMaxResults(0);
        assertThat(properties.getMaxResults()).isEqualTo(RetrievalProperties.DEFAULT_MAX_RESULTS);
        properties.setMaxResults(-3);
        assertThat(properties.getMaxResults()).isEqualTo(RetrievalProperties.DEFAULT_MAX_RESULTS);
        properties.setMaxResults(11);
        assertThat(properties.getMaxResults()).isEqualTo(RetrievalProperties.DEFAULT_MAX_RESULTS);
        properties.setMaxResults(1);
        assertThat(properties.getMaxResults()).isEqualTo(1);
        properties.setMaxResults(8);
        assertThat(properties.getMaxResults()).isEqualTo(8);
        properties.setMaxResults(10);
        assertThat(properties.getMaxResults()).isEqualTo(10);

        // max-chunks-per-document 允许 1~3
        properties.setMaxChunksPerDocument(0);
        assertThat(properties.getMaxChunksPerDocument())
                .isEqualTo(RetrievalProperties.DEFAULT_MAX_CHUNKS_PER_DOCUMENT);
        properties.setMaxChunksPerDocument(4);
        assertThat(properties.getMaxChunksPerDocument())
                .isEqualTo(RetrievalProperties.DEFAULT_MAX_CHUNKS_PER_DOCUMENT);
        properties.setMaxChunksPerDocument(1);
        assertThat(properties.getMaxChunksPerDocument()).isEqualTo(1);
        properties.setMaxChunksPerDocument(3);
        assertThat(properties.getMaxChunksPerDocument()).isEqualTo(3);
    }

    @Test
    void realApplicationYmlBindsRetrievalSection() throws Exception {
        MutablePropertySources propertySources = new MutablePropertySources();
        List<PropertySource<?>> loaded = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"));
        loaded.forEach(propertySources::addFirst);

        Binder binder = new Binder(
                ConfigurationPropertySources.from(propertySources),
                new PropertySourcesPlaceholdersResolver(propertySources));
        RetrievalProperties bound = binder
                .bind("app.agent.retrieval", Bindable.of(RetrievalProperties.class))
                .orElseThrow(() -> new AssertionError(
                        "application.yml 里没有可绑定的 app.agent.retrieval 配置"));

        assertThat(bound.isMultiQueryEnabled()).isTrue();
        assertThat(bound.isRewriteLlmEnabled())
                .as("真实 application.yml 必须把 rewrite-llm-enabled 绑成 false（默认关）")
                .isFalse();
        assertThat(bound.getMaxResults()).isEqualTo(RetrievalProperties.DEFAULT_MAX_RESULTS);
        assertThat(bound.getMaxChunksPerDocument())
                .isEqualTo(RetrievalProperties.DEFAULT_MAX_CHUNKS_PER_DOCUMENT);
    }
}
