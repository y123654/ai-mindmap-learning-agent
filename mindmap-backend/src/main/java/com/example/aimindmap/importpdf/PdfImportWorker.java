package com.example.aimindmap.importpdf;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * PDF 导入后台 worker（方案《阶段五》/ §3.5 开关与降级）。
 *
 * <p>app.mq.enabled=true（缺省）：不直接解析，本调度降级为每
 * {@code app.mq.requeue-scan-delay-ms}（默认 60s）一次的对账扫描——
 * 捞 PENDING 超时漏发与 EXTRACTING 超时卡死（消费者崩溃）的 PDF 任务，复位并补写 outbox，
 * 由 OutboxRelay 重新投递；执行权在 {@link PdfImportMessageConsumer}，不与 MQ 消费者重复处理。
 *
 * <p>app.mq.enabled=false：按 id 升序（与投递顺序一致）单线程认领 PENDING 并直接调用
 * {@link PdfImportService#process(Long)}，保持单并发 FIFO；MQ 关闭时 PDF 仍会被处理。
 */
@Component
public class PdfImportWorker {

    private static final Logger log = LoggerFactory.getLogger(PdfImportWorker.class);

    private final PdfImportService pdfImportService;
    private final boolean mqEnabled;

    public PdfImportWorker(PdfImportService pdfImportService,
                           @Value("${app.mq.enabled:true}") boolean mqEnabled) {
        this.pdfImportService = pdfImportService;
        this.mqEnabled = mqEnabled;
    }

    @Scheduled(fixedDelayString = "${app.mq.requeue-scan-delay-ms:60000}")
    public void tick() {
        if (mqEnabled) {
            // 只对账补投，不直接解析，避免与 MQ 消费者重复执行
            pdfImportService.reconcileStaleJobs();
            return;
        }
        List<Long> jobIds = pdfImportService.claimNextForPolling(PdfImportService.POLL_BATCH_LIMIT);
        if (jobIds.isEmpty()) {
            return;
        }
        log.info("PDF 轮询兜底 worker 认领任务 count={} jobIds={}", jobIds.size(), jobIds);
        for (Long jobId : jobIds) {
            processOne(jobId);
        }
    }

    private void processOne(Long jobId) {
        try {
            PdfImportService.ProcessOutcome outcome = pdfImportService.process(jobId);
            log.info("PDF 轮询兜底处理完成 jobId={} outcome={}", jobId, outcome);
        } catch (DataAccessException e) {
            // 业务失败已在 process 内落 FAILED；能逃到这里的只有基础设施异常。
            // 复位为 PENDING，下一轮轮询重试，避免任务卡在 EXTRACTING。
            log.error("PDF 轮询处理遇数据库异常，复位为 PENDING 待下轮重试 jobId={}", jobId, e);
            safeRequeue(jobId);
        } catch (RuntimeException e) {
            log.error("PDF 轮询处理发生未预期异常，复位为 PENDING 待下轮重试 jobId={}", jobId, e);
            safeRequeue(jobId);
        }
    }

    private void safeRequeue(Long jobId) {
        try {
            pdfImportService.requeueForMessageRetry(jobId);
        } catch (DataAccessException e) {
            log.error("PDF 轮询任务复位 PENDING 又失败，等待对账兜底 jobId={}", jobId, e);
        }
    }
}
