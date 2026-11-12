package com.example.aimindmap.vectorindex;

/**
 * 向量任务的永久失败（方案《阶段四·C》消费者异常分级）：
 * 资源不存在、资料已删除、片段/节点消失、资源 ID 格式非法、不支持的资料类型等，
 * 重试不可能成功，MQ 消费者首次即 nack(requeue=false) 进 DLQ。
 *
 * <p>继承 {@link VectorIndexExternalException} 而非并列新类型：轮询兜底链路
 * （app.mq.enabled=false 时的 VectorIndexWorker）现有 catch 语义保持不变，
 * 仅 MQ 消费者按子类识别"不可重试"。业务逻辑零改动，只替换 throw 站点。
 */
public class VectorIndexPermanentException extends VectorIndexExternalException {

    public VectorIndexPermanentException(String message) {
        super(message, null);
    }
}