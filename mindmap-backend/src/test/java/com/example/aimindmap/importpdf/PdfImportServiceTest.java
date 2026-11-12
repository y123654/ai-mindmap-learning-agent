package com.example.aimindmap.importpdf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.aimindmap.auth.ExternalIdCodec;
import com.example.aimindmap.common.error.ApiException;
import com.example.aimindmap.document.DocumentService;
import com.example.aimindmap.metrics.BusinessMetrics;
import com.example.aimindmap.mqsupport.TaskMessage;
import com.example.aimindmap.mqsupport.TaskType;
import com.example.aimindmap.mqsupport.outbox.IdempotentConsumptionEntity;
import com.example.aimindmap.mqsupport.outbox.IdempotentConsumptionRepository;
import com.example.aimindmap.mqsupport.outbox.OutboxEventRepository;
import com.example.aimindmap.mqsupport.outbox.OutboxEventService;
import com.example.aimindmap.mqsupport.outbox.OutboxEventStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

/** 阶段五-A：上传快速受理（PENDING + 同事务 outbox，不解析）与 process(jobId) 四类结果。纯 Mockito。 */
class PdfImportServiceTest {

    private PdfImportJobRepository jobRepository;
    private PdfProperties properties;
    private PdfTextExtractor extractor;
    private PdfDraftBuilder draftBuilder;
    private DocumentService documentService;
    private ExternalIdCodec idCodec;
    private OutboxEventService outboxEventService;
    private OutboxEventRepository outboxEventRepository;
    private IdempotentConsumptionRepository idempotentConsumptionRepository;
    /** 阶段八-1：上传 RT 指标断言用真实 SimpleMeterRegistry（不 mock 指标组件）。 */
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final BusinessMetrics businessMetrics = new BusinessMetrics(meterRegistry);

    private PdfImportService service;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        jobRepository = Mockito.mock(PdfImportJobRepository.class);
        properties = new PdfProperties();
        properties.setUploadDir(tempDir.toString());
        extractor = Mockito.mock(PdfTextExtractor.class);
        draftBuilder = Mockito.mock(PdfDraftBuilder.class);
        documentService = Mockito.mock(DocumentService.class);
        idCodec = Mockito.mock(ExternalIdCodec.class);
        outboxEventService = Mockito.mock(OutboxEventService.class);
        outboxEventRepository = Mockito.mock(OutboxEventRepository.class);
        idempotentConsumptionRepository = Mockito.mock(IdempotentConsumptionRepository.class);
        when(idCodec.encode(any())).thenReturn("encoded");
        when(jobRepository.save(any(PdfImportJobEntity.class))).thenAnswer(invocation -> {
            PdfImportJobEntity job = invocation.getArgument(0);
            if (job.getId() == null) {
                ReflectionTestUtils.setField(job, "id", 77L);
            }
            return job;
        });
        service = new PdfImportService(jobRepository, properties, extractor, draftBuilder,
                documentService, idCodec, outboxEventService, outboxEventRepository,
                idempotentConsumptionRepository, businessMetrics);
    }

    // ---------- 上传快速受理 ----------

    @Test
    void emptyFileStillRejectedWith400Contract() {
        MockMultipartFile file = new MockMultipartFile("file", "a.pdf", "application/pdf", new byte[0]);

        assertThatThrownBy(() -> service.uploadPdf(9L, file))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo("PDF_EMPTY_FILE");

        verify(jobRepository, never()).save(any());
        verifyNoInteractions(outboxEventService, extractor, draftBuilder);
    }

    @Test
    void oversizedFileStillRejectedWith400Contract() {
        properties.setMaxFileSizeMb(1);
        byte[] bytes = ("%PDF-" + "x".repeat(2 * 1024 * 1024)).getBytes();
        MockMultipartFile file = new MockMultipartFile("file", "big.pdf", "application/pdf", bytes);

        assertThatThrownBy(() -> service.uploadPdf(9L, file))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo("PDF_FILE_TOO_LARGE");

        verify(jobRepository, never()).save(any());
        verifyNoInteractions(outboxEventService, extractor, draftBuilder);
    }

    @Test
    void nonPdfHeaderStillRejectedWith400Contract() {
        MockMultipartFile file =
                new MockMultipartFile("file", "a.txt", "text/plain", "not a pdf at all".getBytes());

        assertThatThrownBy(() -> service.uploadPdf(9L, file))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo("PDF_NOT_PDF");

        verify(jobRepository, never()).save(any());
        verifyNoInteractions(outboxEventService, extractor, draftBuilder);
    }

    @Test
    void uploadCreatesPendingJobWritesOutboxInSameTxAndNeverParses() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "课堂笔记.pdf", "application/pdf",
                ("%PDF-1.4" + "0123456789".repeat(10)).getBytes());

        PdfImportDtos.JobResponse response = service.uploadPdf(9L, file);

        ArgumentCaptor<PdfImportJobEntity> jobCaptor = ArgumentCaptor.forClass(PdfImportJobEntity.class);
        verify(jobRepository).save(jobCaptor.capture());
        PdfImportJobEntity saved = jobCaptor.getValue();
        assertThat(saved.getStatus()).isEqualTo(PdfImportStatus.PENDING);
        assertThat(saved.getUserId()).isEqualTo(9L);
        assertThat(saved.getOriginalFileName()).isEqualTo("课堂笔记.pdf");
        assertThat(saved.getStoredFilePath()).endsWith(".pdf");
        assertThat(Files.exists(Path.of(saved.getStoredFilePath()))).isTrue();
        // UUID 落盘：文件名不是原始文件名
        assertThat(Path.of(saved.getStoredFilePath()).getFileName().toString())
                .isNotEqualTo("课堂笔记.pdf");

        ArgumentCaptor<TaskMessage> messageCaptor = ArgumentCaptor.forClass(TaskMessage.class);
        verify(outboxEventService).recordPending(messageCaptor.capture());
        TaskMessage message = messageCaptor.getValue();
        assertThat(message.taskType()).isEqualTo(TaskType.PDF_IMPORT);
        assertThat(message.taskId()).isEqualTo(77L);
        assertThat(message.entityId()).isNull();
        assertThat(message.op()).isEqualTo("IMPORT");
        assertThat(message.occurredAt()).isNotNull();

        // 快速返回契约：200 由控制器保证；响应为 PENDING、无草稿/无终态时间
        assertThat(response.status()).isEqualTo(PdfImportStatus.PENDING);
        assertThat(response.markdownDraft()).isNull();
        assertThat(response.completedAt()).isNull();
        assertThat(response.errorCode()).isNull();

        verify(extractor, never()).extract(any(), org.mockito.ArgumentMatchers.anyInt());
        verifyNoInteractions(draftBuilder);
    }

    // ---------- 阶段八-1：PDF 上传 RT 埋点（325 行第 ② 项） ----------

    @Test
    void uploadPdfRecordsUploadTimer() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "课堂笔记.pdf", "application/pdf",
                ("%PDF-1.4" + "0123456789".repeat(10)).getBytes());

        service.uploadPdf(9L, file);

        assertThat(meterRegistry.timer("aimindmap.pdf.upload").count()).isEqualTo(1L);
        assertThat(meterRegistry.timer("aimindmap.pdf.upload").getId().getTags()).isEmpty();
    }

    @Test
    void rejectedUploadRecordsNoTimerSample() {
        MockMultipartFile empty = new MockMultipartFile("file", "a.pdf", "application/pdf", new byte[0]);

        assertThatThrownBy(() -> service.uploadPdf(9L, empty))
                .isInstanceOf(ApiException.class);

        // 400 拒绝路径不记 RT（由自动装配的 http_server_requests{status="400"} 覆盖），
        // 埋点也不得改变既有异常契约
        assertThat(meterRegistry.find("aimindmap.pdf.upload").timer()).isNull();
    }

    // ---------- process(jobId) 四类结果 ----------

    @Test
    void processNormalPdfReachesDraftReadyWithSameMarkdownContractAsSyncEra() {
        PdfImportJobEntity job = jobFor(77L, "操作系统讲义.pdf");
        when(jobRepository.findById(77L)).thenReturn(java.util.Optional.of(job));
        PdfRawExtraction raw = new PdfRawExtraction(List.of(), 3, 0, 620);
        PdfDraftResult result = PdfDraftResult.ready("正文内容", raw, 206);
        when(extractor.extract(any(), eq(100))).thenReturn(raw);
        when(draftBuilder.build(raw, 100, 0.5d)).thenReturn(result);

        PdfImportService.ProcessOutcome outcome = service.process(77L);

        assertThat(outcome).isEqualTo(PdfImportService.ProcessOutcome.DRAFT_READY);
        assertThat(job.getStatus()).isEqualTo(PdfImportStatus.DRAFT_READY);
        assertThat(job.getPageCount()).isEqualTo(3);
        assertThat(job.getExtractedTextLength()).isEqualTo(620);
        assertThat(job.isSuspectedScanned()).isFalse();
        assertThat(job.getMarkdownDraft()).isEqualTo("# 操作系统讲义\n\n正文内容");
        assertThat(job.getErrorCode()).isNull();
        assertThat(job.getErrorMessage()).isNull();
        assertThat(job.getCompletedAt()).isNotNull();
        verifyIdempotentRow(77L);
    }

    @Test
    void processScannedPdfReachesScannedWithoutDraft() {
        PdfImportJobEntity job = jobFor(78L, "scan.pdf");
        when(jobRepository.findById(78L)).thenReturn(java.util.Optional.of(job));
        PdfRawExtraction raw = new PdfRawExtraction(List.of(), 2, 2, 0);
        PdfDraftResult result = PdfDraftResult.scanned(raw, 0);
        when(extractor.extract(any(), eq(100))).thenReturn(raw);
        when(draftBuilder.build(raw, 100, 0.5d)).thenReturn(result);

        PdfImportService.ProcessOutcome outcome = service.process(78L);

        assertThat(outcome).isEqualTo(PdfImportService.ProcessOutcome.SCANNED);
        assertThat(job.getStatus()).isEqualTo(PdfImportStatus.SCANNED);
        assertThat(job.isSuspectedScanned()).isTrue();
        assertThat(job.getMarkdownDraft()).isNull();
        assertThat(job.getCompletedAt()).isNotNull();
        verifyIdempotentRow(78L);
    }

    @Test
    void processEncryptedPdfFailsWithEncryptedCodeAndSyncEraMessage() {
        PdfImportJobEntity job = jobFor(79L, "secret.pdf");
        when(jobRepository.findById(79L)).thenReturn(java.util.Optional.of(job));
        when(extractor.extract(any(), eq(100)))
                .thenThrow(ApiException.badRequest("PDF_ENCRYPTED", "该 PDF 已加密或需要密码，当前版本无法读取"));

        PdfImportService.ProcessOutcome outcome = service.process(79L);

        assertThat(outcome).isEqualTo(PdfImportService.ProcessOutcome.FAILED);
        assertFailed(job, "PDF_ENCRYPTED", "该 PDF 已加密或需要密码，当前版本无法读取");
        verifyIdempotentRow(79L);
    }

    @Test
    void processCorruptPdfFromExtractorFailsWithParseFailedCode() {
        PdfImportJobEntity job = jobFor(80L, "broken.pdf");
        when(jobRepository.findById(80L)).thenReturn(java.util.Optional.of(job));
        when(extractor.extract(any(), eq(100)))
                .thenThrow(ApiException.badRequest("PDF_PARSE_FAILED", "PDF 解析失败，文件可能已损坏或不是有效的 PDF"));

        PdfImportService.ProcessOutcome outcome = service.process(80L);

        assertThat(outcome).isEqualTo(PdfImportService.ProcessOutcome.FAILED);
        assertFailed(job, "PDF_PARSE_FAILED", "PDF 解析失败，文件可能已损坏或不是有效的 PDF");
        verifyIdempotentRow(80L);
    }

    @Test
    void processUnexpectedRuntimeFailsWithLegacyFallbackCodeAndMessage() {
        PdfImportJobEntity job = jobFor(81L, "weird.pdf");
        when(jobRepository.findById(81L)).thenReturn(java.util.Optional.of(job));
        PdfRawExtraction raw = new PdfRawExtraction(List.of(), 1, 0, 200);
        when(extractor.extract(any(), eq(100))).thenReturn(raw);
        when(draftBuilder.build(raw, 100, 0.5d)).thenThrow(new RuntimeException("boom"));

        PdfImportService.ProcessOutcome outcome = service.process(81L);

        assertThat(outcome).isEqualTo(PdfImportService.ProcessOutcome.FAILED);
        assertFailed(job, "PDF_PARSE_FAILED", "PDF 解析失败，文件可能已损坏或不受支持");
        verifyIdempotentRow(81L);
    }

    @Test
    void processMissingJobRaisesIllegalState() {
        when(jobRepository.findById(404L)).thenReturn(java.util.Optional.empty());

        assertThatThrownBy(() -> service.process(404L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("404");
    }

    // ---------- 认领 / 重试复位 ----------

    @Test
    void claimForMessageDelegatesConditionalUpdate() {
        when(jobRepository.claimForConsumer(7L)).thenReturn(1);
        when(jobRepository.claimForConsumer(8L)).thenReturn(0);

        assertThat(service.claimForMessage(7L)).isTrue();
        assertThat(service.claimForMessage(8L)).isFalse();
    }

    @Test
    void requeueForMessageRetryReturnsFalseWhenRowNotExtracting() {
        when(jobRepository.requeueForRetry(7L)).thenReturn(0);

        assertThat(service.requeueForMessageRetry(7L)).isFalse();
    }

    @Test
    void reconcileResetsStaleExtractingAndReissuesMissingOutbox() {
        PdfImportJobEntity pending = jobFor(91L, "p.pdf");
        PdfImportJobEntity extracting = jobFor(92L, "e.pdf");
        extracting.setStatus(PdfImportStatus.EXTRACTING);
        when(jobRepository.findStalePending(any(), eq(PdfImportService.RECONCILE_BATCH_LIMIT)))
                .thenReturn(List.of(pending));
        when(jobRepository.findStaleExtracting(any(), eq(PdfImportService.RECONCILE_BATCH_LIMIT)))
                .thenReturn(List.of(extracting));
        when(outboxEventRepository.existsByTaskTypeAndTaskIdAndStatus(
                eq(TaskType.PDF_IMPORT.wireName()), org.mockito.ArgumentMatchers.anyLong(),
                eq(OutboxEventStatus.PENDING))).thenReturn(false);

        int recovered = service.reconcileStaleJobs();

        assertThat(recovered).isEqualTo(2);
        assertThat(extracting.getStatus()).isEqualTo(PdfImportStatus.PENDING);
        verify(outboxEventService, Mockito.times(2)).recordPending(any(TaskMessage.class));
    }

    @Test
    void reconcileSkipsReissueWhenPendingOutboxAlreadyExists() {
        PdfImportJobEntity pending = jobFor(93L, "p.pdf");
        when(jobRepository.findStalePending(any(), eq(PdfImportService.RECONCILE_BATCH_LIMIT)))
                .thenReturn(List.of(pending));
        when(jobRepository.findStaleExtracting(any(), eq(PdfImportService.RECONCILE_BATCH_LIMIT)))
                .thenReturn(List.of());
        when(outboxEventRepository.existsByTaskTypeAndTaskIdAndStatus(
                TaskType.PDF_IMPORT.wireName(), 93L, OutboxEventStatus.PENDING)).thenReturn(true);

        assertThat(service.reconcileStaleJobs()).isZero();
        verify(outboxEventService, never()).recordPending(any());
    }

    @Test
    void pollClaimsOldestPendingInOrderAndMarksThemExtracting() {
        PdfImportJobEntity first = jobFor(101L, "1.pdf");
        PdfImportJobEntity second = jobFor(102L, "2.pdf");
        when(jobRepository.claimNextPending(PdfImportService.POLL_BATCH_LIMIT))
                .thenReturn(List.of(first, second));

        List<Long> ids = service.claimNextForPolling(PdfImportService.POLL_BATCH_LIMIT);

        assertThat(ids).containsExactly(101L, 102L);
        assertThat(first.getStatus()).isEqualTo(PdfImportStatus.EXTRACTING);
        assertThat(second.getStatus()).isEqualTo(PdfImportStatus.EXTRACTING);
    }

    // ---------- helpers ----------

    private PdfImportJobEntity jobFor(Long id, String fileName) {
        PdfImportJobEntity job = new PdfImportJobEntity();
        ReflectionTestUtils.setField(job, "id", id);
        job.setUserId(9L);
        job.setOriginalFileName(fileName);
        job.setStoredFilePath(tempDir.resolve(storedName(id)).toString());
        job.setStatus(PdfImportStatus.EXTRACTING);
        return job;
    }

    private static String storedName(Long id) {
        return id + ".pdf";
    }

    private void assertFailed(PdfImportJobEntity job, String code, String message) {
        assertThat(job.getStatus()).isEqualTo(PdfImportStatus.FAILED);
        assertThat(job.getErrorCode()).isEqualTo(code);
        assertThat(job.getErrorMessage()).isEqualTo(message);
        assertThat(job.getMarkdownDraft()).isNull();
        assertThat(job.isSuspectedScanned()).isFalse();
        assertThat(job.getCompletedAt()).isNotNull();
    }

    private void verifyIdempotentRow(Long jobId) {
        ArgumentCaptor<IdempotentConsumptionEntity> captor =
                ArgumentCaptor.forClass(IdempotentConsumptionEntity.class);
        verify(idempotentConsumptionRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getMessageId()).isEqualTo("PDF_IMPORT:" + jobId);
    }
}
