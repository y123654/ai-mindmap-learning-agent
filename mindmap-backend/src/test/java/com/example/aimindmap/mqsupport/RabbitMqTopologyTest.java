package com.example.aimindmap.mqsupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.boot.autoconfigure.amqp.CachingConnectionFactoryConfigurer;
import org.springframework.boot.autoconfigure.amqp.RabbitConnectionFactoryBeanConfigurer;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 直接 new 拓扑配置类断言 Bean 元数据：不起 Spring 上下文、不连 MQ。
 * 只覆盖本小阶段范围（6 工作队列 + DLX/DLQ + 工厂）；重试队列待拓扑选型后另补。
 */
class RabbitMqTopologyTest {

    private final RabbitMqConfiguration configuration = new RabbitMqConfiguration();

    private record QueueBinding(TaskType taskType, Queue queue, Binding binding) {
    }

    private QueueBinding[] allTaskQueuesAndBindings() {
        TopicExchange taskExchange = configuration.taskExchange();
        return new QueueBinding[] {
            new QueueBinding(TaskType.PDF_IMPORT,
                    configuration.pdfImportQueue(),
                    configuration.pdfImportBinding(taskExchange, configuration.pdfImportQueue())),
            new QueueBinding(TaskType.VECTOR_UPSERT,
                    configuration.vectorUpsertQueue(),
                    configuration.vectorUpsertBinding(taskExchange, configuration.vectorUpsertQueue())),
            new QueueBinding(TaskType.VECTOR_DELETE,
                    configuration.vectorDeleteQueue(),
                    configuration.vectorDeleteBinding(taskExchange, configuration.vectorDeleteQueue())),
            new QueueBinding(TaskType.MINDMAP_SYNC,
                    configuration.mindmapSyncQueue(),
                    configuration.mindmapSyncBinding(taskExchange, configuration.mindmapSyncQueue())),
            new QueueBinding(TaskType.QUESTION_GENERATE,
                    configuration.questionGenerateQueue(),
                    configuration.questionGenerateBinding(taskExchange,
                            configuration.questionGenerateQueue())),
            new QueueBinding(TaskType.SESSION_SUMMARY,
                    configuration.sessionSummaryQueue(),
                    configuration.sessionSummaryBinding(taskExchange, configuration.sessionSummaryQueue()))
        };
    }

    @Test
    void sixWorkQueuesAreDurableAndDeadLetterToDlx() {
        for (QueueBinding item : allTaskQueuesAndBindings()) {
            Queue queue = item.queue();
            assertThat(queue.getName())
                    .as("queue name for %s", item.taskType())
                    .isEqualTo(MqTopology.queueName(item.taskType()));
            assertThat(queue.isDurable())
                    .as("queue %s must be durable", queue.getName())
                    .isTrue();
            assertThat(queue.getArguments())
                    .as("queue %s dead-letter argument", queue.getName())
                    .containsEntry("x-dead-letter-exchange", MqTopology.DEAD_LETTER_EXCHANGE);
            // 本小阶段不得提前声明重试相关参数
            assertThat(queue.getArguments()).doesNotContainKey("x-message-ttl");
        }
    }

    @Test
    void sixBindingsUseTaskRoutingKeysOnTaskExchange() {
        for (QueueBinding item : allTaskQueuesAndBindings()) {
            Binding binding = item.binding();
            assertThat(binding.getExchange()).isEqualTo(MqTopology.TASK_EXCHANGE);
            assertThat(binding.getRoutingKey()).isEqualTo(MqTopology.routingKey(item.taskType()));
            assertThat(binding.getDestination()).isEqualTo(MqTopology.queueName(item.taskType()));
            assertThat(binding.getDestinationType()).isEqualTo(Binding.DestinationType.QUEUE);
        }
    }

    @Test
    void taskExchangeAndDlxAndDlqAreDurableWithCorrectTypes() {
        TopicExchange taskExchange = configuration.taskExchange();
        assertThat(taskExchange.getName()).isEqualTo("aimindmap.tasks");
        assertThat(taskExchange.getType()).isEqualTo("topic");
        assertThat(taskExchange.isDurable()).isTrue();
        assertThat(taskExchange.isAutoDelete()).isFalse();

        FanoutExchange dlx = configuration.deadLetterExchange();
        assertThat(dlx.getName()).isEqualTo("aimindmap.dlx");
        assertThat(dlx.getType()).isEqualTo("fanout");
        assertThat(dlx.isDurable()).isTrue();

        Queue dlq = configuration.deadLetterQueue();
        assertThat(dlq.getName()).isEqualTo("aimindmap.dlq");
        assertThat(dlq.isDurable()).isTrue();
        assertThat(dlq.getArguments()).isEmpty();

        Binding dlqBinding = configuration.deadLetterBinding(dlx, dlq);
        assertThat(dlqBinding.getExchange()).isEqualTo("aimindmap.dlx");
        assertThat(dlqBinding.getDestination()).isEqualTo("aimindmap.dlq");
        assertThat(dlqBinding.getDestinationType()).isEqualTo(Binding.DestinationType.QUEUE);
    }

    @Test
    void eighteenRetryQueuesAreDurableWithTtlAndDeadLetterBackToWorkRoutingKey() {
        Declarables retryQueues = configuration.retryQueues();
        List<Queue> queues = new ArrayList<>(retryQueues.getDeclarablesByType(Queue.class));

        assertThat(queues).hasSize(18);
        // 重试队列不注册任何绑定：4-3 经默认交换机按队列名直接投递
        assertThat(retryQueues.getDeclarablesByType(Binding.class)).isEmpty();

        for (TaskType taskType : TaskType.values()) {
            for (MqTopology.RetryTier tier : MqTopology.RetryTier.values()) {
                String expectedName = MqTopology.retryQueueName(taskType, tier);
                Queue queue = queues.stream()
                        .filter(candidate -> candidate.getName().equals(expectedName))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("缺少重试队列: " + expectedName));

                assertThat(queue.isDurable())
                        .as("retry queue %s must be durable", expectedName)
                        .isTrue();
                Map<String, Object> arguments = queue.getArguments();
                assertThat(arguments)
                        .as("retry queue %s ttl", expectedName)
                        .containsEntry("x-message-ttl", tier.ttlMillis());
                assertThat(arguments)
                        .as("retry queue %s dead-letter exchange", expectedName)
                        .containsEntry("x-dead-letter-exchange", MqTopology.TASK_EXCHANGE);
                assertThat(arguments)
                        .as("retry queue %s dead-letter routing key", expectedName)
                        .containsEntry("x-dead-letter-routing-key", MqTopology.routingKey(taskType));
            }
        }
    }

    @Test
    void topologyDeclaresExactlyTwentyFiveQueuesAndTwoExchangesWithUniqueNames() {
        TopicExchange taskExchange = configuration.taskExchange();
        List<Queue> allQueues = new ArrayList<>();
        allQueues.add(configuration.deadLetterQueue());
        allQueues.add(configuration.pdfImportQueue());
        allQueues.add(configuration.vectorUpsertQueue());
        allQueues.add(configuration.vectorDeleteQueue());
        allQueues.add(configuration.mindmapSyncQueue());
        allQueues.add(configuration.questionGenerateQueue());
        allQueues.add(configuration.sessionSummaryQueue());
        allQueues.addAll(configuration.retryQueues().getDeclarablesByType(Queue.class));

        assertThat(allQueues).hasSize(25);
        assertThat(allQueues.stream().map(Queue::getName).distinct()).hasSize(25);

        List<org.springframework.amqp.core.Exchange> exchanges =
                List.of(taskExchange, configuration.deadLetterExchange());
        assertThat(exchanges).hasSize(2);
        assertThat(exchanges.stream().map(org.springframework.amqp.core.Exchange::getName).distinct())
                .hasSize(2)
                .containsExactlyInAnyOrder("aimindmap.tasks", "aimindmap.dlx");
    }

    @Test
    void workQueuesDeadLetterToDlxWhileRetryQueuesDeadLetterBackToTaskExchange() {
        // 工作队列 → DLX（不可重试归宿 DLQ）；重试队列 → tasks（TTL 到期回工作队列）
        assertThat(configuration.vectorUpsertQueue().getArguments())
                .containsEntry("x-dead-letter-exchange", "aimindmap.dlx");

        Queue firstRetry = new ArrayList<>(
                configuration.retryQueues().getDeclarablesByType(Queue.class)).stream()
                .filter(queue -> queue.getName()
                        .equals(MqTopology.retryQueueName(TaskType.VECTOR_UPSERT,
                                MqTopology.RetryTier.FIRST)))
                .findFirst()
                .orElseThrow();
        assertThat(firstRetry.getArguments())
                .containsEntry("x-dead-letter-exchange", "aimindmap.tasks")
                .containsEntry("x-dead-letter-routing-key", "aimindmap.task.VECTOR_UPSERT");
    }

    @Test
    void vectorContainerFactoryIsManualAckWithConcurrency2To8() {
        // spring-amqp 3.2 的容器并发数只有 setter，直接断言工厂字段（不创建容器、不连 MQ）
        SimpleRabbitListenerContainerFactory factory =
                configuration.vectorRabbitListenerContainerFactory(mock(ConnectionFactory.class));

        assertThat(ReflectionTestUtils.getField(factory, "acknowledgeMode"))
                .isEqualTo(AcknowledgeMode.MANUAL);
        assertThat(ReflectionTestUtils.getField(factory, "concurrentConsumers")).isEqualTo(2);
        assertThat(ReflectionTestUtils.getField(factory, "maxConcurrentConsumers")).isEqualTo(8);
    }

    @Test
    void aiContainerFactoryIsManualAckWithConcurrency1To2() {
        SimpleRabbitListenerContainerFactory factory =
                configuration.aiRabbitListenerContainerFactory(mock(ConnectionFactory.class));

        assertThat(ReflectionTestUtils.getField(factory, "acknowledgeMode"))
                .isEqualTo(AcknowledgeMode.MANUAL);
        assertThat(ReflectionTestUtils.getField(factory, "concurrentConsumers")).isEqualTo(1);
        assertThat(ReflectionTestUtils.getField(factory, "maxConcurrentConsumers")).isEqualTo(2);
    }

    @Test
    void primaryAndAiConnectionFactoriesAreIndependent() throws Exception {
        RabbitConnectionFactoryBeanConfigurer beanConfigurer =
                mock(RabbitConnectionFactoryBeanConfigurer.class);
        CachingConnectionFactoryConfigurer cachingConfigurer =
                mock(CachingConnectionFactoryConfigurer.class);

        CachingConnectionFactory primary = configuration.rabbitConnectionFactory(
                beanConfigurer, cachingConfigurer);
        CachingConnectionFactory ai = configuration.aiRabbitConnectionFactory(
                beanConfigurer, cachingConfigurer);

        assertThat(primary).isNotNull();
        assertThat(ai).isNotNull();
        assertThat(ai).isNotSameAs(primary);
    }
}
