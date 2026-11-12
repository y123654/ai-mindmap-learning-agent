package com.example.aimindmap.vectorindex;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 向量索引后台 worker（4-3 对账化，方案《阶段四·C》末条 / §3.5）。
 *
 * <p>app.mq.enabled=true（缺省）：不再直接处理任务，本调度降级为每
 * {@code app.mq.requeue-scan-delay-ms}（默认 60s）一次的对账扫描——
 * 捞 PENDING 超时漏发与 PROCESSING 超时卡死（消费者崩溃）的任务，复位并补写 outbox，
 * 由 OutboxRelay 重新投递；执行权在 {@link VectorIndexMessageConsumer}。
 *
 * <p>app.mq.enabled=false：保留原有"认领 PENDING/可重试 FAILED → 直接处理 → mark*"
 * 的轮询兜底语义，MQ 宕机/关闭时任务仍能运行。
 */
@Component
@EnableScheduling
public class VectorIndexWorker {

    private static final Logger log = LoggerFactory.getLogger(VectorIndexWorker.class);

    private final VectorIndexTaskService taskService;
    private final VectorIndexTaskProcessor processor;
    private final boolean mqEnabled;

    public VectorIndexWorker(VectorIndexTaskService taskService,
                             VectorIndexTaskProcessor processor,
                             @Value("${app.mq.enabled:true}") boolean mqEnabled) {
        this.taskService = taskService;
        this.processor = processor;
        this.mqEnabled = mqEnabled;
    }

    @Scheduled(fixedDelayString = "${app.mq.requeue-scan-delay-ms:60000}")
    public void tick() {
        if (mqEnabled) {
            // 只对账补投，不直接处理，避免与 MQ 消费者重复执行（H1-5）
            taskService.reconcileStaleTasks();
            return;
        }
        List<VectorIndexTaskService.TaskLoad> tasks = taskService.claimBatch();
        if (tasks.isEmpty()) {
            return;
        }
        log.info("向量索引轮询 worker 认领任务 count={}", tasks.size());
        for (VectorIndexTaskService.TaskLoad task : tasks) {
            processOne(task);
        }
    }

    private void processOne(VectorIndexTaskService.TaskLoad task) {
        try {
            VectorIndexTaskProcessor.ProcessResult result = processor.process(task);
            switch (result) {
                case VectorIndexTaskProcessor.ProcessResult.UpsertSucceeded succeeded ->
                        taskService.markUpsertSucceeded(task, succeeded.pointId(), succeeded.contentHash());
                case VectorIndexTaskProcessor.ProcessResult.ProcessDeleteSucceeded ignored ->
                        taskService.markDeleteSucceeded(task);
                case VectorIndexTaskProcessor.ProcessResult.Skipped skipped ->
                        taskService.markSkipped(task.taskId());
            }
        } catch (VectorIndexExternalException e) {
            log.warn("向量任务外部调用失败 taskId={} userId={} resourceType={} resourceId={} chunkIndex={} error={}",
                    task.taskId(), task.userId(), task.resourceType().wireName(),
                    task.resourceId(), task.chunkIndex(), e.getMessage(), e);
            taskService.markFailed(task, e.getMessage());
        } catch (Exception e) {
            log.error("向量任务发生未预期异常 taskId={} userId={} resourceType={} resourceId={} chunkIndex={}",
                    task.taskId(), task.userId(), task.resourceType().wireName(),
                    task.resourceId(), task.chunkIndex(), e);
            taskService.markFailed(task, e.toString());
        }
    }
}