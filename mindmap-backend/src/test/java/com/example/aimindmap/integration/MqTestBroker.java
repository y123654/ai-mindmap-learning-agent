package com.example.aimindmap.integration;

import com.rabbitmq.client.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 阶段四 4-4：全部 MQ 集成测试共享的 JVM 内单例 RabbitMQ（方案硬性约定 2/3）。
 * broker 一律由 Testcontainers 启动，禁止使用开发机上已有的 aimindmap-rabbitmq 容器。
 * 队列/交换机由被测应用启动时 RabbitAdmin 自动声明。
 */
public final class MqTestBroker {

    private static final Logger log = LoggerFactory.getLogger(MqTestBroker.class);

    /** Testcontainers 官方默认 RabbitMQ 镜像（与 1.20.x RabbitMQContainer 默认一致）。 */
    private static final DockerImageName IMAGE = DockerImageName.parse("rabbitmq:3.12-alpine");

    private static RabbitMQContainer container;
    private static CachingConnectionFactory driverConnectionFactory;
    private static RabbitTemplate driverTemplate;

    private MqTestBroker() {
    }

    /** 幂等启动；同一 JVM 内所有 IT 共用一个 broker。 */
    public static synchronized void start() {
        if (container == null) {
            container = new RabbitMQContainer(IMAGE);
            container.start();
            log.info("Testcontainers RabbitMQ 已启动 amqpPort={}", container.getAmqpPort());
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                closeDriverQuietly();
                if (container != null) {
                    container.stop();
                }
            }));
        }
    }

    public static String host() {
        return container.getHost();
    }

    public static int amqpPort() {
        return container.getAmqpPort();
    }

    /**
     * 测试驱动专用 RabbitTemplate（独立连接工厂，不经被测应用）：
     * 用于重发消息、purge、被动声明查消息数、从 DLQ 取信。
     */
    public static synchronized RabbitTemplate driver() {
        if (driverTemplate == null) {
            driverConnectionFactory = new CachingConnectionFactory();
            driverConnectionFactory.setHost(host());
            driverConnectionFactory.setPort(amqpPort());
            driverConnectionFactory.setUsername(container.getAdminUsername());
            driverConnectionFactory.setPassword(container.getAdminPassword());
            driverConnectionFactory.setVirtualHost("/");
            driverTemplate = new RabbitTemplate(driverConnectionFactory);
        }
        return driverTemplate;
    }

    /** 队列中的 ready 消息数（被动声明，不创建队列；队列未声明抛异常时由调用方容错）。 */
    public static int messageCount(String queueName) {
        Integer count = driver().execute((Channel channel) ->
                channel.queueDeclarePassive(queueName).getMessageCount());
        return count == null ? 0 : count;
    }

    /** purge 一个队列；队列尚未被任何应用上下文声明时安静跳过。 */
    public static void purgeQuietly(String queueName) {
        try {
            driver().execute((Channel channel) -> {
                channel.queuePurge(queueName);
                return null;
            });
        } catch (Exception e) {
            log.debug("purge 队列 {} 时忽略（可能尚未声明）：{}", queueName, e.toString());
        }
    }

    private static synchronized void closeDriverQuietly() {
        if (driverConnectionFactory != null) {
            try {
                driverConnectionFactory.destroy();
            } catch (Exception ignored) {
                // JVM 退出路径，静默
            }
            driverConnectionFactory = null;
            driverTemplate = null;
        }
    }
}