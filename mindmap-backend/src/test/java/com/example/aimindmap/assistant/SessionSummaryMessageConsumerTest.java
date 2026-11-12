package com.example.aimindmap.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.aimindmap.assistant.SessionSummaryService.SummaryOutcome;
import com.example.aimindmap.metrics.MqTaskMetrics;
import com.example.aimindmap.mqsupport.MqTopology;
import com.example.aimindmap.mqsupport.RetriableTaskException;
import com.example.aimindmap.mqsupport.TaskMessage;
import com.example.aimindmap.mqsupport.TaskType;
import com.example.aimindmap.mqsupport.outbox.IdempotentConsumptionRepository;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.rabbitmq.client.Channel;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;

/** 阶段六-3：摘要消费者单测，纯 Mockito，Channel/RabbitTemplate/Repository 一律 mock，不连真实 MQ/LLM/DB。 */
class SessionSummaryMessageConsumerTest {

    private static final long TAG = 41L;
    private static final long TRIGGER_MESSAGE_ID = 42L;

    private final ObjectMapper objectMapper = new ObjectMapper()
            .findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private final RabbitTemplate rabbitTemplate = org.mockito.Mockito.mock(RabbitTemplate.class);
    private final SessionSummaryService sessionSummaryService =
            org.mockito.Mockito.mock(SessionSummaryService.class);
    private final IdempotentConsumptionRepository idempotentConsumptionRepository =
            org.mockito.Mockito.mock(IdempotentConsumptionRepository.class);
    private final Channel channel = org.mockito.Mockito.mock(Channel.class);
    /** 阶段八-1：指标用真实 SimpleMeterRegistry（不 mock 指标组件），既有断言语义零改动。 */
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final MqTaskMetrics mqTaskMetrics = new MqTaskMetrics(meterRegistry);

    private final SessionSummaryMessageConsumer consumer = new SessionSummaryMessageConsumer(
            objectMapper, rabbitTemplate, sessionSummaryService, idempotentConsumptionRepository,
            mqTaskMetrics);

    @BeforeEach
    void notConsumedYet() {
        when(idempotentConsumptionRepository.existsById("SESSION_SUMMARY:" + TRIGGER_MESSAGE_ID))
                .thenReturn(false);
    }

    // ---------- 正常结果：SUMMARIZED/SKIPPED ack；CONFIGURATION/TRIGGER_MISSING 进 DLQ ----------

    @Test
    void summarizedOutcomeAcks() throws Exception {
        when(sessionSummaryService.handleSummaryMessage(TRIGGER_MESSAGE_ID))
                .thenReturn(SummaryOutcome.SUMMARIZED);

        consumer.onMessage(amqpMessage(taskMessage(TRIGGER_MESSAGE_ID)), channel, TAG);

        verify(sessionSummaryService).handleSummaryMessage(TRIGGER_MESSAGE_ID);
        verify(channel).basicAck(TAG, false);
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
        verifyNoInteractions(rabbitTemplate);
    }

    @Test
    void skippedOutcomeAcks() throws Exception {
        when(sessionSummaryService.handleSummaryMessage(TRIGGER_MESSAGE_ID))
                .thenReturn(SummaryOutcome.SKIPPED);

        consumer.onMessage(amqpMessage(taskMessage(TRIGGER_MESSAGE_ID)), channel, TAG);

        verify(channel).basicAck(TAG, false);
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
        verifyNoInteractions(rabbitTemplate);
    }

    @Test
    void triggerMissingNacksToDeadLetterQueueWithoutRetry() throws Exception {
        when(sessionSummaryService.handleSummaryMessage(TRIGGER_MESSAGE_ID))
                .thenReturn(SummaryOutcome.TRIGGER_MISSING);

        consumer.onMessage(amqpMessage(taskMessage(TRIGGER_MESSAGE_ID)), channel, TAG);

        verify(channel).basicNack(TAG, false, false);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
        verifyNoInteractions(rabbitTemplate);
    }

    @Test
    void configurationOutcomeNacksToDeadLetterQueueWithoutRetry() throws Exception {
        when(sessionSummaryService.handleSummaryMessage(TRIGGER_MESSAGE_ID))
                .thenReturn(SummaryOutcome.CONFIGURATION);

        consumer.onMessage(amqpMessage(taskMessage(TRIGGER_MESSAGE_ID)), channel, TAG);

        verify(channel).basicNack(TAG, false, false);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
        verifyNoInteractions(rabbitTemplate);
    }

    // ---------- 幂等预查：命中直接 ack，不调业务 ----------

    @Test
    void alreadyConsumedAcksWithoutCallingService() throws Exception {
        when(idempotentConsumptionRepository.existsById("SESSION_SUMMARY:" + TRIGGER_MESSAGE_ID))
                .thenReturn(true);

        consumer.onMessage(amqpMessage(taskMessage(TRIGGER_MESSAGE_ID)), channel, TAG);

        verify(sessionSummaryService, never()).handleSummaryMessage(anyLong());
        verify(channel).basicAck(TAG, false);
        verifyNoInteractions(rabbitTemplate);
    }

    @Test
    void idempotentPrecheckDatabaseFailureRequeuesOriginalMessage() throws Exception {
        when(idempotentConsumptionRepository.existsById("SESSION_SUMMARY:" + TRIGGER_MESSAGE_ID))
                .thenThrow(new DataAccessResourceFailureException("db down"));

        consumer.onMessage(amqpMessage(taskMessage(TRIGGER_MESSAGE_ID)), channel, TAG);

        verify(sessionSummaryService, never()).handleSummaryMessage(anyLong());
        verify(channel).basicNack(TAG, false, true);
        verifyNoInteractions(rabbitTemplate);
    }

    // ---------- 毒消息：反序列化失败/taskType 不符/messageId 不符/taskId 空 → DLQ ----------

    @Test
    void corruptBodyNacksToDeadLetterQueue() throws Exception {
        Message bad = amqpRawMessage("{not-json".getBytes(StandardCharsets.UTF_8),
                "SESSION_SUMMARY:42");

        consumer.onMessage(bad, channel, TAG);

        verify(sessionSummaryService, never()).handleSummaryMessage(anyLong());
        verify(channel).basicNack(TAG, false, false);
    }

    @Test
    void bodyTaskTypeMismatchNacksToDeadLetterQueue() throws Exception {
        TaskMessage message = new TaskMessage(TaskType.PDF_IMPORT, TRIGGER_MESSAGE_ID, "10",
                "SUMMARY", Instant.parse("2026-10-19T08:00:00Z"));

        consumer.onMessage(amqpMessage(message, "PDF_IMPORT:42"), channel, TAG);

        verify(sessionSummaryService, never()).handleSummaryMessage(anyLong());
        verify(channel).basicNack(TAG, false, false);
    }

    @Test
    void messageIdMismatchNacksToDeadLetterQueue() throws Exception {
        consumer.onMessage(amqpMessage(taskMessage(TRIGGER_MESSAGE_ID), "SESSION_SUMMARY:999"),
                channel, TAG);

        verify(sessionSummaryService, never()).handleSummaryMessage(anyLong());
        verify(channel).basicNack(TAG, false, false);
    }

    @Test
    void nullTaskIdNacksToDeadLetterQueue() throws Exception {
        TaskMessage message = new TaskMessage(TaskType.SESSION_SUMMARY, null, "10", "SUMMARY",
                Instant.parse("2026-10-19T08:00:00Z"));

        consumer.onMessage(amqpMessage(message, "SESSION_SUMMARY:null"), channel, TAG);

        verify(sessionSummaryService, never()).handleSummaryMessage(anyLong());
        verify(channel).basicNack(TAG, false, false);
    }

    // ---------- 基础设施异常：三档重试 / 耗尽 DLQ / 转发失败 requeue ----------

    @Test
    void retriableExceptionForwardsToFirstRetryQueueAndAcks() throws Exception {
        when(sessionSummaryService.handleSummaryMessage(TRIGGER_MESSAGE_ID))
                .thenThrow(new RetriableTaskException("retriable AI failure: AI_HTTP_429",
                        new RuntimeException("rate limited")));

        consumer.onMessage(amqpMessage(taskMessage(TRIGGER_MESSAGE_ID)), channel, TAG);

        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
        verify(rabbitTemplate).send(eq(""),
                eq(MqTopology.retryQueueName(TaskType.SESSION_SUMMARY, MqTopology.RetryTier.FIRST)),
                messageCaptor.capture());
        assertThat(messageCaptor.getValue().getMessageProperties().getMessageId())
                .isEqualTo("SESSION_SUMMARY:42");
        assertThat((Integer) messageCaptor.getValue().getMessageProperties()
                .getHeader(MqTopology.RETRY_COUNT_HEADER)).isEqualTo(1);
        verify(channel).basicAck(TAG, false);
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
    }

    @Test
    void dataAccessExceptionForwardsToFirstRetryQueue() throws Exception {
        when(sessionSummaryService.handleSummaryMessage(TRIGGER_MESSAGE_ID))
                .thenThrow(new DataAccessResourceFailureException("db down"));

        consumer.onMessage(amqpMessage(taskMessage(TRIGGER_MESSAGE_ID)), channel, TAG);

        verify(rabbitTemplate).send(eq(""),
                eq(MqTopology.retryQueueName(TaskType.SESSION_SUMMARY, MqTopology.RetryTier.FIRST)),
                any(Message.class));
        verify(channel).basicAck(TAG, false);
    }

    @Test
    void retryCountOneForwardsToSecondTier() throws Exception {
        when(sessionSummaryService.handleSummaryMessage(TRIGGER_MESSAGE_ID))
                .thenThrow(new RetriableTaskException("retriable AI failure: AI_TIMEOUT",
                        new RuntimeException("timeout")));

        consumer.onMessage(amqpMessage(taskMessage(TRIGGER_MESSAGE_ID), "SESSION_SUMMARY:42", 1,
                Map.of()), channel, TAG);

        verify(rabbitTemplate).send(eq(""),
                eq(MqTopology.retryQueueName(TaskType.SESSION_SUMMARY, MqTopology.RetryTier.SECOND)),
                any(Message.class));
        verify(channel).basicAck(TAG, false);
    }

    @Test
    void retryCountTwoForwardsToThirdTier() throws Exception {
        when(sessionSummaryService.handleSummaryMessage(TRIGGER_MESSAGE_ID))
                .thenThrow(new RetriableTaskException("retriable AI failure: AI_HTTP_503",
                        new RuntimeException("busy")));

        consumer.onMessage(amqpMessage(taskMessage(TRIGGER_MESSAGE_ID), "SESSION_SUMMARY:42", 2,
                Map.of()), channel, TAG);

        verify(rabbitTemplate).send(eq(""),
                eq(MqTopology.retryQueueName(TaskType.SESSION_SUMMARY, MqTopology.RetryTier.THIRD)),
                any(Message.class));
        verify(channel).basicAck(TAG, false);
    }

    @Test
    void thirdTierExhaustedNacksToDeadLetterQueueWithoutForwarding() throws Exception {
        when(sessionSummaryService.handleSummaryMessage(TRIGGER_MESSAGE_ID))
                .thenThrow(new RetriableTaskException("retriable AI failure: AI_HTTP_500",
                        new RuntimeException("error")));

        consumer.onMessage(amqpMessage(taskMessage(TRIGGER_MESSAGE_ID), "SESSION_SUMMARY:42", 3,
                Map.of("x-death", "present")), channel, TAG);

        verifyNoInteractions(rabbitTemplate);
        verify(channel).basicNack(TAG, false, false);
    }

    @Test
    void invalidRetryCountHeaderIsTreatedAsZero() throws Exception {
        when(sessionSummaryService.handleSummaryMessage(TRIGGER_MESSAGE_ID))
                .thenThrow(new RetriableTaskException("retry", new RuntimeException("x")));

        consumer.onMessage(amqpMessage(taskMessage(TRIGGER_MESSAGE_ID), "SESSION_SUMMARY:42",
                "not-a-number", Map.of()), channel, TAG);

        verify(rabbitTemplate).send(eq(""),
                eq(MqTopology.retryQueueName(TaskType.SESSION_SUMMARY, MqTopology.RetryTier.FIRST)),
                any(Message.class));
        verify(channel).basicAck(TAG, false);
    }

    @Test
    void retryForwardAmqpFailureRequeuesOriginalMessage() throws Exception {
        when(sessionSummaryService.handleSummaryMessage(TRIGGER_MESSAGE_ID))
                .thenThrow(new RetriableTaskException("retry", new RuntimeException("x")));
        org.mockito.Mockito.doThrow(new AmqpException("broker gone"))
                .when(rabbitTemplate).send(any(), any(), any(Message.class));

        consumer.onMessage(amqpMessage(taskMessage(TRIGGER_MESSAGE_ID)), channel, TAG);

        verify(channel).basicNack(TAG, false, true);
    }

    @Test
    void idempotentUniqueConflictAcksAsDuplicate() throws Exception {
        when(sessionSummaryService.handleSummaryMessage(TRIGGER_MESSAGE_ID))
                .thenThrow(new DataIntegrityViolationException("duplicate key"));

        consumer.onMessage(amqpMessage(taskMessage(TRIGGER_MESSAGE_ID)), channel, TAG);

        verify(channel).basicAck(TAG, false);
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
        verifyNoInteractions(rabbitTemplate);
    }

    @Test
    void unexpectedRuntimeExceptionNacksToDeadLetterQueue() throws Exception {
        when(sessionSummaryService.handleSummaryMessage(TRIGGER_MESSAGE_ID))
                .thenThrow(new IllegalStateException("boom"));

        consumer.onMessage(amqpMessage(taskMessage(TRIGGER_MESSAGE_ID)), channel, TAG);

        verify(channel).basicNack(TAG, false, false);
        verifyNoInteractions(rabbitTemplate);
    }

    // ---------- 阶段八-1：业务指标埋点（只断言计数/耗时，既有 ack/nack 断言语义零改动） ----------

    @Test
    void summarizedOutcomeRecordsSuccessCounterAndTimer() throws Exception {
        when(sessionSummaryService.handleSummaryMessage(TRIGGER_MESSAGE_ID))
                .thenReturn(SummaryOutcome.SUMMARIZED);

        consumer.onMessage(amqpMessage(taskMessage(TRIGGER_MESSAGE_ID)), channel, TAG);

        assertThat(meterRegistry.counter("aimindmap.task.consumed",
                "task_type", "SESSION_SUMMARY", "outcome", "success").count()).isEqualTo(1.0);
        assertThat(meterRegistry.timer("aimindmap.task.consume",
                "task_type", "SESSION_SUMMARY", "outcome", "success").count()).isEqualTo(1L);
    }

    @Test
    void skippedOutcomeRecordsSkippedCounter() throws Exception {
        when(sessionSummaryService.handleSummaryMessage(TRIGGER_MESSAGE_ID))
                .thenReturn(SummaryOutcome.SKIPPED);

        consumer.onMessage(amqpMessage(taskMessage(TRIGGER_MESSAGE_ID)), channel, TAG);

        assertThat(meterRegistry.counter("aimindmap.task.consumed",
                "task_type", "SESSION_SUMMARY", "outcome", "skipped").count()).isEqualTo(1.0);
    }

    @Test
    void idempotentPrecheckHitRecordsDuplicateCounter() throws Exception {
        when(idempotentConsumptionRepository.existsById("SESSION_SUMMARY:" + TRIGGER_MESSAGE_ID))
                .thenReturn(true);

        consumer.onMessage(amqpMessage(taskMessage(TRIGGER_MESSAGE_ID)), channel, TAG);

        assertThat(meterRegistry.counter("aimindmap.task.consumed",
                "task_type", "SESSION_SUMMARY", "outcome", "duplicate").count()).isEqualTo(1.0);
        verifyNoInteractions(sessionSummaryService);
    }

    @Test
    void configurationOutcomeRecordsDeadLetterCounterWithReason() throws Exception {
        when(sessionSummaryService.handleSummaryMessage(TRIGGER_MESSAGE_ID))
                .thenReturn(SummaryOutcome.CONFIGURATION);

        consumer.onMessage(amqpMessage(taskMessage(TRIGGER_MESSAGE_ID)), channel, TAG);

        assertThat(meterRegistry.counter("aimindmap.task.dead.letter",
                "task_type", "SESSION_SUMMARY", "reason", "configuration_failure").count()).isEqualTo(1.0);
    }

    // ---------- helpers ----------

    private static TaskMessage taskMessage(long triggerMessageId) {
        return new TaskMessage(TaskType.SESSION_SUMMARY, triggerMessageId, "10", "SUMMARY",
                Instant.parse("2026-10-19T08:00:00Z"));
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
