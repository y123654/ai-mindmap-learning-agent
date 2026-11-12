package com.example.aimindmap.loadtest;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.aimindmap.ai.AiGateway;
import com.example.aimindmap.ai.OpenAiCompatibleAiGateway;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

/**
 * 阶段八-3：loadtest profile 下的真实应用上下文装配测试——
 * 与 ExternalIdEndToEndWiringTest 同范式（MOCK web 环境，数据库/中间件配置沿用
 * src/test 既有默认），额外激活 spring.profiles.active=loadtest。
 *
 * <p>锁死：①三个打桩 bean 在完整应用上下文里都是按类型注入的胜出者（@Primary）；
 * ②真实 bean（OpenAiChatModel / OpenAiEmbeddingModel 自动装配 / OpenAiCompatibleAiGateway）
 * 仍然共存于容器中——打桩只是"注入优先级覆盖"，不删除、不改名、不改任何既有装配，
 * profile 关闭即恢复原状（关闭态由 LoadTestStubWiringTest.profileOffRegistersNoStubBeans
 * 与既有 702 全绿基线共同佐证）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = "spring.profiles.active=loadtest")
class LoadTestProfileContextIntegrationTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void stubBeansArePrimaryInFullApplicationContext() {
        assertThat(context.getBean(ChatModel.class)).isInstanceOf(FixedDelayChatModel.class);
        assertThat(context.getBean(EmbeddingModel.class)).isInstanceOf(FixedVectorEmbeddingModel.class);
        assertThat(context.getBean(AiGateway.class)).isInstanceOf(FixedDelayAiGateway.class);
    }

    @Test
    void realBeansStillCoexistUnmodified() {
        // 真实 OpenAiChatModel（SpringAiChatConfig）仍在容器里，只是不再是按类型注入的胜出者
        assertThat(context.getBean("openAiChatModel", OpenAiChatModel.class))
                .isNotNull();
        assertThat(context.getBean(OpenAiCompatibleAiGateway.class)).isNotNull();
        // 打桩 EmbeddingModel 维度与真实配置一致（1024，text-embedding-v3）
        assertThat(context.getBean(EmbeddingModel.class).dimensions()).isEqualTo(1024);
    }
}
