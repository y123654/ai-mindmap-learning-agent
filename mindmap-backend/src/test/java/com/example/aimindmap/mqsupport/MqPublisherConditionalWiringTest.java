package com.example.aimindmap.mqsupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** app.mq.enabled 开关的条件装配测试：不起完整 Spring 上下文、不连 MQ。 */
class MqPublisherConditionalWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(ObjectMapper.class, () -> new ObjectMapper().findAndRegisterModules())
            .withBean(RabbitTemplate.class, () -> mock(RabbitTemplate.class))
            .withUserConfiguration(RabbitTaskMessagePublisher.class, NoopTaskMessagePublisher.class);

    @Test
    void enabledWiresRabbitPublisher() {
        runner.withPropertyValues("app.mq.enabled=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(TaskMessagePublisher.class);
                    assertThat(context.getBean(TaskMessagePublisher.class))
                            .isInstanceOf(RabbitTaskMessagePublisher.class);
                    assertThat(context).doesNotHaveBean(NoopTaskMessagePublisher.class);
                });
    }

    @Test
    void missingPropertyDefaultsToRabbitPublisher() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(TaskMessagePublisher.class);
            assertThat(context.getBean(TaskMessagePublisher.class))
                    .isInstanceOf(RabbitTaskMessagePublisher.class);
            assertThat(context).doesNotHaveBean(NoopTaskMessagePublisher.class);
        });
    }

    @Test
    void disabledWiresNoopPublisher() {
        runner.withPropertyValues("app.mq.enabled=false")
                .run(context -> {
                    assertThat(context).hasSingleBean(TaskMessagePublisher.class);
                    assertThat(context.getBean(TaskMessagePublisher.class))
                            .isInstanceOf(NoopTaskMessagePublisher.class);
                    assertThat(context).doesNotHaveBean(RabbitTaskMessagePublisher.class);
                });
    }
}
