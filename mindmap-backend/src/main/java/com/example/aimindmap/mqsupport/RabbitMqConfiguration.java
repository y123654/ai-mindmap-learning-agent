package com.example.aimindmap.mqsupport;

import java.util.ArrayList;
import java.util.List;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.connection.RabbitConnectionFactoryBean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.amqp.CachingConnectionFactoryConfigurer;
import org.springframework.boot.autoconfigure.amqp.RabbitConnectionFactoryBeanConfigurer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * RabbitMQ 拓扑与监听容器工厂声明（方案《阶段四·A》，app.mq.enabled=true 时生效）。
 *
 * <p>本小阶段声明：持久化任务交换机 {@code aimindmap.tasks}、6 个持久化工作队列
 * （x-dead-letter-exchange 统一指向 {@code aimindmap.dlx}）、持久化死信交换机
 * {@code aimindmap.dlx}（fanout）与死信队列 {@code aimindmap.dlq}。
 * 工作队列当前全部空闲（无 @RabbitListener，4-3 起才开始监听向量两类队列）。
 *
 * <p>三档延时重试（2026-10-16 所有者拍板方案 A：每类任务 3 个专属重试队列，共 18 个）：
 * 队列名 aimindmap.retry.&lt;TaskType&gt;.5s/.30s/.2m，durable，x-message-ttl 分别为
 * 5000/30000/120000ms，x-dead-letter-exchange 指回 {@code aimindmap.tasks}，
 * x-dead-letter-routing-key 固定为本任务工作 routing key，因此 TTL 到期必定死信回到
 * 自己的工作队列。重试队列不绑定任何交换机：4-3 消费者经 AMQP 默认交换机（""）按队列名
 * 直接投递。本小阶段只声明队列与参数，不写任何消费者。
 *
 * <p>4-3 消费者重试流转（本类注释先行约定，代码在 4-3 实现）：
 * 消费者在消息头维护已重试次数；捕获可重试异常时，按 0→5s、1→30s、2→2m 选档，
 * 将消息发到对应重试队列（默认交换机 routing key = 重试队列名）；TTL 到期消息自动死信
 * 回 aimindmap.tasks 再入工作队列，形成一次重试；第 3 次重试后仍失败（第 4 次处理失败）
 * 对原始工作队列消息 nack(requeue=false)，由工作队列的 x-dead-letter-exchange=aimindmap.dlx
 * 进入 aimindmap.dlq。不可重试异常（非法参数/资源不存在）首次即 nack 直接进 DLQ。
 *
 * <p>双连接工厂：{@link #rabbitConnectionFactory} 为 Spring Boot 自动配置让位的主工厂
 * （publisher confirm correlated 由 spring.rabbitmq.* 经 configurer 统一配置），
 * 供 RabbitTemplate 与向量队列容器使用；{@link #aiRabbitConnectionFactory} 是独立物理
 * 连接池，AI 重调用队列经 {@link #aiRabbitListenerContainerFactory} 使用（阶段六-1 起
 * MINDMAP_SYNC 已接入；QUESTION_GENERATE/SESSION_SUMMARY 在 6-2/6-3 接入），并发限制 1~2，
 * 避免 AI 慢消费占满共享连接通道。
 */
@Configuration
@ConditionalOnProperty(name = "app.mq.enabled", havingValue = "true", matchIfMissing = true)
public class RabbitMqConfiguration {

    // ---------------- 交换机 / 死信 ----------------

    @Bean
    public TopicExchange taskExchange() {
        return new TopicExchange(MqTopology.TASK_EXCHANGE, true, false);
    }

    @Bean
    public FanoutExchange deadLetterExchange() {
        return new FanoutExchange(MqTopology.DEAD_LETTER_EXCHANGE, true, false);
    }

    @Bean
    public Queue deadLetterQueue() {
        return QueueBuilder.durable(MqTopology.DEAD_LETTER_QUEUE).build();
    }

    /**
     * 方案 A 的 18 个重试队列（6 任务 × 3 档），循环生成、不绑定交换机。
     * 用 {@link Declarables} 一次性注册，RabbitAdmin 启动时统一声明。
     */
    @Bean
    public Declarables retryQueues() {
        List<Queue> queues = new ArrayList<>();
        for (TaskType taskType : TaskType.values()) {
            for (MqTopology.RetryTier tier : MqTopology.RetryTier.values()) {
                queues.add(QueueBuilder
                        .durable(MqTopology.retryQueueName(taskType, tier))
                        .withArgument("x-message-ttl", tier.ttlMillis())
                        .withArgument("x-dead-letter-exchange", MqTopology.TASK_EXCHANGE)
                        .withArgument("x-dead-letter-routing-key", MqTopology.routingKey(taskType))
                        .build());
            }
        }
        return new Declarables(queues);
    }

    @Bean
    public Binding deadLetterBinding(@Qualifier("deadLetterExchange") FanoutExchange deadLetterExchange,
                                     @Qualifier("deadLetterQueue") Queue deadLetterQueue) {
        return BindingBuilder.bind(deadLetterQueue).to(deadLetterExchange);
    }

    // ---------------- 6 个工作队列 ----------------

    @Bean
    public Queue pdfImportQueue() {
        return taskQueue(TaskType.PDF_IMPORT);
    }

    @Bean
    public Queue vectorUpsertQueue() {
        return taskQueue(TaskType.VECTOR_UPSERT);
    }

    @Bean
    public Queue vectorDeleteQueue() {
        return taskQueue(TaskType.VECTOR_DELETE);
    }

    @Bean
    public Queue mindmapSyncQueue() {
        return taskQueue(TaskType.MINDMAP_SYNC);
    }

    @Bean
    public Queue questionGenerateQueue() {
        return taskQueue(TaskType.QUESTION_GENERATE);
    }

    @Bean
    public Queue sessionSummaryQueue() {
        return taskQueue(TaskType.SESSION_SUMMARY);
    }

    private Queue taskQueue(TaskType taskType) {
        return QueueBuilder.durable(MqTopology.queueName(taskType))
                .withArgument("x-dead-letter-exchange", MqTopology.DEAD_LETTER_EXCHANGE)
                .build();
    }

    // ---------------- 6 条工作队列绑定 ----------------

    @Bean
    public Binding pdfImportBinding(@Qualifier("taskExchange") TopicExchange taskExchange,
                                    @Qualifier("pdfImportQueue") Queue pdfImportQueue) {
        return bindTaskQueue(taskExchange, pdfImportQueue, TaskType.PDF_IMPORT);
    }

    @Bean
    public Binding vectorUpsertBinding(@Qualifier("taskExchange") TopicExchange taskExchange,
                                       @Qualifier("vectorUpsertQueue") Queue vectorUpsertQueue) {
        return bindTaskQueue(taskExchange, vectorUpsertQueue, TaskType.VECTOR_UPSERT);
    }

    @Bean
    public Binding vectorDeleteBinding(@Qualifier("taskExchange") TopicExchange taskExchange,
                                       @Qualifier("vectorDeleteQueue") Queue vectorDeleteQueue) {
        return bindTaskQueue(taskExchange, vectorDeleteQueue, TaskType.VECTOR_DELETE);
    }

    @Bean
    public Binding mindmapSyncBinding(@Qualifier("taskExchange") TopicExchange taskExchange,
                                      @Qualifier("mindmapSyncQueue") Queue mindmapSyncQueue) {
        return bindTaskQueue(taskExchange, mindmapSyncQueue, TaskType.MINDMAP_SYNC);
    }

    @Bean
    public Binding questionGenerateBinding(@Qualifier("taskExchange") TopicExchange taskExchange,
                                           @Qualifier("questionGenerateQueue") Queue questionGenerateQueue) {
        return bindTaskQueue(taskExchange, questionGenerateQueue, TaskType.QUESTION_GENERATE);
    }

    @Bean
    public Binding sessionSummaryBinding(@Qualifier("taskExchange") TopicExchange taskExchange,
                                         @Qualifier("sessionSummaryQueue") Queue sessionSummaryQueue) {
        return bindTaskQueue(taskExchange, sessionSummaryQueue, TaskType.SESSION_SUMMARY);
    }

    private Binding bindTaskQueue(TopicExchange taskExchange, Queue taskQueue, TaskType taskType) {
        return BindingBuilder.bind(taskQueue)
                .to(taskExchange)
                .with(MqTopology.routingKey(taskType));
    }

    // ---------------- 连接工厂（主 + AI 独立） ----------------

    /**
     * 主连接工厂：标记 {@link Primary} 后 Spring Boot 不再创建默认 ConnectionFactory，
     * RabbitTemplate 自动装配仍然生效（@ConditionalOnSingleCandidate 由 @Primary 满足）。
     */
    @Bean
    @Primary
    public CachingConnectionFactory rabbitConnectionFactory(
            RabbitConnectionFactoryBeanConfigurer rabbitConnectionFactoryBeanConfigurer,
            CachingConnectionFactoryConfigurer rabbitCachingConnectionFactoryConfigurer) throws Exception {
        return buildCachingConnectionFactory(
                rabbitConnectionFactoryBeanConfigurer, rabbitCachingConnectionFactoryConfigurer);
    }

    /**
     * AI 类队列独立连接工厂（阶段六复用）：与主工厂物理连接池隔离，
     * MINDMAP_SYNC / QUESTION_GENERATE / SESSION_SUMMARY 的慢消费不挤占向量链路通道。
     */
    @Bean
    public CachingConnectionFactory aiRabbitConnectionFactory(
            RabbitConnectionFactoryBeanConfigurer rabbitConnectionFactoryBeanConfigurer,
            CachingConnectionFactoryConfigurer rabbitCachingConnectionFactoryConfigurer) throws Exception {
        return buildCachingConnectionFactory(
                rabbitConnectionFactoryBeanConfigurer, rabbitCachingConnectionFactoryConfigurer);
    }

    private CachingConnectionFactory buildCachingConnectionFactory(
            RabbitConnectionFactoryBeanConfigurer connectionFactoryBeanConfigurer,
            CachingConnectionFactoryConfigurer cachingConnectionFactoryConfigurer) throws Exception {
        RabbitConnectionFactoryBean connectionFactoryBean = new RabbitConnectionFactoryBean();
        connectionFactoryBeanConfigurer.configure(connectionFactoryBean);
        connectionFactoryBean.afterPropertiesSet();
        com.rabbitmq.client.ConnectionFactory rabbitConnectionFactory = connectionFactoryBean.getObject();
        CachingConnectionFactory cachingConnectionFactory = new CachingConnectionFactory(rabbitConnectionFactory);
        cachingConnectionFactoryConfigurer.configure(cachingConnectionFactory);
        return cachingConnectionFactory;
    }

    // ---------------- 监听容器工厂（本小阶段不挂监听器） ----------------

    /**
     * 向量队列容器工厂（4-3 直接使用）：手动 ACK，并发 2、最大 8（对齐附录 A）。
     */
    @Bean
    public SimpleRabbitListenerContainerFactory vectorRabbitListenerContainerFactory(
            @Qualifier("rabbitConnectionFactory") ConnectionFactory
                    rabbitConnectionFactory) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(rabbitConnectionFactory);
        factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        factory.setConcurrentConsumers(2);
        factory.setMaxConcurrentConsumers(8);
        return factory;
    }

    /**
     * PDF 队列容器工厂（阶段五-A）：主连接工厂、手动 ACK，并发严格为 1（并发与最大并发
     * 均为 1）且 prefetch=1，保证 PDF 文件严格按投递顺序单线程解析。
     */
    @Bean
    public SimpleRabbitListenerContainerFactory pdfRabbitListenerContainerFactory(
            @Qualifier("rabbitConnectionFactory") ConnectionFactory
                    rabbitConnectionFactory) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(rabbitConnectionFactory);
        factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        factory.setConcurrentConsumers(1);
        factory.setMaxConcurrentConsumers(1);
        factory.setPrefetchCount(1);
        return factory;
    }

    /**
     * AI 类队列容器工厂（阶段六复用）：独立连接工厂，手动 ACK，并发 1、最大 2。
     * 阶段六-1 起 MINDMAP_SYNC 消费者使用本工厂；6-2/6-3 出题与摘要链路继续复用，不新增工厂。
     */
    @Bean
    public SimpleRabbitListenerContainerFactory aiRabbitListenerContainerFactory(
            @Qualifier("aiRabbitConnectionFactory") ConnectionFactory
                    aiRabbitConnectionFactory) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(aiRabbitConnectionFactory);
        factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        factory.setConcurrentConsumers(1);
        factory.setMaxConcurrentConsumers(2);
        return factory;
    }
}
