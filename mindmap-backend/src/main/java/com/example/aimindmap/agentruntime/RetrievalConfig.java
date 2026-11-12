package com.example.aimindmap.agentruntime;

import com.example.aimindmap.ai.AiGateway;
import com.example.aimindmap.vectorindex.QdrantProperties;
import com.example.aimindmap.vectorindex.VectorSearchService;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 检索质量（方案 §P1-5）的装配：{@code app.agent.retrieval.*} 配置 + 多查询检索器 bean。
 *
 * <p>为什么单独一个配置类而不是加进 {@link AgentRuntimeConfig}：那边被
 * {@code AgentRuntimeConfigTest} 用最小容器直接 {@code register(...)} 起来（只提供 AiGateway），
 * 把一个依赖 {@link VectorSearchService} 的 bean 塞进去会让那个既有用例在 refresh 阶段失败。
 *
 * <p>多查询开关默认 <b>true</b>（多查询生效）；关掉时 {@link MultiQueryRetriever} 内部直接委托旧路径，
 * 不需要重启装配。多查询内部的 <b>LLM 改写默认关</b>（{@code rewrite-llm-enabled=false}，
 * 所有者 2026-10-28 决定）——关着时 bean 照旧注入 {@link AiGateway}，但检索路径不会调用它。
 */
@Configuration
@EnableConfigurationProperties(RetrievalProperties.class)
public class RetrievalConfig {

    /**
     * 多查询检索器。{@link AiGateway} 不可用（未配 Key）时它只用规则改写，仍然可用；
     * LLM 改写默认关时它同样不碰该网关，因此这里不加 {@code @ConditionalOnProperty}，
     * 单测/运维都能通过容器取到同一个 bean。
     */
    @Bean
    public MultiQueryRetriever multiQueryRetriever(VectorSearchService vectorSearchService,
                                                   AiGateway aiGateway,
                                                   QdrantProperties qdrantProperties,
                                                   RetrievalProperties retrievalProperties) {
        return new MultiQueryRetriever(vectorSearchService, aiGateway, qdrantProperties,
                retrievalProperties);
    }
}
