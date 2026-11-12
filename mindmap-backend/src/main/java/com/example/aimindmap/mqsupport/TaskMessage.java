package com.example.aimindmap.mqsupport;

import java.time.Instant;

/**
 * 后台任务 MQ 消息（方案《阶段四·A》定义的唯一契约，字段不得自行增删改）。
 *
 * @param taskType   任务类型，wire 名为枚举原名
 * @param taskId     业务任务表主键；与 taskType 共同构成幂等键（messageId = taskType:taskId）
 * @param entityId   业务实体 ID（如文档 ID）；无实体的任务可为 null
 * @param op         操作类型（如 UPSERT/DELETE/REBUILD，具体取值由各业务链路在迁移时约定）
 * @param occurredAt 事件发生时间（UTC 时间线；全仓实体/DTO 的时间字段统一使用 Instant）
 */
public record TaskMessage(
        TaskType taskType,
        Long taskId,
        String entityId,
        String op,
        Instant occurredAt) {
}
