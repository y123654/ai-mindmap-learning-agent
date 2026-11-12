package com.example.aimindmap.smoke;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.time.Duration;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

/**
 * 阶段三中间件手动冒烟测试（默认跳过，普通 mvn test 不会真正连接 MQ/Redis）。
 *
 * <p>为什么默认 @Disabled：本类依赖本机真实运行的 RabbitMQ 容器（D:\develop\mq-docker）
 * 与独立 Redis 容器，不属于 Mockito 单测范畴；方案 §3.2 测试红线禁止单测依赖真实
 * MQ/Redis，因此全量回归时必须保持跳过。
 *
 * <p>手动运行前提：
 * <ol>
 *   <li>Docker Desktop 已启动；在 D:\develop\mq-docker 执行 .\start.ps1，
 *       等 .\status.ps1 的 rabbitmq-diagnostics ping 返回 ok；</li>
 *   <li>独立 Redis 容器 redis 正在运行（127.0.0.1:6379）；</li>
 *   <li>本机 PostgreSQL 与 application.yml 默认配置可用（@SpringBootTest 会加载完整上下文）。</li>
 * </ol>
 *
 * <p>如何临时启用：临时把类上的 @Disabled 注解注释掉（或删除），在 IDEA 里直接运行本类；
 * 跑完务必恢复 @Disabled，不要带改动提交。连接参数全部走 application.yml 的
 * ${ENV:localhost 默认值}，本类不写任何真实账号口令。
 *
 * <p>隔离约定：RabbitMQ 使用唯一的 aimindmap.smoke.&lt;时间戳&gt; 临时队列，finally 删除；
 * Redis 只操作 db2 的 aimindmap:smoke:* 键，finally 删除，绝不触碰其他 db。
 */
@Disabled("阶段三手动冒烟：依赖本机真实 RabbitMQ/Redis，违反 §3.2 单测红线，默认跳过")
@SpringBootTest
class MiddlewareSmokeManualTest {

    @Autowired private RabbitTemplate rabbitTemplate;
    @Autowired private StringRedisTemplate stringRedisTemplate;

    @Test
    void rabbitmqTemporaryQueueRoundTrip() throws Exception {
        String queue = "aimindmap.smoke." + System.currentTimeMillis();
        String payload = "aimindmap stage3 smoke " + System.currentTimeMillis();

        // 非持久化、独占、自动删除的临时队列，即便清理遗漏重启后也不会残留
        rabbitTemplate.execute(channel -> {
            channel.queueDeclare(queue, false, true, true, null);
            return null;
        });
        try {
            // 默认交换机 "" 的 routing key 即队列名
            rabbitTemplate.convertAndSend("", queue, payload);
            Object received = rabbitTemplate.receiveAndConvert(queue, 5000);
            assertNotNull(received, "5 秒内未从临时队列取回消息，请确认 RabbitMQ 已就绪");
            assertEquals(payload, received.toString());
        } finally {
            rabbitTemplate.execute(channel -> {
                channel.queueDelete(queue);
                return null;
            });
        }
    }

    @Test
    void redisDb2PrefixedKeyRoundTrip() {
        String key = "aimindmap:smoke:" + System.currentTimeMillis();
        String payload = "aimindmap-stage3-smoke-" + System.currentTimeMillis();

        // EXPIRE 兜底：万一 JVM 被强杀没走到 finally，键也会 60 秒后自行消失
        stringRedisTemplate.opsForValue().set(key, payload, Duration.ofSeconds(60));
        try {
            String received = stringRedisTemplate.opsForValue().get(key);
            assertEquals(payload, received);
        } finally {
            stringRedisTemplate.delete(key);
        }
    }
}
