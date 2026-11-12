package com.example.aimindmap.question;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.aimindmap.metrics.MqTaskMetrics;
import com.example.aimindmap.mqsupport.MqTopology;
import com.example.aimindmap.mqsupport.RetriableTaskException;
import com.example.aimindmap.mqsupport.TaskMessage;
import com.example.aimindmap.mqsupport.TaskType;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.rabbitmq.client.Channel;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;

/** 阶段六-2：出题消费者单测，纯 Mockito，Channel/RabbitTemplate 一律 mock，不连真实 MQ/LLM/DB。 */
class QuestionGenerationMessageConsumerTest {

    private static final long TAG = 31L;

    private final ObjectMapper objectMapper = new ObjectMapper()
            .findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private final RabbitTemplate rabbitTemplate = org.mockito.Mockito.mock(RabbitTemplate.class);
    private final QuestionService questionService = org.mockito.Mockito.mock(QuestionService.class);
    private final Channel channel = org.mockito.Mockito.mock(Channel.class);
    /** 阶段八-1：指标用真实 SimpleMeterRegistry（不 mock 指标组件），既有断言语义零改动。 */
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final MqTaskMetrics mqTaskMetrics = new MqTaskMetrics(meterRegistry);

    private final QuestionGenerationMessageConsumer consumer =
            new QuestionGenerationMessageConsumer(objectMapper, rabbitTemplate, questionService,
                    mqTaskMetrics);

    // ---------- 四类 GenerationOutcome：COMPLETED/FAILED_BUSINESS/EARLY_EXIT ack；CONFIGURATION 进 DLQ ----------

    @Test
    void completedOutcomeAcks() throws Exception {
        when(questionService.claimForMessage(42L)).thenReturn(true);
        when(questionService.processGenerationJob(42L))
                .thenReturn(QuestionService.GenerationOutcome.COMPLETED);

        consumer.onMessage(amqpMessage(taskMessage(42L)), channel, TAG);

        verify(questionService).processGenerationJob(42L);
        verify(channel).basicAck(TAG, false);
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
        verifyNoInteractions(rabbitTemplate);
    }

    @Test
    void failedBusinessOutcomeAcksAndNeverToDlq() throws Exception {
        when(questionService.claimForMessage(42L)).thenReturn(true);
        when(questionService.processGenerationJob(42L))
                .thenReturn(QuestionService.GenerationOutcome.FAILED_BUSINESS);

        consumer.onMessage(amqpMessage(taskMessage(42L)), channel, TAG);

        verify(channel).basicAck(TAG, false);
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
        verifyNoInteractions(rabbitTemplate);
    }

    @Test
    void earlyExitOutcomeAcks() throws Exception {
        when(questionService.claimForMessage(42L)).thenReturn(true);
        when(questionService.processGenerationJob(42L))
                .thenReturn(QuestionService.GenerationOutcome.EARLY_EXIT);

        consumer.onMessage(amqpMessage(taskMessage(42L)), channel, TAG);

        verify(channel).basicAck(TAG, false);
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
        verifyNoInteractions(rabbitTemplate);
    }

    @Test
    void failedConfigurationOutcomeNacksToDeadLetterQueueWithoutRetry() throws Exception {
        when(questionService.claimForMessage(42L)).thenReturn(true);
        when(questionService.processGenerationJob(42L))
                .thenReturn(QuestionService.GenerationOutcome.FAILED_CONFIGURATION);

        consumer.onMessage(amqpMessage(taskMessage(42L)), channel, TAG);

        verify(questionService, never()).requeueForMessageRetry(anyLong());
        verify(channel).basicNack(TAG, false, false);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
        verifyNoInteractions(rabbitTemplate);
    }

    // ---------- 幂等：重复投递 / 终态任务 ----------

    @Test
    void claimZeroRowsWithExistingJobAcksWithoutProcessing() throws Exception {
        when(questionService.claimForMessage(42L)).thenReturn(false);
        when(questionService.jobExists(42L)).thenReturn(true);

        consumer.onMessage(amqpMessage(taskMessage(42L)), channel, TAG);

        verify(questionService, never()).processGenerationJob(anyLong());
        verify(channel).basicAck(TAG, false);
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
    }

    // ---------- 毒消息：反序列化/头体交叉校验/任务行不存在 → DLQ ----------

    @Test
    void corruptBodyNacksToDeadLetterQueue() throws Exception {
        Message bad = amqpRawMessage("{not-json".getBytes(StandardCharsets.UTF_8), "QUESTION_GENERATE:42");

        consumer.onMessage(bad, channel, TAG);

        verify(questionService, never()).claimForMessage(anyLong());
        verify(questionService, never()).processGenerationJob(anyLong());
        verify(channel).basicNack(TAG, false, false);
    }

    @Test
    void bodyTaskTypeMismatchNacksToDeadLetterQueue() throws Exception {
        TaskMessage message = new TaskMessage(TaskType.PDF_IMPORT, 42L, "1", "GENERATE",
                Instant.parse("2026-10-19T08:00:00Z"));

        consumer.onMessage(amqpMessage(message, "PDF_IMPORT:42"), channel, TAG);

        verify(questionService, never()).claimForMessage(anyLong());
        verify(channel).basicNack(TAG, false, false);
    }

    @Test
    void messageIdMismatchNacksToDeadLetterQueue() throws Exception {
        consumer.onMessage(amqpMessage(taskMessage(42L), "QUESTION_GENERATE:999"), channel, TAG);

        verify(questionService, never()).claimForMessage(anyLong());
        verify(channel).basicNack(TAG, false, false);
    }

    @Test
    void nullJobIdNacksToDeadLetterQueue() throws Exception {
        TaskMessage message = new TaskMessage(TaskType.QUESTION_GENERATE, null, "1", "GENERATE",
                Instant.parse("2026-10-19T08:00:00Z"));

        consumer.onMessage(amqpMessage(message, "QUESTION_GENERATE:null"), channel, TAG);

        verify(questionService, never()).claimForMessage(anyLong());
        verify(channel).basicNack(TAG, false, false);
    }

    @Test
    void claimZeroRowsButJobMissingNacksToDeadLetterQueue() throws Exception {
        when(questionService.claimForMessage(42L)).thenReturn(false);
        when(questionService.jobExists(42L)).thenReturn(false);

        consumer.onMessage(amqpMessage(taskMessage(42L)), channel, TAG);

        verify(questionService, never()).processGenerationJob(anyLong());
        verify(channel).basicNack(TAG, false, false);
    }

    // ---------- 基础设施异常：三档重试 / 耗尽 DLQ / 复位与转发失败 requeue ----------

    @Test
    void databaseFailureOnClaimRequeuesOriginalMessage() throws Exception {
        when(questionService.claimForMessage(42L))
                .thenThrow(new DataAccessResourceFailureException("db down"));

        consumer.onMessage(amqpMessage(taskMessage(42L)), channel, TAG);

        verify(questionService, never()).processGenerationJob(anyLong());
        verify(channel).basicNack(TAG, false, true);
        verifyNoInteractions(rabbitTemplate);
    }

    @Test
    void retriableExceptionForwardsToFirstRetryQueueAndAcks() throws Exception {
        when(questionService.claimForMessage(42L)).thenReturn(true);
        when(questionService.processGenerationJob(42L))
                .thenThrow(new RetriableTaskException("retriable AI failure: AI_HTTP_429",
                        new RuntimeException("rate limited")));

        consumer.onMessage(amqpMessage(taskMessage(42L)), channel, TAG);

        verify(questionService).requeueForMessageRetry(42L);
        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
        verify(rabbitTemplate).send(eq(""),
                eq(MqTopology.retryQueueName(TaskType.QUESTION_GENERATE, MqTopology.RetryTier.FIRST)),
                messageCaptor.capture());
        assertThat(messageCaptor.getValue().getMessageProperties().getMessageId())
                .isEqualTo("QUESTION_GENERATE:42");
        assertThat((Integer) messageCaptor.getValue().getMessageProperties()
                .getHeader(MqTopology.RETRY_COUNT_HEADER)).isEqualTo(1);
        verify(channel).basicAck(TAG, false);
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
    }

    @Test
    void databaseFailureOnProcessForwardsToFirstRetryQueue() throws Exception {
        when(questionService.claimForMessage(42L)).thenReturn(true);
        when(questionService.processGenerationJob(42L))
                .thenThrow(new DataAccessResourceFailureException("db down"));

        consumer.onMessage(amqpMessage(taskMessage(42L)), channel, TAG);

        verify(questionService).requeueForMessageRetry(42L);
        verify(rabbitTemplate).send(eq(""),
                eq(MqTopology.retryQueueName(TaskType.QUESTION_GENERATE, MqTopology.RetryTier.FIRST)),
                any(Message.class));
        verify(channel).basicAck(TAG, false);
    }

    @Test
    void retryCountOneForwardsToSecondTier() throws Exception {
        when(questionService.claimForMessage(42L)).thenReturn(true);
        when(questionService.processGenerationJob(42L))
                .thenThrow(new RetriableTaskException("retriable AI failure: AI_TIMEOUT",
                        new RuntimeException("timeout")));

        consumer.onMessage(amqpMessage(taskMessage(42L), "QUESTION_GENERATE:42", 1, Map.of()),
                channel, TAG);

        verify(rabbitTemplate).send(eq(""),
                eq(MqTopology.retryQueueName(TaskType.QUESTION_GENERATE, MqTopology.RetryTier.SECOND)),
                any(Message.class));
        verify(channel).basicAck(TAG, false);
    }

    @Test
    void retryCountTwoForwardsToThirdTier() throws Exception {
        when(questionService.claimForMessage(42L)).thenReturn(true);
        when(questionService.processGenerationJob(42L))
                .thenThrow(new RetriableTaskException("retriable AI failure: AI_HTTP_503",
                        new RuntimeException("busy")));

        consumer.onMessage(amqpMessage(taskMessage(42L), "QUESTION_GENERATE:42", 2, Map.of()),
                channel, TAG);

        verify(rabbitTemplate).send(eq(""),
                eq(MqTopology.retryQueueName(TaskType.QUESTION_GENERATE, MqTopology.RetryTier.THIRD)),
                any(Message.class));
        verify(channel).basicAck(TAG, false);
    }

    @Test
    void invalidRetryCountHeaderIsTreatedAsZero() throws Exception {
        when(questionService.claimForMessage(42L)).thenReturn(true);
        when(questionService.processGenerationJob(42L))
                .thenThrow(new RetriableTaskException("retry", new RuntimeException("x")));

        consumer.onMessage(amqpMessage(taskMessage(42L), "QUESTION_GENERATE:42", "not-a-number", Map.of()),
                channel, TAG);

        verify(rabbitTemplate).send(eq(""),
                eq(MqTopology.retryQueueName(TaskType.QUESTION_GENERATE, MqTopology.RetryTier.FIRST)),
                any(Message.class));
        verify(channel).basicAck(TAG, false);
    }

    @Test
    void thirdTierExhaustedNacksToDeadLetterQueueWithoutForwarding() throws Exception {
        when(questionService.claimForMessage(42L)).thenReturn(true);
        when(questionService.processGenerationJob(42L))
                .thenThrow(new RetriableTaskException("retriable AI failure: AI_HTTP_500",
                        new RuntimeException("error")));

        consumer.onMessage(amqpMessage(taskMessage(42L), "QUESTION_GENERATE:42", 3,
                Map.of("x-death", "present")), channel, TAG);

        verify(questionService, never()).requeueForMessageRetry(anyLong());
        verifyNoInteractions(rabbitTemplate);
        verify(channel).basicNack(TAG, false, false);
    }

    @Test
    void retryRowResetFailureRequeuesOriginalMessage() throws Exception {
        when(questionService.claimForMessage(42L)).thenReturn(true);
        when(questionService.processGenerationJob(42L))
                .thenThrow(new RetriableTaskException("retry", new RuntimeException("x")));
        org.mockito.Mockito.doThrow(new DataAccessResourceFailureException("reset failed"))
                .when(questionService).requeueForMessageRetry(42L);

        consumer.onMessage(amqpMessage(taskMessage(42L)), channel, TAG);

        verifyNoInteractions(rabbitTemplate);
        verify(channel).basicNack(TAG, false, true);
    }

    @Test
    void retryForwardAmqpFailureRequeuesOriginalMessage() throws Exception {
        when(questionService.claimForMessage(42L)).thenReturn(true);
        when(questionService.processGenerationJob(42L))
                .thenThrow(new RetriableTaskException("retry", new RuntimeException("x")));
        org.mockito.Mockito.doThrow(new AmqpException("broker gone"))
                .when(rabbitTemplate).send(any(), any(), any(Message.class));

        consumer.onMessage(amqpMessage(taskMessage(42L)), channel, TAG);

        verify(channel).basicNack(TAG, false, true);
    }

    @Test
    void idempotentUniqueConflictOnCompletionAcksAsDuplicate() throws Exception {
        when(questionService.claimForMessage(42L)).thenReturn(true);
        when(questionService.processGenerationJob(42L))
                .thenThrow(new DataIntegrityViolationException("duplicate key"));

        consumer.onMessage(amqpMessage(taskMessage(42L)), channel, TAG);

        verify(channel).basicAck(TAG, false);
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
        verifyNoInteractions(rabbitTemplate);
    }

    @Test
    void unexpectedRuntimeExceptionNacksToDeadLetterQueue() throws Exception {
        when(questionService.claimForMessage(42L)).thenReturn(true);
        when(questionService.processGenerationJob(42L)).thenThrow(new IllegalStateException("boom"));

        consumer.onMessage(amqpMessage(taskMessage(42L)), channel, TAG);

        verify(channel).basicNack(TAG, false, false);
        verifyNoInteractions(rabbitTemplate);
    }

    // ---------- 阶段八-1：业务指标埋点（只断言计数/耗时，既有 ack/nack 断言语义零改动） ----------

    @Test
    void completedOutcomeRecordsSuccessCounterAndTimer() throws Exception {
        when(questionService.claimForMessage(42L)).thenReturn(true);
        when(questionService.processGenerationJob(42L))
                .thenReturn(QuestionService.GenerationOutcome.COMPLETED);

        consumer.onMessage(amqpMessage(taskMessage(42L)), channel, TAG);

        assertThat(meterRegistry.counter("aimindmap.task.consumed",
                "task_type", "QUESTION_GENERATE", "outcome", "success").count()).isEqualTo(1.0);
        assertThat(meterRegistry.timer("aimindmap.task.consume",
                "task_type", "QUESTION_GENERATE", "outcome", "success").count()).isEqualTo(1L);
    }

    @Test
    void retriableFailureForwardedRecordsRetryOutcome() throws Exception {
        when(questionService.claimForMessage(42L)).thenReturn(true);
        when(questionService.processGenerationJob(42L))
                .thenThrow(new RetriableTaskException("retriable AI failure: AI_TIMEOUT",
                        new RuntimeException("ai timeout")));

        consumer.onMessage(amqpMessage(taskMessage(42L)), channel, TAG);

        assertThat(meterRegistry.counter("aimindmap.task.consumed",
                "task_type", "QUESTION_GENERATE", "outcome", "retry_forwarded").count()).isEqualTo(1.0);
        assertThat(meterRegistry.find("aimindmap.task.dead.letter").counter()).isNull();
    }

    @Test
    void retriesExhaustedRecordsDeadLetterCounterWithReason() throws Exception {
        when(questionService.claimForMessage(42L)).thenReturn(true);
        when(questionService.processGenerationJob(42L))
                .thenThrow(new RetriableTaskException("retriable AI failure: AI_HTTP_500",
                        new RuntimeException("ai 500")));

        consumer.onMessage(amqpMessage(taskMessage(42L), "QUESTION_GENERATE:42",
                QuestionGenerationMessageConsumer.MAX_RETRY_COUNT, Map.of()), channel, TAG);

        assertThat(meterRegistry.counter("aimindmap.task.dead.letter",
                "task_type", "QUESTION_GENERATE", "reason", "retries_exhausted").count()).isEqualTo(1.0);
        assertThat(meterRegistry.counter("aimindmap.task.consumed",
                "task_type", "QUESTION_GENERATE", "outcome", "dead_letter").count()).isEqualTo(1.0);
    }

    // ---------- helpers ----------

    private static TaskMessage taskMessage(long jobId) {
        return new TaskMessage(TaskType.QUESTION_GENERATE, jobId, String.valueOf(jobId), "GENERATE",
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
