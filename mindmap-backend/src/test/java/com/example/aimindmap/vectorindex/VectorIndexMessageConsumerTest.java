package com.example.aimindmap.vectorindex;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.aimindmap.metrics.MqTaskMetrics;
import com.example.aimindmap.mqsupport.MqTopology;
import com.example.aimindmap.mqsupport.TaskMessage;
import com.example.aimindmap.mqsupport.TaskType;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.rabbitmq.client.Channel;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;

/** 4-3 向量消费者单测：纯 Mockito，Channel/RabbitTemplate 一律 mock，不连真实 MQ。 */
class VectorIndexMessageConsumerTest {

    private static final long TAG = 17L;

    private final ObjectMapper objectMapper = new ObjectMapper()
            .findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private final RabbitTemplate rabbitTemplate = org.mockito.Mockito.mock(RabbitTemplate.class);
    private final VectorIndexTaskService taskService =
            org.mockito.Mockito.mock(VectorIndexTaskService.class);
    private final VectorIndexTaskProcessor processor =
            org.mockito.Mockito.mock(VectorIndexTaskProcessor.class);
    private final Channel channel = org.mockito.Mockito.mock(Channel.class);
    /** 阶段八-1：指标用真实 SimpleMeterRegistry（不 mock 指标组件），既有断言语义零改动。 */
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final MqTaskMetrics mqTaskMetrics = new MqTaskMetrics(meterRegistry);

    private final VectorIndexMessageConsumer consumer =
            new VectorIndexMessageConsumer(objectMapper, rabbitTemplate, taskService, processor,
                    mqTaskMetrics);

    private VectorIndexTaskService.TaskLoad upsertLoad;
    private VectorIndexTaskService.TaskLoad deleteLoad;

    @BeforeEach
    void setUp() {
        upsertLoad = new VectorIndexTaskService.TaskLoad(
                42L, 1L, VectorResourceType.DOCUMENT_CHUNK, "3", 0, VectorTaskOperation.UPSERT);
        deleteLoad = new VectorIndexTaskService.TaskLoad(
                43L, 1L, VectorResourceType.MINDMAP_NODE, "4:n8", 0, VectorTaskOperation.DELETE);
    }

    // ---------- 成功路径 ----------

    @Test
    void upsertSuccessMarksResultInsertsIdempotentAndAcks() throws Exception {
        TaskMessage message = taskMessage(TaskType.VECTOR_UPSERT, 42L, "3", "UPSERT");
        VectorIndexTaskProcessor.ProcessResult result =
                new VectorIndexTaskProcessor.ProcessResult.UpsertSucceeded(UUID.randomUUID(), "h1");
        when(taskService.claimForMessage(42L)).thenReturn(true);
        when(taskService.loadTask(42L)).thenReturn(upsertLoad);
        when(processor.process(upsertLoad)).thenReturn(result);

        consumer.onUpsert(amqpMessage(message), channel, TAG);

        verify(taskService).completeFromMessage(upsertLoad, result, "VECTOR_UPSERT:42");
        verify(channel).basicAck(TAG, false);
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
        verifyNoInteractions(rabbitTemplate);
    }

    @Test
    void deleteSuccessMarksResultAndAcks() throws Exception {
        TaskMessage message = taskMessage(TaskType.VECTOR_DELETE, 43L, "4:n8", "DELETE");
        VectorIndexTaskProcessor.ProcessResult result =
                new VectorIndexTaskProcessor.ProcessResult.ProcessDeleteSucceeded();
        when(taskService.claimForMessage(43L)).thenReturn(true);
        when(taskService.loadTask(43L)).thenReturn(deleteLoad);
        when(processor.process(deleteLoad)).thenReturn(result);

        consumer.onDelete(amqpMessage(message), channel, TAG);

        verify(taskService).completeFromMessage(deleteLoad, result, "VECTOR_DELETE:43");
        verify(channel).basicAck(TAG, false);
        verifyNoInteractions(rabbitTemplate);
    }

    @Test
    void skippedResultAlsoCompletesAndAcks() throws Exception {
        TaskMessage message = taskMessage(TaskType.VECTOR_UPSERT, 42L, "3", "UPSERT");
        VectorIndexTaskProcessor.ProcessResult result =
                new VectorIndexTaskProcessor.ProcessResult.Skipped("h-same");
        when(taskService.claimForMessage(42L)).thenReturn(true);
        when(taskService.loadTask(42L)).thenReturn(upsertLoad);
        when(processor.process(upsertLoad)).thenReturn(result);

        consumer.onUpsert(amqpMessage(message), channel, TAG);

        verify(taskService).completeFromMessage(upsertLoad, result, "VECTOR_UPSERT:42");
        verify(channel).basicAck(TAG, false);
    }

    @Test
    void claimZeroRowsAcksWithoutProcessing() throws Exception {
        TaskMessage message = taskMessage(TaskType.VECTOR_UPSERT, 42L, "3", "UPSERT");
        when(taskService.claimForMessage(42L)).thenReturn(false);

        consumer.onUpsert(amqpMessage(message), channel, TAG);

        verify(taskService, never()).loadTask(anyLong());
        verify(processor, never()).process(any());
        verify(channel).basicAck(TAG, false);
    }

    @Test
    void idempotentUniqueConflictAcksAsAlreadyConsumed() throws Exception {
        TaskMessage message = taskMessage(TaskType.VECTOR_UPSERT, 42L, "3", "UPSERT");
        when(taskService.claimForMessage(42L)).thenReturn(true);
        when(taskService.loadTask(42L)).thenReturn(upsertLoad);
        when(processor.process(upsertLoad)).thenReturn(
                new VectorIndexTaskProcessor.ProcessResult.ProcessDeleteSucceeded());
        doThrow(new DataIntegrityViolationException("idempotent pkey conflict"))
                .when(taskService).completeFromMessage(any(), any(), anyString());

        consumer.onUpsert(amqpMessage(message), channel, TAG);

        verify(channel).basicAck(TAG, false);
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
    }

    // ---------- 可重试：三档延时重试 ----------

    @Test
    void retryableFirstFailureGoesToFiveSecondsQueueAndAcksOriginal() throws Exception {
        assertRetryForwarding(null, 0, MqTopology.RetryTier.FIRST, 1);
    }

    @Test
    void retryableSecondFailureGoesToThirtySecondsQueue() throws Exception {
        assertRetryForwarding(1, 1, MqTopology.RetryTier.SECOND, 2);
    }

    @Test
    void retryableThirdFailureGoesToTwoMinutesQueue() throws Exception {
        assertRetryForwarding("2", 2, MqTopology.RetryTier.THIRD, 3);
    }

    private void assertRetryForwarding(Object headerValue, int currentCount,
                                       MqTopology.RetryTier expectedTier, int expectedNextCount)
            throws Exception {
        TaskMessage message = taskMessage(TaskType.VECTOR_UPSERT, 42L, "3", "UPSERT");
        when(taskService.claimForMessage(42L)).thenReturn(true);
        when(taskService.loadTask(42L)).thenReturn(upsertLoad);
        when(processor.process(upsertLoad))
                .thenThrow(new VectorIndexExternalException("Qdrant 写入失败",
                        new RuntimeException("grpc UNKNOWN")));

        Message incoming = amqpMessage(message, "VECTOR_UPSERT:42", headerValue,
                Map.of("x-custom-trace", "trace-1"));
        consumer.onUpsert(incoming, channel, TAG);

        verify(taskService).requeueForMessageRetry(42L, "Qdrant 写入失败");
        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(rabbitTemplate).send(eq(""),
                eq(MqTopology.retryQueueName(TaskType.VECTOR_UPSERT, expectedTier)),
                captor.capture());
        Message forwarded = captor.getValue();
        assertThat(forwarded.getBody()).isEqualTo(incoming.getBody());
        assertThat(forwarded.getMessageProperties().getMessageId()).isEqualTo("VECTOR_UPSERT:42");
        assertThat(forwarded.getMessageProperties().getHeaders())
                .containsEntry(MqTopology.RETRY_COUNT_HEADER, expectedNextCount)
                .containsEntry("x-custom-trace", "trace-1");
        verify(channel).basicAck(TAG, false);
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
        assertThat(currentCount).isEqualTo(VectorIndexMessageConsumer.parseRetryCount(
                incoming.getMessageProperties().getHeaders()));
    }

    @Test
    void fourthProcessingFailureNacksToDeadLetterQueueAndMarksFailed() throws Exception {
        TaskMessage message = taskMessage(TaskType.VECTOR_DELETE, 43L, "4:n8", "DELETE");
        when(taskService.claimForMessage(43L)).thenReturn(true);
        when(taskService.loadTask(43L)).thenReturn(deleteLoad);
        when(processor.process(deleteLoad))
                .thenThrow(new VectorIndexExternalException("Qdrant 删除失败",
                        new RuntimeException("timeout")));
        List<Map<String, Object>> xDeath = List.of(Map.of(
                "queue", MqTopology.retryQueueName(TaskType.VECTOR_DELETE, MqTopology.RetryTier.THIRD),
                "reason", "expired", "count", 3L));

        consumer.onDelete(amqpMessage(message, "VECTOR_DELETE:43", 3, Map.of("x-death", xDeath)),
                channel, TAG);

        verify(taskService).markFailed(deleteLoad, "Qdrant 删除失败");
        verify(channel).basicNack(TAG, false, false);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
        verifyNoInteractions(rabbitTemplate);
    }

    @Test
    void retryRowResetDatabaseFailureNacksWithRequeue() throws Exception {
        TaskMessage message = taskMessage(TaskType.VECTOR_UPSERT, 42L, "3", "UPSERT");
        when(taskService.claimForMessage(42L)).thenReturn(true);
        when(taskService.loadTask(42L)).thenReturn(upsertLoad);
        when(processor.process(upsertLoad))
                .thenThrow(new VectorIndexExternalException("Qdrant 写入失败", new RuntimeException()));
        org.mockito.Mockito.doThrow(new DataAccessResourceFailureException("db down"))
                .when(taskService).requeueForMessageRetry(anyLong(), anyString());

        consumer.onUpsert(amqpMessage(message), channel, TAG);

        verify(channel).basicNack(TAG, false, true);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
        verifyNoInteractions(rabbitTemplate);
    }

    @Test
    void retryForwardBrokerFailureNacksWithRequeue() throws Exception {
        TaskMessage message = taskMessage(TaskType.VECTOR_UPSERT, 42L, "3", "UPSERT");
        when(taskService.claimForMessage(42L)).thenReturn(true);
        when(taskService.loadTask(42L)).thenReturn(upsertLoad);
        when(processor.process(upsertLoad))
                .thenThrow(new VectorIndexExternalException("Qdrant 写入失败", new RuntimeException()));
        org.mockito.Mockito.doThrow(new AmqpException("broker channel closed"))
                .when(rabbitTemplate).send(anyString(), anyString(), any(Message.class));

        consumer.onUpsert(amqpMessage(message), channel, TAG);

        verify(channel).basicNack(TAG, false, true);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
    }

    // ---------- 不可重试 ----------

    @Test
    void permanentFailureNacksImmediatelyAndMarksFailed() throws Exception {
        TaskMessage message = taskMessage(TaskType.VECTOR_UPSERT, 42L, "3", "UPSERT");
        when(taskService.claimForMessage(42L)).thenReturn(true);
        when(taskService.loadTask(42L)).thenReturn(upsertLoad);
        when(processor.process(upsertLoad))
                .thenThrow(new VectorIndexPermanentException("笔记已不存在，无法向量化 documentId=3"));

        consumer.onUpsert(amqpMessage(message), channel, TAG);

        verify(taskService).markFailed(upsertLoad, "笔记已不存在，无法向量化 documentId=3");
        verify(channel).basicNack(TAG, false, false);
        verifyNoInteractions(rabbitTemplate);
    }

    @Test
    void illegalArgumentFailureIsTreatedAsPermanent() throws Exception {
        TaskMessage message = taskMessage(TaskType.VECTOR_UPSERT, 42L, "3", "UPSERT");
        when(taskService.claimForMessage(42L)).thenReturn(true);
        when(taskService.loadTask(42L)).thenReturn(upsertLoad);
        when(processor.process(upsertLoad)).thenThrow(new NumberFormatException("For input string: \"x\""));

        consumer.onUpsert(amqpMessage(message), channel, TAG);

        verify(channel).basicNack(TAG, false, false);
        verifyNoInteractions(rabbitTemplate);
    }

    @Test
    void unexpectedExceptionNacksToDeadLetterQueue() throws Exception {
        TaskMessage message = taskMessage(TaskType.VECTOR_UPSERT, 42L, "3", "UPSERT");
        when(taskService.claimForMessage(42L)).thenReturn(true);
        when(taskService.loadTask(42L)).thenReturn(upsertLoad);
        when(processor.process(upsertLoad)).thenThrow(new RuntimeException("NPE boom"));

        consumer.onUpsert(amqpMessage(message), channel, TAG);

        verify(taskService).markFailed(eq(upsertLoad), anyString());
        verify(channel).basicNack(TAG, false, false);
    }

    // ---------- 消息本身非法 ----------

    @Test
    void corruptBodyNacksWithoutTouchingProcessor() throws Exception {
        Message bad = amqpRawMessage("{not-json".getBytes(StandardCharsets.UTF_8), "VECTOR_UPSERT:42");

        consumer.onUpsert(bad, channel, TAG);

        verify(taskService, never()).claimForMessage(anyLong());
        verify(processor, never()).process(any());
        verify(channel).basicNack(TAG, false, false);
    }

    @Test
    void messageIdMismatchNacks() throws Exception {
        TaskMessage message = taskMessage(TaskType.VECTOR_UPSERT, 42L, "3", "UPSERT");

        consumer.onUpsert(amqpMessage(message, "VECTOR_UPSERT:999"), channel, TAG);

        verify(taskService, never()).claimForMessage(anyLong());
        verify(channel).basicNack(TAG, false, false);
    }

    @Test
    void bodyTaskTypeMismatchWithQueueNacks() throws Exception {
        TaskMessage message = taskMessage(TaskType.VECTOR_DELETE, 42L, "3", "DELETE");

        consumer.onUpsert(amqpMessage(message, "VECTOR_DELETE:42"), channel, TAG);

        verify(taskService, never()).claimForMessage(anyLong());
        verify(channel).basicNack(TAG, false, false);
    }

    @Test
    void claimedButTaskRowMissingNacksToDeadLetterQueue() throws Exception {
        TaskMessage message = taskMessage(TaskType.VECTOR_UPSERT, 42L, "3", "UPSERT");
        when(taskService.claimForMessage(42L)).thenReturn(true);
        when(taskService.loadTask(42L)).thenReturn(null);

        consumer.onUpsert(amqpMessage(message), channel, TAG);

        verify(processor, never()).process(any());
        verify(channel).basicNack(TAG, false, false);
    }

    // ---------- 阶段八-1：业务指标埋点（只断言计数/耗时，既有 ack/nack 断言语义零改动） ----------

    @Test
    void upsertSuccessRecordsCounterWithUpsertTaskTypeTag() throws Exception {
        TaskMessage message = taskMessage(TaskType.VECTOR_UPSERT, 42L, "3", "UPSERT");
        VectorIndexTaskProcessor.ProcessResult result =
                new VectorIndexTaskProcessor.ProcessResult.UpsertSucceeded(UUID.randomUUID(), "h1");
        when(taskService.claimForMessage(42L)).thenReturn(true);
        when(taskService.loadTask(42L)).thenReturn(upsertLoad);
        when(processor.process(upsertLoad)).thenReturn(result);

        consumer.onUpsert(amqpMessage(message), channel, TAG);

        assertThat(meterRegistry.counter("aimindmap.task.consumed",
                "task_type", "VECTOR_UPSERT", "outcome", "success").count()).isEqualTo(1.0);
        assertThat(meterRegistry.timer("aimindmap.task.consume",
                "task_type", "VECTOR_UPSERT", "outcome", "success").count()).isEqualTo(1L);
    }

    @Test
    void deleteSuccessRecordsCounterWithDeleteTaskTypeTag() throws Exception {
        TaskMessage message = taskMessage(TaskType.VECTOR_DELETE, 43L, "4:n8", "DELETE");
        VectorIndexTaskProcessor.ProcessResult result =
                new VectorIndexTaskProcessor.ProcessResult.ProcessDeleteSucceeded();
        when(taskService.claimForMessage(43L)).thenReturn(true);
        when(taskService.loadTask(43L)).thenReturn(deleteLoad);
        when(processor.process(deleteLoad)).thenReturn(result);

        consumer.onDelete(amqpMessage(message), channel, TAG);

        assertThat(meterRegistry.counter("aimindmap.task.consumed",
                "task_type", "VECTOR_DELETE", "outcome", "success").count()).isEqualTo(1.0);
        assertThat(meterRegistry.find("aimindmap.task.consumed")
                .tag("task_type", "VECTOR_UPSERT").counter()).isNull();
    }

    @Test
    void skippedResultRecordsSkippedOutcome() throws Exception {
        TaskMessage message = taskMessage(TaskType.VECTOR_UPSERT, 42L, "3", "UPSERT");
        VectorIndexTaskProcessor.ProcessResult result =
                new VectorIndexTaskProcessor.ProcessResult.Skipped("h-same");
        when(taskService.claimForMessage(42L)).thenReturn(true);
        when(taskService.loadTask(42L)).thenReturn(upsertLoad);
        when(processor.process(upsertLoad)).thenReturn(result);

        consumer.onUpsert(amqpMessage(message), channel, TAG);

        assertThat(meterRegistry.counter("aimindmap.task.consumed",
                "task_type", "VECTOR_UPSERT", "outcome", "skipped").count()).isEqualTo(1.0);
    }

    // ---------- helpers ----------

    private static TaskMessage taskMessage(TaskType type, long taskId, String entityId, String op) {
        return new TaskMessage(type, taskId, entityId, op, Instant.parse("2026-10-17T08:00:00Z"));
    }

    private Message amqpMessage(TaskMessage message) throws Exception {
        return amqpMessage(message, message.taskType().name() + ":" + message.taskId());
    }

    private Message amqpMessage(TaskMessage message, String messageId) throws Exception {
        return amqpMessage(message, messageId, null, Map.of());
    }

    private Message amqpMessage(TaskMessage message, String messageId, Object retryCount,
                                Map<String, Object> extraHeaders) throws Exception {
        byte[] body = objectMapper.writeValueAsBytes(message);
        MessageProperties properties = new MessageProperties();
        properties.setMessageId(messageId);
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setContentEncoding(StandardCharsets.UTF_8.name());
        if (retryCount != null) {
            properties.setHeader(MqTopology.RETRY_COUNT_HEADER, retryCount);
        }
        extraHeaders.forEach(properties::setHeader);
        return new Message(body, properties);
    }

    private Message amqpRawMessage(byte[] body, String messageId) {
        MessageProperties properties = new MessageProperties();
        properties.setMessageId(messageId);
        return new Message(body, properties);
    }
}
