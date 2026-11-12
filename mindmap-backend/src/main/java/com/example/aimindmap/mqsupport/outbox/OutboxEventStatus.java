package com.example.aimindmap.mqsupport.outbox;

/** outbox_events.status：待投递 / broker 已确认 / 失败达上限。 */
public enum OutboxEventStatus {
    PENDING,
    SENT,
    DEAD
}
