package com.example.aimindmap.vectorindex;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.scheduling.annotation.Scheduled;

/** 4-3：MQ 开启时 worker 只对账补投；关闭时保留轮询直处理语义。纯 Mockito。 */
class VectorIndexWorkerTest {

    private final VectorIndexTaskService taskService =
            Mockito.mock(VectorIndexTaskService.class);
    private final VectorIndexTaskProcessor processor =
            Mockito.mock(VectorIndexTaskProcessor.class);

    private final VectorIndexTaskService.TaskLoad task = new VectorIndexTaskService.TaskLoad(
            5L, 1L, VectorResourceType.QUESTION, "8", 0, VectorTaskOperation.UPSERT);

    @Test
    void mqEnabledTickOnlyReconcilesAndNeverProcessesDirectly() {
        VectorIndexWorker worker = new VectorIndexWorker(taskService, processor, true);

        worker.tick();

        verify(taskService).reconcileStaleTasks();
        verify(taskService, never()).claimBatch();
        verify(processor, never()).process(any());
    }

    @Test
    void mqDisabledTickKeepsPollingFallbackProcessing() {
        VectorIndexWorker worker = new VectorIndexWorker(taskService, processor, false);
        when(taskService.claimBatch()).thenReturn(List.of(task));
        var result =
                new VectorIndexTaskProcessor.ProcessResult.UpsertSucceeded(UUID.randomUUID(), "h");
        when(processor.process(task)).thenReturn(result);

        worker.tick();

        verify(taskService, never()).reconcileStaleTasks();
        verify(taskService).markUpsertSucceeded(task, result.pointId(), result.contentHash());
    }

    @Test
    void mqDisabledExternalFailureMarksTaskFailed() {
        VectorIndexWorker worker = new VectorIndexWorker(taskService, processor, false);
        when(taskService.claimBatch()).thenReturn(List.of(task));
        when(processor.process(task))
                .thenThrow(new VectorIndexExternalException("Qdrant 写入失败", new RuntimeException()));

        worker.tick();

        verify(taskService).markFailed(task, "Qdrant 写入失败");
    }

    @Test
    void scheduledAnnotationUsesRequeueScanDelayKey() throws Exception {
        Method tick = VectorIndexWorker.class.getMethod("tick");
        Scheduled scheduled = tick.getAnnotation(Scheduled.class);

        assertThat(scheduled).isNotNull();
        assertThat(scheduled.fixedDelayString())
                .isEqualTo("${app.mq.requeue-scan-delay-ms:60000}");
        // 旧键 app.qdrant.worker-fixed-delay-ms 不得再被 worker 引用
        assertThat(scheduled.fixedDelayString()).doesNotContain("app.qdrant");
    }
}
