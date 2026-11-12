package com.example.aimindmap.loadtest;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.aimindmap.ai.AiGateway;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.document.Document;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import reactor.core.publisher.Flux;

/**
 * 阶段八-3：打桩 profile 的条件装配测试（ApplicationContextRunner，不起完整上下文、
 * 不连任何中间件，绕开沙盒回环限制）。
 *
 * <p>锁死两条红线：①profile 关闭时本配置一个 bean 都不注册（既有装配零影响）；
 * ②profile 开启时三个打桩 bean 均为 @Primary——即使容器里同时存在"真实"类型的
 * 竞争 bean，按接口注入拿到的也一定是打桩实现。
 */
class LoadTestStubWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(LoadTestStubConfiguration.class);

    @Test
    void profileOffRegistersNoStubBeans() {
        runner.run(context -> {
            assertThat(context).doesNotHaveBean(FixedDelayChatModel.class);
            assertThat(context).doesNotHaveBean(FixedVectorEmbeddingModel.class);
            assertThat(context).doesNotHaveBean(FixedDelayAiGateway.class);
            assertThat(context).doesNotHaveBean(LoadTestStubConfiguration.class);
        });
    }

    @Test
    void profileOnRegistersStubBeansWithCodeDefaults() {
        runner.withPropertyValues("spring.profiles.active=loadtest")
                .run(context -> {
                    assertThat(context).hasSingleBean(ChatModel.class);
                    assertThat(context.getBean(ChatModel.class))
                            .isInstanceOf(FixedDelayChatModel.class);
                    assertThat(context).hasSingleBean(EmbeddingModel.class);
                    assertThat(context.getBean(EmbeddingModel.class))
                            .isInstanceOf(FixedVectorEmbeddingModel.class);
                    assertThat(context).hasSingleBean(AiGateway.class);
                    assertThat(context.getBean(AiGateway.class))
                            .isInstanceOf(FixedDelayAiGateway.class);
                    // 维度默认值写在代码 @Value 里（application.yml 零改动），与 text-embedding-v3 一致
                    assertThat(context.getBean(EmbeddingModel.class).dimensions()).isEqualTo(1024);
                });
    }

    @Test
    void stubBeansWinOverCompetingRealBeansAsPrimary() {
        runner.withPropertyValues("spring.profiles.active=loadtest")
                .withBean("realChatModel", ChatModel.class, DummyChatModel::new)
                .withBean("realEmbeddingModel", EmbeddingModel.class, DummyEmbeddingModel::new)
                .withBean("realAiGateway", AiGateway.class, DummyAiGateway::new)
                .run(context -> {
                    assertThat(context.getBean(ChatModel.class))
                            .isInstanceOf(FixedDelayChatModel.class);
                    assertThat(context.getBean(EmbeddingModel.class))
                            .isInstanceOf(FixedVectorEmbeddingModel.class);
                    assertThat(context.getBean(AiGateway.class))
                            .isInstanceOf(FixedDelayAiGateway.class);
                });
    }

    @Test
    void latencyPropertiesOverrideCodeDefaults() {
        runner.withPropertyValues("spring.profiles.active=loadtest",
                        "app.loadtest.ai-latency-ms=0",
                        "app.loadtest.embedding-latency-ms=0",
                        "app.loadtest.embedding-dimension=8",
                        "app.loadtest.gateway-latency-ms=0")
                .run(context -> {
                    EmbeddingModel embeddingModel = context.getBean(EmbeddingModel.class);
                    assertThat(embeddingModel.dimensions()).isEqualTo(8);
                    // 延迟覆盖为 0 后立即返回（允许 2 秒宽松上界，纯防挂死）
                    long started = System.nanoTime();
                    embeddingModel.embed("压测文本");
                    long elapsedMillis = (System.nanoTime() - started) / 1_000_000L;
                    assertThat(elapsedMillis).isLessThan(2000L);
                });
    }

    /** 竞争用最小 ChatModel（模拟"真实"bean 同时在容器里）。 */
    private static final class DummyChatModel implements ChatModel {
        @Override
        public ChatResponse call(Prompt prompt) {
            throw new UnsupportedOperationException("竞争 bean 不应被按类型注入选中");
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            throw new UnsupportedOperationException("竞争 bean 不应被按类型注入选中");
        }
    }

    /** 竞争用最小 EmbeddingModel。 */
    private static final class DummyEmbeddingModel implements EmbeddingModel {
        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            throw new UnsupportedOperationException("竞争 bean 不应被按类型注入选中");
        }

        @Override
        public float[] embed(Document document) {
            throw new UnsupportedOperationException("竞争 bean 不应被按类型注入选中");
        }
    }

    /** 竞争用最小 AiGateway。 */
    private static final class DummyAiGateway implements AiGateway {
        @Override
        public <T> T completeJson(com.example.aimindmap.ai.AiJsonRequest request, Class<T> responseType) {
            throw new UnsupportedOperationException("竞争 bean 不应被按类型注入选中");
        }

        @Override
        public String completeChat(com.example.aimindmap.ai.AiChatRequest request) {
            throw new UnsupportedOperationException("竞争 bean 不应被按类型注入选中");
        }

        @Override
        public void streamChat(com.example.aimindmap.ai.AiChatRequest request,
                               java.util.function.Consumer<String> onDelta) {
            throw new UnsupportedOperationException("竞争 bean 不应被按类型注入选中");
        }

        @Override
        public boolean configured() {
            return false;
        }

        @Override
        public String provider() {
            return "dummy";
        }

        @Override
        public String model() {
            return "dummy";
        }
    }
}
