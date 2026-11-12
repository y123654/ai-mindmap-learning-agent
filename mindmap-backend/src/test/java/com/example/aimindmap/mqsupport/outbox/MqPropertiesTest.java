package com.example.aimindmap.mqsupport.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 4-3：app.mq.* 配置默认值（附录 A）。 */
class MqPropertiesTest {

    @Test
    void defaultsMatchAppendixA() {
        MqProperties properties = new MqProperties();
        assertThat(properties.isEnabled()).isTrue();
        assertThat(properties.getOutboxFixedDelayMs()).isEqualTo(1000L);
        assertThat(properties.getOutboxMaxAttempts()).isEqualTo(5);
        assertThat(properties.getRequeueScanDelayMs()).isEqualTo(60000L);
    }
}