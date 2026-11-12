package com.example.aimindmap.mqsupport.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.example.aimindmap.mqsupport.NoopTaskMessagePublisher;
import com.example.aimindmap.mqsupport.RabbitTaskMessagePublisher;
import com.example.aimindmap.mqsupport.TaskMessagePublisher;
import com.example.aimindmap.metrics.MqTaskMetrics;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * 开关装配：mq=false 时 Relay/RelayService 不装配（事件留 PENDING 由轮询兜底），
 * 但原事务写 outbox 的 OutboxEventService 仍可用。不起完整上下文、不连中间件。
 *
 * <p>阶段八-1 适配：{@code OutboxEventService} 构造器新增 {@code MqTaskMetrics}（入队计数），
 * 因此这里按最小上下文补一个 {@link SimpleMeterRegistry} 与 {@code MqTaskMetrics} Bean，
 * 两个测试的断言语义<b>零改动</b>。
 */
class OutboxRelayConditionalWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(ObjectMapper.class, () -> new ObjectMapper().findAndRegisterModules())
            .withBean(OutboxEventRepository.class, () -> {
                OutboxEventRepository repository = mock(OutboxEventRepository.class);
                org.mockito.Mockito.when(repository.claimPending(org.mockito.ArgumentMatchers.anyInt()))
                        .thenReturn(java.util.List.of());
                return repository;
            })
            .withBean(TaskMessagePublisher.class,
                    () -> mock(com.example.aimindmap.mqsupport.NoopTaskMessagePublisher.class))
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
            .withUserConfiguration(OutboxRelay.class, OutboxRelayService.class,
                    OutboxEventService.class, MqTaskMetrics.class);

    @Test
    void enabledWiresRelayBeans() {
        runner.withPropertyValues("app.mq.enabled=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(OutboxRelay.class);
                    assertThat(context).hasSingleBean(OutboxRelayService.class);
                    assertThat(context).hasSingleBean(OutboxEventService.class);
                    assertThat(context).hasSingleBean(MqProperties.class);
                });
    }

    @Test
    void disabledSkipsRelayButKeepsEventWriter() {
        runner.withPropertyValues("app.mq.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(OutboxRelay.class);
                    assertThat(context).doesNotHaveBean(OutboxRelayService.class);
                    assertThat(context).hasSingleBean(OutboxEventService.class);
                });
    }
}
