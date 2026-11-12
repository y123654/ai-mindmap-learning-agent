package com.example.aimindmap.mqsupport;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class NoopTaskMessagePublisherTest {

    @Test
    void disabledPublisherSkipsWithoutThrowing() {
        NoopTaskMessagePublisher publisher = new NoopTaskMessagePublisher();
        TaskMessage message = new TaskMessage(
                TaskType.MINDMAP_SYNC, 99L, "mindmap-7", "SYNC", Instant.now());

        PublishOutcome outcome = publisher.publish(message);

        assertThat(outcome).isInstanceOf(PublishOutcome.Skipped.class);
        assertThat(outcome.accepted()).isFalse();
        assertThat(outcome.messageId()).isEqualTo("MINDMAP_SYNC:99");
    }
}
