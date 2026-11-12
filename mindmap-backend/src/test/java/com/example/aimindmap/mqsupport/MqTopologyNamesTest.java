package com.example.aimindmap.mqsupport;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** MqTopology 命名规则与三档 TTL（不连 MQ）。 */
class MqTopologyNamesTest {

    @ParameterizedTest
    @EnumSource(TaskType.class)
    void taskNamesKeepEnumWireName(TaskType taskType) {
        assertThat(taskType.wireName()).isEqualTo(taskType.name());
        assertThat(MqTopology.queueName(taskType))
                .isEqualTo("aimindmap.queue." + taskType.name());
        assertThat(MqTopology.routingKey(taskType))
                .isEqualTo("aimindmap.task." + taskType.name());
    }

    @Test
    void sixTaskTypesMatchContractExactly() {
        assertThat(TaskType.values()).containsExactly(
                TaskType.PDF_IMPORT,
                TaskType.VECTOR_UPSERT,
                TaskType.VECTOR_DELETE,
                TaskType.MINDMAP_SYNC,
                TaskType.QUESTION_GENERATE,
                TaskType.SESSION_SUMMARY);
    }

    @ParameterizedTest
    @EnumSource(TaskType.class)
    void retryQueueNamesCarryTaskAndTier(TaskType taskType) {
        assertThat(MqTopology.retryQueueName(taskType, MqTopology.RetryTier.FIRST))
                .isEqualTo("aimindmap.retry." + taskType.name() + ".5s");
        assertThat(MqTopology.retryQueueName(taskType, MqTopology.RetryTier.SECOND))
                .isEqualTo("aimindmap.retry." + taskType.name() + ".30s");
        assertThat(MqTopology.retryQueueName(taskType, MqTopology.RetryTier.THIRD))
                .isEqualTo("aimindmap.retry." + taskType.name() + ".2m");
    }

    @Test
    void retryTierTtlsAreFiveSecondsThirtySecondsTwoMinutes() {
        assertThat(MqTopology.RetryTier.FIRST.ttlMillis()).isEqualTo(5000L);
        assertThat(MqTopology.RetryTier.SECOND.ttlMillis()).isEqualTo(30000L);
        assertThat(MqTopology.RetryTier.THIRD.ttlMillis()).isEqualTo(120000L);
    }

    @Test
    void topologyConstantsAreFixed() {
        assertThat(MqTopology.TASK_EXCHANGE).isEqualTo("aimindmap.tasks");
        assertThat(MqTopology.DEAD_LETTER_EXCHANGE).isEqualTo("aimindmap.dlx");
        assertThat(MqTopology.DEAD_LETTER_QUEUE).isEqualTo("aimindmap.dlq");
    }
}
