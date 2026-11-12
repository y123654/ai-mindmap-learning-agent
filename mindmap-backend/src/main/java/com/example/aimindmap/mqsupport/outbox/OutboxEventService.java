package com.example.aimindmap.mqsupport.outbox;

import com.example.aimindmap.mqsupport.TaskMessage;
import com.example.aimindmap.metrics.MqTaskMetrics;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 写本地发件箱（方案《阶段四·B》：业务服务在原事务内写 outbox）。
 *
 * <p>事务传播固定 REQUIRED：必须加入业务事务，业务回滚则 outbox 行一起回滚，
 * 这是 outbox 模式保证"业务数据与消息一致"的核心，禁止改成 REQUIRES_NEW。
 *
 * <p>阶段八-1：{@link #recordPending} 成功落库后按 taskType 记一次入队计数
 * （{@code aimindmap.task.enqueued}，方案 325 行「各任务入队」）。计数是纯记录，
 * 不参与事务判定、不改变返回值；序列化失败路径在上面已抛出并回滚，不会被计入。
 */
@Service
public class OutboxEventService {

    private static final Logger log = LoggerFactory.getLogger(OutboxEventService.class);

    private final OutboxEventRepository repository;
    private final ObjectMapper objectMapper;
    private final MqTaskMetrics mqTaskMetrics;

    public OutboxEventService(OutboxEventRepository repository, ObjectMapper objectMapper,
                              MqTaskMetrics mqTaskMetrics) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.mqTaskMetrics = mqTaskMetrics;
    }

    /**
     * 在当前业务事务内追加一条 PENDING outbox 事件。
     * payload 为整条 TaskMessage 的 Jackson JSON。
     *
     * @throws IllegalStateException 消息无法序列化（编程错误）：外抛让业务事务回滚，禁止静默
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public OutboxEventEntity recordPending(TaskMessage message) {
        String payload;
        try {
            payload = objectMapper.writeValueAsString(message);
        } catch (JsonProcessingException e) {
            log.error("outbox 消息序列化失败，业务事务将回滚 taskType={} taskId={}",
                    message.taskType(), message.taskId(), e);
            throw new IllegalStateException(
                    "outbox payload serialize failed for " + message.taskType() + ":" + message.taskId(), e);
        }
        OutboxEventEntity entity = new OutboxEventEntity(
                message.taskType().wireName(), message.taskId(), payload);
        OutboxEventEntity saved = repository.save(entity);
        mqTaskMetrics.recordEnqueued(message.taskType());
        return saved;
    }
}
