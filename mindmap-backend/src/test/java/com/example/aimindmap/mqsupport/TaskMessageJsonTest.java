package com.example.aimindmap.mqsupport;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** TaskMessage Jackson 往返与 wire 名（不连 MQ）。 */
class TaskMessageJsonTest {

    private final ObjectMapper objectMapper = new ObjectMapper()
            .findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    @Test
    void jsonRoundTripPreservesAllFields() throws Exception {
        Instant occurredAt = Instant.parse("2026-10-16T08:30:00Z");
        TaskMessage original = new TaskMessage(
                TaskType.VECTOR_UPSERT, 42L, "doc-1001", "UPSERT", occurredAt);

        String json = objectMapper.writeValueAsString(original);
        TaskMessage restored = objectMapper.readValue(json, TaskMessage.class);

        assertThat(restored).isEqualTo(original);
        assertThat(restored.taskType()).isEqualTo(TaskType.VECTOR_UPSERT);
        assertThat(restored.taskId()).isEqualTo(42L);
        assertThat(restored.entityId()).isEqualTo("doc-1001");
        assertThat(restored.op()).isEqualTo("UPSERT");
        assertThat(restored.occurredAt()).isEqualTo(occurredAt);
    }

    @Test
    void taskTypeUsesEnumWireNameAndInstantIsIsoUtc() throws Exception {
        TaskMessage message = new TaskMessage(
                TaskType.SESSION_SUMMARY, 7L, null, "SUMMARY",
                Instant.parse("2026-10-16T01:02:03Z"));

        String json = objectMapper.writeValueAsString(message);

        assertThat(json).contains("\"taskType\":\"SESSION_SUMMARY\"");
        assertThat(json).contains("\"occurredAt\":\"2026-10-16T01:02:03Z\"");
    }
}
