package com.example.aimindmap.mqsupport.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.aimindmap.metrics.MqTaskMetrics;
import com.example.aimindmap.mqsupport.TaskMessage;
import com.example.aimindmap.mqsupport.TaskType;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Method;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** OutboxEventService：原事务写发件箱（纯 Mockito，不连 PG）。 */
class OutboxEventServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper()
            .findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    /** 阶段八-1：入队计数断言用真实 SimpleMeterRegistry（不 mock 指标组件）。 */
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final MqTaskMetrics mqTaskMetrics = new MqTaskMetrics(meterRegistry);

    private TaskMessage sampleMessage() {
        return new TaskMessage(TaskType.VECTOR_DELETE, 88L, "doc-88", "DELETE",
                Instant.parse("2026-10-16T09:00:00Z"));
    }

    @Test
    void recordPendingPersistsContractFieldsWithPayloadRoundTrip() throws Exception {
        OutboxEventRepository repository = mock(OutboxEventRepository.class);
        ArgumentCaptor<OutboxEventEntity> captor = ArgumentCaptor.forClass(OutboxEventEntity.class);
        when(repository.save(captor.capture())).thenAnswer(invocation -> invocation.getArgument(0));
        OutboxEventService service = new OutboxEventService(repository, objectMapper, mqTaskMetrics);

        TaskMessage message = sampleMessage();
        OutboxEventEntity saved = service.recordPending(message);

        assertThat(saved.getTaskType()).isEqualTo("VECTOR_DELETE");
        assertThat(saved.getTaskId()).isEqualTo(88L);
        assertThat(saved.getStatus()).isEqualTo(OutboxEventStatus.PENDING);
        assertThat(saved.getAttempts()).isZero();
        assertThat(saved.getCreatedAt()).isNotNull();
        TaskMessage restored = objectMapper.readValue(saved.getPayload(), TaskMessage.class);
        assertThat(restored).isEqualTo(message);
    }

    @Test
    void serializationFailurePropagatesAsIllegalStateAndNeverSaves() throws Exception {
        OutboxEventRepository repository = mock(OutboxEventRepository.class);
        ObjectMapper failingMapper = mock(ObjectMapper.class);
        when(failingMapper.writeValueAsBytes(any())).thenCallRealMethod();
        when(failingMapper.writeValueAsString(any(TaskMessage.class)))
                .thenThrow(new JsonProcessingException("boom") {
                });
        OutboxEventService service = new OutboxEventService(repository, failingMapper, mqTaskMetrics);

        assertThatThrownBy(() -> service.recordPending(sampleMessage()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("outbox payload serialize failed");

        verify(repository, never()).save(any());
    }

    @Test
    void recordPendingJoinsCallerTransactionByDefault() throws Exception {
        Method method = OutboxEventService.class.getMethod("recordPending", TaskMessage.class);
        Transactional transactional = method.getAnnotation(Transactional.class);
        assertThat(transactional).isNotNull();
        assertThat(transactional.propagation()).isEqualTo(Propagation.REQUIRED);
    }

    // ---------- 阶段八-1：入队计数埋点（325 行「各任务入队」） ----------

    @Test
    void recordPendingCountsEnqueuedTaskPerTaskType() {
        OutboxEventRepository repository = mock(OutboxEventRepository.class);
        when(repository.save(any(OutboxEventEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        OutboxEventService service = new OutboxEventService(repository, objectMapper, mqTaskMetrics);

        service.recordPending(sampleMessage());
        service.recordPending(sampleMessage());
        service.recordPending(new TaskMessage(TaskType.PDF_IMPORT, 99L, null, "IMPORT",
                Instant.parse("2026-10-22T09:00:00Z")));

        assertThat(meterRegistry.counter("aimindmap.task.enqueued",
                "task_type", "VECTOR_DELETE").count()).isEqualTo(2.0);
        assertThat(meterRegistry.counter("aimindmap.task.enqueued",
                "task_type", "PDF_IMPORT").count()).isEqualTo(1.0);
    }

    @Test
    void serializationFailureNeverCountsEnqueuedTask() throws Exception {
        OutboxEventRepository repository = mock(OutboxEventRepository.class);
        ObjectMapper failingMapper = mock(ObjectMapper.class);
        when(failingMapper.writeValueAsString(any(TaskMessage.class)))
                .thenThrow(new JsonProcessingException("boom") {
                });
        OutboxEventService service = new OutboxEventService(repository, failingMapper, mqTaskMetrics);

        assertThatThrownBy(() -> service.recordPending(sampleMessage()))
                .isInstanceOf(IllegalStateException.class);

        assertThat(meterRegistry.find("aimindmap.task.enqueued").counter()).isNull();
    }
}
