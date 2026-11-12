package com.example.aimindmap.importpdf;

import com.example.aimindmap.auth.ExternalIdCodec;
import com.example.aimindmap.common.error.ApiException;
import com.example.aimindmap.document.DocumentEntity;
import com.example.aimindmap.document.DocumentService;
import com.example.aimindmap.metrics.BusinessMetrics;
import com.example.aimindmap.mqsupport.TaskMessage;
import com.example.aimindmap.mqsupport.TaskType;
import com.example.aimindmap.mqsupport.outbox.IdempotentConsumptionEntity;
import com.example.aimindmap.mqsupport.outbox.IdempotentConsumptionRepository;
import com.example.aimindmap.mqsupport.outbox.OutboxEventRepository;
import com.example.aimindmap.mqsupport.outbox.OutboxEventService;
import com.example.aimindmap.mqsupport.outbox.OutboxEventStatus;
import jakarta.transaction.Transactional;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * 阶段五-A：PDF 上传快速受理 + 后台异步解析编排（方案《阶段五》）。
 *
 * <p>上传只做校验、UUID 落盘、建 PENDING 任务，并在同一事务写 outbox（PDF_IMPORT/IMPORT），
 * 立即返回 jobId；解析由 {@code PdfImportMessageConsumer}（MQ）或 {@code PdfImportWorker}
 * （轮询兜底）调用 {@link #process(Long)} 完成，解析逻辑与同步时代码完全一致，
 * DRAFT_READY/SCANNED/FAILED 与错误码文案均不改变。
 *
 * <p>阶段八-1：{@link #uploadPdf} 记录受理耗时 {@code aimindmap.pdf.upload}
 * （方案 325 行「PDF 上传 RT」）。只加记录，不改校验顺序、事务边界与返回值；
 * 被 400 拒绝的上传不记（由自动装配的 http_server_requests 覆盖），因此不新增 catch。
 */
@Service
public class PdfImportService {

    private static final Logger log = LoggerFactory.getLogger(PdfImportService.class);
    private static final byte[] PDF_MAGIC = {0x25, 0x50, 0x44, 0x46, 0x2D}; // %PDF-

    /**
     * 对账超时门槛：PENDING/EXTRACTING 超过 5 分钟才视为漏发或消费者崩溃。
     * 正常 outbox 投递与 PDF 解析（20MB/100 页上限）均为秒级，不会误伤。
     */
    static final Duration RECONCILE_STALE_AGE = Duration.ofMinutes(5);
    /** 单轮对账最多补投的行数，防止风暴。 */
    static final int RECONCILE_BATCH_LIMIT = 20;
    /** app.mq.enabled=false 轮询兜底单轮最多认领数；单线程顺序处理，保持单并发与 FIFO。 */
    static final int POLL_BATCH_LIMIT = 10;

    private final PdfImportJobRepository jobRepository;
    private final PdfProperties properties;
    private final PdfTextExtractor extractor;
    private final PdfDraftBuilder draftBuilder;
    private final DocumentService documentService;
    private final ExternalIdCodec idCodec;
    private final OutboxEventService outboxEventService;
    private final OutboxEventRepository outboxEventRepository;
    private final IdempotentConsumptionRepository idempotentConsumptionRepository;
    private final BusinessMetrics businessMetrics;

    public PdfImportService(PdfImportJobRepository jobRepository,
                            PdfProperties properties,
                            PdfTextExtractor extractor,
                            PdfDraftBuilder draftBuilder,
                            DocumentService documentService,
                            ExternalIdCodec idCodec,
                            OutboxEventService outboxEventService,
                            OutboxEventRepository outboxEventRepository,
                            IdempotentConsumptionRepository idempotentConsumptionRepository,
                            BusinessMetrics businessMetrics) {
        this.jobRepository = jobRepository;
        this.properties = properties;
        this.extractor = extractor;
        this.draftBuilder = draftBuilder;
        this.documentService = documentService;
        this.idCodec = idCodec;
        this.outboxEventService = outboxEventService;
        this.outboxEventRepository = outboxEventRepository;
        this.idempotentConsumptionRepository = idempotentConsumptionRepository;
        this.businessMetrics = businessMetrics;
    }

    /** 后台解析的业务终态；加密/损坏/扫描件都是业务终态，消费者落终态后 ack。 */
    public enum ProcessOutcome {
        DRAFT_READY,
        SCANNED,
        FAILED
    }

    // ---------- 阶段五-A：上传快速受理 ----------

    /**
     * 校验（空文件/大小/%PDF- 头，与同步时代码完全一致）→ UUID 落盘 → 建 PENDING 任务 →
     * 同一事务写 outbox（PDF_IMPORT，op=IMPORT，entityId=null），立即返回，不再同步解析。
     */
    @Transactional
    public PdfImportDtos.JobResponse uploadPdf(Long userId, MultipartFile file) {
        // 阶段八-1：上传受理 RT 起点（校验 + 落盘 + 建任务行 + 同事务写 outbox）
        long startedNanos = businessMetrics.startTimer();
        if (file == null || file.isEmpty()) {
            throw ApiException.badRequest("PDF_EMPTY_FILE", "请选择要上传的 PDF 文件");
        }
        String originalFileName = safeOriginalName(file.getOriginalFilename());
        long maxBytes = (long) properties.getMaxFileSizeMb() * 1024L * 1024L;
        if (file.getSize() > maxBytes) {
            throw ApiException.badRequest("PDF_FILE_TOO_LARGE",
                    "PDF 文件过大，当前版本最大支持 " + properties.getMaxFileSizeMb() + "MB");
        }
        if (!hasPdfMagicHeader(file)) {
            throw ApiException.badRequest("PDF_NOT_PDF", "文件不是有效的 PDF（缺少 %PDF- 文件头），请确认后重试");
        }

        Path storedPath = storeFile(file);

        PdfImportJobEntity job = new PdfImportJobEntity();
        job.setUserId(userId);
        job.setOriginalFileName(truncate(originalFileName, 255));
        job.setStoredFilePath(storedPath.toAbsolutePath().toString());
        job.setStatus(PdfImportStatus.PENDING);
        job = jobRepository.save(job);

        // 任务行与 outbox 同一事务原子落库（事务性发件箱）；
        // recordPending 传播级别 REQUIRED，序列化失败抛 IllegalStateException 让事务回滚。
        outboxEventService.recordPending(new TaskMessage(
                TaskType.PDF_IMPORT, job.getId(), null, "IMPORT", Instant.now()));

        log.info("PDF 上传已受理，等待后台解析 jobId={} userId={} sizeBytes={}",
                job.getId(), userId, file.getSize());
        businessMetrics.recordPdfUploadAccepted(startedNanos);
        return PdfImportDtos.JobResponse.from(job, idCodec);
    }

    // ---------- 阶段五-A：后台消费链路（MQ 消费者 / 轮询 worker 共用） ----------

    /**
     * 消费者条件认领（幂等三件套之一）：仅 PENDING 行能被认领并置为 EXTRACTING；
     * 0 行说明任务已终态或已被认领，调用方直接 ack。
     */
    @Transactional
    public boolean claimForMessage(Long jobId) {
        return jobRepository.claimForConsumer(jobId) > 0;
    }

    /** 任务行是否存在：条件认领 0 行时用于区分"重复投递"与"毒消息（任务行不存在）"。 */
    @Transactional
    public boolean jobExists(Long jobId) {
        return jobRepository.existsById(jobId);
    }

    /**
     * 可重试失败转延时重试前复位任务行：EXTRACTING→PENDING，
     * 重试消息到期回来后才能被 {@link #claimForMessage} 再次认领。
     */
    @Transactional
    public boolean requeueForMessageRetry(Long jobId) {
        int updated = jobRepository.requeueForRetry(jobId);
        if (updated == 0) {
            log.warn("PDF 任务重试复位未命中 EXTRACTING 行（可能存在并发） jobId={}", jobId);
        }
        return updated > 0;
    }

    /**
     * app.mq.enabled=false 轮询兜底：按 id 升序认领最多 limit 条 PENDING 并置 EXTRACTING。
     * 行锁 + SKIP LOCKED；调用方单线程顺序处理，保持单并发 FIFO。
     */
    @Transactional
    public List<Long> claimNextForPolling(int limit) {
        List<PdfImportJobEntity> jobs = jobRepository.claimNextPending(limit);
        List<Long> jobIds = new ArrayList<>();
        for (PdfImportJobEntity job : jobs) {
            job.setStatus(PdfImportStatus.EXTRACTING);
            jobRepository.save(job);
            jobIds.add(job.getId());
        }
        return jobIds;
    }

    /**
     * 解析编排（同步版 uploadPdf 中原有逻辑原样抽出，禁止复制 PdfTextExtractor/
     * PdfDraftBuilder 逻辑到别处）。业务失败（加密/损坏/超页/扫描件之外的异常）
     * 一律落 FAILED 终态；终态结果与 idempotent_consumptions 在同一事务写入。
     */
    @Transactional
    public ProcessOutcome process(Long jobId) {
        PdfImportJobEntity job = jobRepository.findById(jobId)
                .orElseThrow(() -> new IllegalStateException("PDF 导入任务不存在：" + jobId));

        ProcessOutcome outcome;
        try {
            PdfRawExtraction raw =
                    extractor.extract(Paths.get(job.getStoredFilePath()), properties.getMaxPages());
            PdfDraftResult result = draftBuilder.build(
                    raw, properties.getMinCharsPerPage(), properties.getMaxEmptyPageRatio());

            job.setPageCount(result.pageCount());
            job.setExtractedTextLength(result.totalChars());
            // 防御性清空历史错误字段（正常情况下 PENDING 行这些字段本就为 null）
            job.setErrorCode(null);
            job.setErrorMessage(null);
            if (result.scanned()) {
                job.setStatus(PdfImportStatus.SCANNED);
                job.setSuspectedScanned(true);
                job.setMarkdownDraft(null);
                outcome = ProcessOutcome.SCANNED;
            } else {
                String noteTitle = baseName(job.getOriginalFileName());
                String markdown = "# " + noteTitle + "\n\n" + result.markdown();
                job.setStatus(PdfImportStatus.DRAFT_READY);
                job.setSuspectedScanned(false);
                job.setMarkdownDraft(markdown);
                outcome = ProcessOutcome.DRAFT_READY;
            }
        } catch (ApiException e) {
            // 业务可预期失败（加密 PDF_ENCRYPTED、损坏/非 PDF PDF_PARSE_FAILED、
            // 空 PDF/超页数等）：沿用同步时代码与文案，落 FAILED 终态，消费者 ack。
            log.warn("PDF 业务解析失败 jobId={} userId={} errorCode={}",
                    jobId, job.getUserId(), e.getCode());
            markFailed(job, e.getCode(), e.getMessage());
            outcome = ProcessOutcome.FAILED;
        } catch (RuntimeException e) {
            log.warn("PDF 导入失败 jobId={} userId={} error={}",
                    jobId, job.getUserId(), e.getMessage(), e);
            markFailed(job, "PDF_PARSE_FAILED", "PDF 解析失败，文件可能已损坏或不受支持");
            outcome = ProcessOutcome.FAILED;
        }
        job.complete();
        jobRepository.save(job);
        // 幂等三件套之三：终态与幂等记录同一事务；唯一冲突外抛由消费者按重复投递 ack。
        idempotentConsumptionRepository.saveAndFlush(
                new IdempotentConsumptionEntity(TaskType.PDF_IMPORT.wireName() + ":" + jobId));
        return outcome;
    }

    /**
     * 对账（MQ 开启时 PdfImportWorker 每 60s 调用一次）：补投 PENDING 超时漏发，
     * 复位 EXTRACTING 超时卡死（消费者崩溃）并补写 outbox；已有 PENDING outbox 不重复补写。
     * 返回实际补写 outbox 的条数。
     */
    @Transactional
    public int reconcileStaleJobs() {
        Instant cutoff = Instant.now().minus(RECONCILE_STALE_AGE);
        List<PdfImportJobEntity> stalePending =
                jobRepository.findStalePending(cutoff, RECONCILE_BATCH_LIMIT);
        List<PdfImportJobEntity> staleExtracting =
                jobRepository.findStaleExtracting(cutoff, RECONCILE_BATCH_LIMIT);

        for (PdfImportJobEntity job : staleExtracting) {
            job.setStatus(PdfImportStatus.PENDING);
            jobRepository.save(job);
            log.warn("PDF 任务卡在 EXTRACTING 超过 {}，复位为 PENDING 等待补投 jobId={} userId={}",
                    RECONCILE_STALE_AGE, job.getId(), job.getUserId());
        }

        int recovered = 0;
        for (PdfImportJobEntity job : stalePending) {
            recovered += reissueOutboxIfAbsent(job);
        }
        for (PdfImportJobEntity job : staleExtracting) {
            recovered += reissueOutboxIfAbsent(job);
        }
        if (recovered > 0) {
            log.info("PDF 任务对账补投 stalePending={} staleExtracting={} recovered={}",
                    stalePending.size(), staleExtracting.size(), recovered);
        }
        return recovered;
    }

    private int reissueOutboxIfAbsent(PdfImportJobEntity job) {
        if (outboxEventRepository.existsByTaskTypeAndTaskIdAndStatus(
                TaskType.PDF_IMPORT.wireName(), job.getId(), OutboxEventStatus.PENDING)) {
            return 0;
        }
        outboxEventService.recordPending(new TaskMessage(
                TaskType.PDF_IMPORT, job.getId(), null, "IMPORT", Instant.now()));
        return 1;
    }

    // ---------- 查询 / 接受草稿（契约不变） ----------

    @Transactional
    public PdfImportDtos.JobResponse getJob(Long userId, Long jobId) {
        return PdfImportDtos.JobResponse.from(requireOwnedJob(jobId, userId), idCodec);
    }

    @Transactional
    public PdfImportDtos.AcceptDraftResponse acceptDraft(Long userId, Long jobId, String requestedTitle) {
        PdfImportJobEntity job = requireOwnedJob(jobId, userId);
        if (!PdfImportStatus.DRAFT_READY.equals(job.getStatus()) || job.getDocumentId() != null) {
            throw ApiException.badRequest("PDF_DRAFT_NOT_READY",
                    "当前导入任务没有可接受的 Markdown 草稿，无法创建笔记");
        }
        String title = requestedTitle == null || requestedTitle.trim().isBlank()
                ? baseName(job.getOriginalFileName())
                : requestedTitle.trim();

        String markdown = rewriteTopHeading(job.getMarkdownDraft(), title);
        DocumentEntity document = documentService.createDocumentFromDraft(title, markdown, userId);

        job.setDocumentId(document.getId());
        job.setStatus(PdfImportStatus.ACCEPTED);
        job.complete();
        jobRepository.save(job);
        return new PdfImportDtos.AcceptDraftResponse(idCodec.encode(job.getId()), idCodec.encode(document.getId()), document.getTitle());
    }

    /** 用户可改标题接受草稿：把草稿首行 H1 同步为新标题，保证笔记标题与思维导图中心主题一致。 */
    private String rewriteTopHeading(String draft, String title) {
        if (draft == null || !draft.startsWith("# ")) {
            return draft;
        }
        int newline = draft.indexOf("\n");
        String heading = "# " + title;
        return newline >= 0 ? heading + draft.substring(newline) : heading;
    }

    private PdfImportJobEntity requireOwnedJob(Long jobId, Long userId) {
        return jobRepository.findByIdAndUserId(jobId, userId)
                .orElseThrow(() -> ApiException.notFound("PDF 导入任务不存在或无权访问：" + jobId));
    }

    private void markFailed(PdfImportJobEntity job, String code, String message) {
        job.setStatus(PdfImportStatus.FAILED);
        job.setSuspectedScanned(false);
        job.setMarkdownDraft(null);
        job.setErrorCode(code);
        job.setErrorMessage(message);
    }

    private Path storeFile(MultipartFile file) {
        try {
            Path directory = Paths.get(properties.getUploadDir()).toAbsolutePath();
            Files.createDirectories(directory);
            Path target = directory.resolve(UUID.randomUUID() + ".pdf");
            // 使用系统生成的文件名，不信任原始文件名
            file.transferTo(target.toFile());
            return target;
        } catch (IOException e) {
            log.warn("PDF 文件保存失败 originalFileName 不可信，不记录", e);
            throw ApiException.badRequest("PDF_STORE_FAILED", "PDF 保存失败，请稍后重试");
        }
    }

    private boolean hasPdfMagicHeader(MultipartFile file) {
        try (InputStream input = file.getInputStream()) {
            byte[] header = input.readNBytes(PDF_MAGIC.length);
            if (header.length < PDF_MAGIC.length) {
                return false;
            }
            for (int i = 0; i < PDF_MAGIC.length; i++) {
                if (header[i] != PDF_MAGIC[i]) {
                    return false;
                }
            }
            return true;
        } catch (IOException e) {
            log.warn("读取 PDF 文件头失败，按非 PDF 处理", e);
            return false;
        }
    }

    private String safeOriginalName(String name) {
        if (name == null || name.isBlank()) {
            return "未命名.pdf";
        }
        String cleaned = name.replace("\\", "/");
        cleaned = cleaned.substring(cleaned.lastIndexOf('/') + 1);
        return cleaned.isBlank() ? "未命名.pdf" : cleaned;
    }

    private String baseName(String fileName) {
        String name = safeOriginalName(fileName);
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        base = base.trim();
        return base.isEmpty() ? "未命名文档" : truncate(base, 255);
    }

    private String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}
