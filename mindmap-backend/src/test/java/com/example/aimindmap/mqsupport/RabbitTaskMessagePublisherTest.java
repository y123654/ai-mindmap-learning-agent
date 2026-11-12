package com.example.aimindmap.mqsupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

/** RabbitTaskMessagePublisher 单测：mock RabbitTemplate，手动完成 confirm future，不连 MQ。 */
class RabbitTaskMessagePublisherTest {

    private RabbitTemplate rabbitTemplate;
    private ObjectMapper objectMapper;

    private TaskMessage sampleMessage() {
        return new TaskMessage(TaskType.VECTOR_UPSERT, 42L, "doc-1001", "UPSERT",
                Instant.parse("2026-10-16T08:30:00Z"));
    }

    @BeforeEach
    void setUp() {
        rabbitTemplate = mock(RabbitTemplate.class);
        objectMapper = new ObjectMapper()
                .findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @Test
    void ackPathSendsPersistentJsonWithIdempotentMessageIdAndCorrectRouting() throws Exception {
        doAnswer(invocation -> {
            CorrelationData correlationData = invocation.getArgument(3);
            correlationData.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbitTemplate).send(anyString(), anyString(), any(Message.class),
                any(CorrelationData.class));

        PublishOutcome outcome =
                new RabbitTaskMessagePublisher(rabbitTemplate, objectMapper).publish(sampleMessage());

        assertThat(outcome).isInstanceOf(PublishOutcome.Acknowledged.class);
        assertThat(outcome.accepted()).isTrue();
        assertThat(outcome.messageId()).isEqualTo("VECTOR_UPSERT:42");

        ArgumentCaptor<String> exchangeCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> routingKeyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
        ArgumentCaptor<CorrelationData> correlationCaptor = ArgumentCaptor.forClass(CorrelationData.class);
        verify(rabbitTemplate).send(exchangeCaptor.capture(), routingKeyCaptor.capture(),
                messageCaptor.capture(), correlationCaptor.capture());

        assertThat(exchangeCaptor.getValue()).isEqualTo("aimindmap.tasks");
        assertThat(routingKeyCaptor.getValue()).isEqualTo("aimindmap.task.VECTOR_UPSERT");

        Message sent = messageCaptor.getValue();
        assertThat(sent.getMessageProperties().getMessageId()).isEqualTo("VECTOR_UPSERT:42");
        assertThat(sent.getMessageProperties().getDeliveryMode())
                .isEqualTo(MessageDeliveryMode.PERSISTENT);
        assertThat(sent.getMessageProperties().getContentType()).isEqualTo("application/json");
        assertThat(sent.getMessageProperties().getContentEncoding()).isEqualTo("UTF-8");

        TaskMessage restored = objectMapper.readValue(sent.getBody(), TaskMessage.class);
        assertThat(restored).isEqualTo(sampleMessage());

        assertThat(correlationCaptor.getValue().getId()).isEqualTo("VECTOR_UPSERT:42");
    }

    @Test
    void brokerNackReturnsRejectedOutcome() {
        doAnswer(invocation -> {
            CorrelationData correlationData = invocation.getArgument(3);
            correlationData.getFuture().complete(new CorrelationData.Confirm(false, "queue unavailable"));
            return null;
        }).when(rabbitTemplate).send(anyString(), anyString(), any(Message.class),
                any(CorrelationData.class));

        PublishOutcome outcome =
                new RabbitTaskMessagePublisher(rabbitTemplate, objectMapper).publish(sampleMessage());

        assertThat(outcome).isInstanceOf(PublishOutcome.Rejected.class);
        assertThat(outcome.accepted()).isFalse();
        assertThat(outcome.messageId()).isEqualTo("VECTOR_UPSERT:42");
        assertThat(((PublishOutcome.Rejected) outcome).reason()).contains("queue unavailable");
    }

    @Test
    void confirmTimeoutReturnsRejectedWithoutThrowing() {
        // 不完成 CorrelationData future；用 50ms 超时，避免单测等 5 秒
        RabbitTaskMessagePublisher publisher =
                new RabbitTaskMessagePublisher(rabbitTemplate, objectMapper, Duration.ofMillis(50));

        PublishOutcome outcome = publisher.publish(sampleMessage());

        assertThat(outcome).isInstanceOf(PublishOutcome.Rejected.class);
        assertThat(((PublishOutcome.Rejected) outcome).reason()).contains("confirm timeout");
    }

    @Test
    void sendFailureReturnsRejectedWithoutThrowing() {
        doThrow(new AmqpException("connection refused"))
                .when(rabbitTemplate).send(anyString(), anyString(), any(Message.class),
                        any(CorrelationData.class));

        PublishOutcome outcome =
                new RabbitTaskMessagePublisher(rabbitTemplate, objectMapper).publish(sampleMessage());

        assertThat(outcome).isInstanceOf(PublishOutcome.Rejected.class);
        assertThat(((PublishOutcome.Rejected) outcome).reason()).startsWith("send failed");
    }

    @Test
    void serializationFailureDoesNotReachBroker() throws Exception {
        ObjectMapper failingMapper = mock(ObjectMapper.class);
        doThrow(new JsonProcessingException("boom") {
        }).when(failingMapper).writeValueAsBytes(any());

        PublishOutcome outcome =
                new RabbitTaskMessagePublisher(rabbitTemplate, failingMapper).publish(sampleMessage());

        assertThat(outcome).isInstanceOf(PublishOutcome.Rejected.class);
        assertThat(((PublishOutcome.Rejected) outcome).reason()).startsWith("serialize failed");
        verify(rabbitTemplate, never())
                .send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
    }
}
