package com.example.aimindmap.loadtest;

import com.example.aimindmap.ai.AiGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

/**
 * 阶段八-3：压测打桩 profile（方案 327 行「压测用打桩 profile（固定延迟假数据）防烧钱」）。
 *
 * <p><b>仅 loadtest profile 生效，生产零影响</b>：类级 @Profile("loadtest")，
 * 默认启动（不带 --spring.profiles.active=loadtest）时本配置与三个打桩 bean 完全不注册，
 * 既有装配（OpenAiChatModel / OpenAiEmbeddingModel 自动装配 / OpenAiCompatibleAiGateway）
 * 与 702 全绿基线逐字节一致。application.yml 零改动：所有默认值写在下方的
 * @Value 注解里（附录 A 无 app.loadtest.* 键，不扩配置面），需要调延迟时用
 * -Dapp.loadtest.ai-latency-ms=xxx 之类的 JVM 参数或环境变量覆盖即可。
 *
 * <p><b>打桩面（对照三个压测场景的完整调用链，与 MqIntegrationMocks 的覆盖面同源）</b>：
 * <ul>
 *   <li>ChatModel → {@link FixedDelayChatModel}：场景 A 的流式问答
 *       （KnowledgeAgentService/ChatClient）、记忆合并（MemoryVectorService）、
 *       会话摘要（SessionSummaryService）；</li>
 *   <li>EmbeddingModel → {@link FixedVectorEmbeddingModel}：RAG 检索（VectorSearchService）、
 *       文档/记忆向量索引（VectorIndexTaskProcessor、MemoryVectorService），
 *       固定 1024 维与 text-embedding-v3 配置一致；</li>
 *   <li>AiGateway → {@link FixedDelayAiGateway}：场景 C 的同步引擎 AI 归纳
 *       （SyncEngine.completeJsonForResult）。</li>
 * </ul>
 *
 * <p><b>不打桩的部分（有意保留真实链路）</b>：
 * <ul>
 *   <li>Qdrant（QdrantClient / GrpcQdrantVectorGateway）：本机 rag-qdrant 容器在跑，
 *       打桩向量维度与真实配置一致，直接真实读写向量库才能压出「Embedding→upsert→检索」
 *       完整链路，且 Qdrant 本地读写零费用——与 MqIntegrationMocks 打桩 Qdrant 的取舍不同，
 *       那是无 Docker 单测的妥协，压测要的是真实性；</li>
 *   <li>SyncEngine / MarkdownParser / 校验链：纯本地 CPU 逻辑，保留真实执行，
 *       场景 C 才能压出真实的任务处理与削峰追赶行为；</li>
 *   <li>RabbitMQ / Redis / PostgreSQL：压测目标就是它们的真实表现，一律不打桩。</li>
 * </ul>
 */
@Configuration
@Profile("loadtest")
public class LoadTestStubConfiguration {

    private static final Logger log = LoggerFactory.getLogger(LoadTestStubConfiguration.class);

    /** ChatModel 单次调用（含流式全程）的固定模拟延迟毫秒数。 */
    @Bean
    @Primary
    public ChatModel loadTestChatModel(
            @Value("${app.loadtest.ai-latency-ms:800}") long aiLatencyMs) {
        log.info("loadtest profile 已激活：ChatModel 打桩生效 operation=chat latencyMs={}", aiLatencyMs);
        return new FixedDelayChatModel(aiLatencyMs);
    }

    /** EmbeddingModel 单次调用的固定模拟延迟与向量维度（默认与 text-embedding-v3 一致）。 */
    @Bean
    @Primary
    public EmbeddingModel loadTestEmbeddingModel(
            @Value("${app.loadtest.embedding-latency-ms:50}") long embeddingLatencyMs,
            @Value("${app.loadtest.embedding-dimension:1024}") int embeddingDimension) {
        log.info("loadtest profile 已激活：EmbeddingModel 打桩生效 operation=embedding "
                + "latencyMs={} dimension={}", embeddingLatencyMs, embeddingDimension);
        return new FixedVectorEmbeddingModel(embeddingLatencyMs, embeddingDimension);
    }

    /** AiGateway 单次调用的固定模拟延迟毫秒数。 */
    @Bean
    @Primary
    public AiGateway loadTestAiGateway(
            @Value("${app.loadtest.gateway-latency-ms:800}") long gatewayLatencyMs) {
        log.info("loadtest profile 已激活：AiGateway 打桩生效 operation=gateway latencyMs={}",
                gatewayLatencyMs);
        return new FixedDelayAiGateway(gatewayLatencyMs);
    }
}
