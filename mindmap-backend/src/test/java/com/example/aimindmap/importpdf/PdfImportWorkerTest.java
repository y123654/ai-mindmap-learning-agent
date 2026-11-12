package com.example.aimindmap.importpdf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.scheduling.annotation.Scheduled;

/** 阶段五-A：MQ 开启时 PDF worker 只对账补投；关闭时保留按 id 升序的单并发轮询兜底。纯 Mockito。 */
class PdfImportWorkerTest {

    private final PdfImportService pdfImportService = Mockito.mock(PdfImportService.class);

    @Test
    void mqEnabledTickOnlyReconcilesAndNeverPollsOrProcesses() {
        PdfImportWorker worker = new PdfImportWorker(pdfImportService, true);

        worker.tick();

        verify(pdfImportService).reconcileStaleJobs();
        verify(pdfImportService, never()).claimNextForPolling(anyInt());
        verify(pdfImportService, never()).process(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void mqDisabledTickPollsOldestPendingAndProcessesInOrder() {
        PdfImportWorker worker = new PdfImportWorker(pdfImportService, false);
        when(pdfImportService.claimNextForPolling(PdfImportService.POLL_BATCH_LIMIT))
                .thenReturn(List.of(11L, 12L));
        when(pdfImportService.process(11L)).thenReturn(PdfImportService.ProcessOutcome.DRAFT_READY);
        when(pdfImportService.process(12L)).thenReturn(PdfImportService.ProcessOutcome.SCANNED);

        worker.tick();

        verify(pdfImportService, never()).reconcileStaleJobs();
        verify(pdfImportService).process(11L);
        verify(pdfImportService).process(12L);
    }

    @Test
    void mqDisabledEmptyPollDoesNothing() {
        PdfImportWorker worker = new PdfImportWorker(pdfImportService, false);
        when(pdfImportService.claimNextForPolling(PdfImportService.POLL_BATCH_LIMIT))
                .thenReturn(List.of());

        worker.tick();

        verify(pdfImportService, never()).process(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void mqDisabledDatabaseFailureResetsJobToPendingForNextTick() {
        PdfImportWorker worker = new PdfImportWorker(pdfImportService, false);
        when(pdfImportService.claimNextForPolling(PdfImportService.POLL_BATCH_LIMIT))
                .thenReturn(List.of(13L));
        when(pdfImportService.process(13L))
                .thenThrow(new DataAccessResourceFailureException("db down"));

        worker.tick();

        verify(pdfImportService).requeueForMessageRetry(13L);
    }

    @Test
    void scheduledAnnotationUsesRequeueScanDelayKey() throws Exception {
        Method tick = PdfImportWorker.class.getMethod("tick");
        Scheduled scheduled = tick.getAnnotation(Scheduled.class);

        assertThat(scheduled).isNotNull();
        assertThat(scheduled.fixedDelayString())
                .isEqualTo("${app.mq.requeue-scan-delay-ms:60000}");
    }
}
