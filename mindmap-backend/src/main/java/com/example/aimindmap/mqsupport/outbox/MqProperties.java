package com.example.aimindmap.mqsupport.outbox;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * app.mq.* 配置（附录 A）。enabled 的条件装配直接用 @ConditionalOnProperty 读取；
 * 这里绑定 4-2 引入的两个 outbox 键与 4-3 引入的对账扫描间隔，均可被环境变量覆盖。
 */
@ConfigurationProperties(prefix = "app.mq")
public class MqProperties {

    private boolean enabled = true;
    /** relay 认领批次间隔（毫秒），附录 A 默认 1000。 */
    private long outboxFixedDelayMs = 1000L;
    /** 单行投递失败多少次后置 DEAD，附录 A 默认 5。 */
    private int outboxMaxAttempts = 5;
    /** MQ 开启时向量 worker 降级为对账扫描的间隔（毫秒），附录 A 默认 60000。 */
    private long requeueScanDelayMs = 60000L;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public long getOutboxFixedDelayMs() {
        return outboxFixedDelayMs;
    }

    public void setOutboxFixedDelayMs(long outboxFixedDelayMs) {
        this.outboxFixedDelayMs = outboxFixedDelayMs;
    }

    public int getOutboxMaxAttempts() {
        return outboxMaxAttempts;
    }

    public void setOutboxMaxAttempts(int outboxMaxAttempts) {
        this.outboxMaxAttempts = outboxMaxAttempts;
    }

    public long getRequeueScanDelayMs() {
        return requeueScanDelayMs;
    }

    public void setRequeueScanDelayMs(long requeueScanDelayMs) {
        this.requeueScanDelayMs = requeueScanDelayMs;
    }
}
