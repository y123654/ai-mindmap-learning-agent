package com.example.aimindmap.mqsupport.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.aimindmap.mqsupport.PublishOutcome;
import com.example.aimindmap.mqsupport.TaskMessage;
import com.example.aimindmap.mqsupport.TaskType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** OutboxRelayService 纯 Mockito 单测：不连 PG/MQ（真实并发归 4-4 Testcontainers）。 */
class OutboxRelayServiceTest {

    private OutboxEventRepository repository;
    private com.example.aimindmap.mqsupport.TaskMessagePublisher publisher;
    private ObjectMapper objectMapper;
    private MqProperties properties;
    private OutboxRelayService service;

    @BeforeEach
    void setUp() {
        repository = mock(OutboxEventRepository.class);
        publisher = mock(com.example.aimindmap.mqsupport.TaskMessagePublisher.class);
        objectMapper = new ObjectMapper()
                .findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        properties = new MqProperties();
        properties.setOutboxMaxAttempts(5);
        service = new OutboxRelayService(repository, publisher, objectMapper, properties);
    }

    private TaskMessage message(long taskId) {
        return new TaskMessage(TaskType.VECTOR_UPSERT, taskId, "doc-" + taskId,
                "UPSERT", Instant.parse("2026-10-16T08:30:00Z"));
    }

    private OutboxEventEntity eventFor(TaskMessage message, int attempts) {
        try {
            OutboxEventEntity entity = new OutboxEventEntity(
                    message.taskType().wireName(), message.taskId(),
                    objectMapper.writeValueAsString(message));
            entity.setAttempts(attempts);
            return entity;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private OutboxEventEntity captureSaved() {
        ArgumentCaptor<OutboxEventEntity> captor = ArgumentCaptor.forClass(OutboxEventEntity.class);
        verify(repository).save(captor.capture());
        return captor.getValue();
    }

    @Test
    void acknowledgedMarksSentWithSentAt() {
        TaskMessage message = message(1L);
        OutboxEventEntity entity = eventFor(message, 0);
        when(repository.claimPending(anyInt())).thenReturn(List.of(entity));
        when(publisher.publish(message)).thenReturn(
                new PublishOutcome.Acknowledged("VECTOR_UPSERT:1"));

        int count = service.relayPendingBatch();

        assertThat(count).isOne();
        OutboxEventEntity saved = captureSaved();
        assertThat(saved.getStatus()).isEqualTo(OutboxEventStatus.SENT);
        assertThat(saved.getSentAt()).isNotNull();
        assertThat(saved.getAttempts()).isZero();
        assertThat(saved.getLastError()).isNull();
    }

    @Test
    void rejectedBelowLimitStaysPendingAndCountsAttempt() {
        TaskMessage message = message(2L);
        OutboxEventEntity entity = eventFor(message, 1);
        when(repository.claimPending(anyInt())).thenReturn(List.of(entity));
        when(publisher.publish(message)).thenReturn(
                new PublishOutcome.Rejected("VECTOR_UPSERT:2", "broker nack"));

        service.relayPendingBatch();

        OutboxEventEntity saved = captureSaved();
        assertThat(saved.getStatus()).isEqualTo(OutboxEventStatus.PENDING);
        assertThat(saved.getAttempts()).isEqualTo(2);
        assertThat(saved.getLastError()).isEqualTo("broker nack");
        assertThat(saved.getSentAt()).isNull();
    }

    @Test
    void fifthRejectionMarksDead() {
        TaskMessage message = message(3L);
        OutboxEventEntity entity = eventFor(message, 4);
        when(repository.claimPending(anyInt())).thenReturn(List.of(entity));
        when(publisher.publish(message)).thenReturn(
                new PublishOutcome.Rejected("VECTOR_UPSERT:3", "confirm timeout after 5000ms"));

        service.relayPendingBatch();

        OutboxEventEntity saved = captureSaved();
        assertThat(saved.getStatus()).isEqualTo(OutboxEventStatus.DEAD);
        assertThat(saved.getAttempts()).isEqualTo(5);
        assertThat(saved.getLastError()).contains("confirm timeout");
    }

    @Test
    void skippedKeepsRowCompletelyUntouched() {
        TaskMessage message = message(4L);
        OutboxEventEntity entity = eventFor(message, 0);
        when(repository.claimPending(anyInt())).thenReturn(List.of(entity));
        when(publisher.publish(message)).thenReturn(
                new PublishOutcome.Skipped("VECTOR_UPSERT:4", "mq disabled"));

        service.relayPendingBatch();

        assertThat(entity.getStatus()).isEqualTo(OutboxEventStatus.PENDING);
        assertThat(entity.getAttempts()).isZero();
        assertThat(entity.getLastError()).isNull();
        assertThat(entity.getSentAt()).isNull();
        verify(repository, never()).save(any());
    }

    @Test
    void unexpectedPublisherExceptionDoesNotAbortBatch() {
        TaskMessage first = message(5L);
        TaskMessage second = message(6L);
        OutboxEventEntity firstEntity = eventFor(first, 0);
        OutboxEventEntity secondEntity = eventFor(second, 0);
        when(repository.claimPending(anyInt())).thenReturn(List.of(firstEntity, secondEntity));
        when(publisher.publish(first)).thenThrow(new RuntimeException("connection lost"));
        when(publisher.publish(second)).thenReturn(
                new PublishOutcome.Acknowledged("VECTOR_UPSERT:6"));

        int count = service.relayPendingBatch();

        assertThat(count).isEqualTo(2);
        assertThat(firstEntity.getStatus()).isEqualTo(OutboxEventStatus.PENDING);
        assertThat(firstEntity.getAttempts()).isEqualTo(1);
        assertThat(firstEntity.getLastError()).contains("connection lost");
        assertThat(secondEntity.getStatus()).isEqualTo(OutboxEventStatus.SENT);
        assertThat(secondEntity.getSentAt()).isNotNull();
        verify(repository).save(firstEntity);
        verify(repository).save(secondEntity);
    }

    @Test
    void corruptPayloadIncrementsAttemptsWithoutPublishing() {
        OutboxEventEntity entity = new OutboxEventEntity("VECTOR_UPSERT", 7L, "{not-json");
        when(repository.claimPending(anyInt())).thenReturn(List.of(entity));

        service.relayPendingBatch();

        assertThat(entity.getStatus()).isEqualTo(OutboxEventStatus.PENDING);
        assertThat(entity.getAttempts()).isEqualTo(1);
        assertThat(entity.getLastError()).contains("反序列化失败");
        verify(publisher, never()).publish(any());
    }

    @Test
    void emptyBatchReturnsZeroAndNeverPublishes() {
        when(repository.claimPending(anyInt())).thenReturn(List.of());

        int count = service.relayPendingBatch();

        assertThat(count).isZero();
        verify(publisher, never()).publish(any());
        verify(repository, never()).save(any());
    }

    @Test
    void cleanupUsesSevenDayCutoffAndReturnsDeletedCount() {
        when(repository.deleteSentBefore(any(Instant.class))).thenReturn(3);

        Instant before = Instant.now().minus(Duration.ofDays(7));
        int deleted = service.cleanupSent();
        Instant after = Instant.now().minus(Duration.ofDays(7));

        assertThat(deleted).isEqualTo(3);
        ArgumentCaptor<Instant> cutoffCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(repository).deleteSentBefore(cutoffCaptor.capture());
        assertThat(cutoffCaptor.getValue()).isBetween(before, after);
    }
}
