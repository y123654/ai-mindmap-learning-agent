package com.example.aimindmap.question;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 阶段六-2：出题任务对账器（方案《阶段六》，仅 app.mq.enabled=true/缺省时装配）。
 *
 * <p>每 {@code app.mq.requeue-scan-delay-ms}（默认 60s）调用一次
 * {@link QuestionService#reconcileStaleJobs()}：
 * PENDING 超 5 分钟（outbox DEAD/漏发/消息丢失）补写 outbox；RUNNING 超 5 分钟
 * （消费者崩溃、或三档重试耗尽进 DLQ 后基础设施恢复）复位 PENDING 并补写 outbox，
 * 由 OutboxRelay 重新投递。执行权在 {@link QuestionGenerationMessageConsumer}，
 * 本类只对账补投，不直接调用 processGenerationJob，避免与 MQ 消费者重复执行。
 *
 * <p>app.mq.enabled=false 时本 Bean 不装配：降级链路保持阶段 4 的 afterCommit 事件 +
 * {@link QuestionGenerationWorker} @Async，行为零变化。年龄锚点只有 created_at
 * （本表无 updated_at，取舍与 6-1 一致，不加 V13 迁移）。
 */
@Component
@ConditionalOnProperty(name = "app.mq.enabled", havingValue = "true", matchIfMissing = true)
public class QuestionGenerationReconciler {

    private final QuestionService questionService;

    public QuestionGenerationReconciler(QuestionService questionService) {
        this.questionService = questionService;
    }

    @Scheduled(fixedDelayString = "${app.mq.requeue-scan-delay-ms:60000}")
    public void tick() {
        // 与 SyncJobReconciler 同构：异常交给 @Scheduled 框架记录，下一轮继续触发
        questionService.reconcileStaleJobs();
    }
}
