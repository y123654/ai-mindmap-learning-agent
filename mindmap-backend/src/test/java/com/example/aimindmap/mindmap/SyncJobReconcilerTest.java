package com.example.aimindmap.mindmap;

import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/** 阶段六-1：对账器调度只委托 SyncJobService.reconcileStaleJobs，纯 Mockito。 */
class SyncJobReconcilerTest {

    private final SyncJobService syncJobService = Mockito.mock(SyncJobService.class);

    @Test
    void tickDelegatesToReconcileStaleJobs() {
        SyncJobReconciler reconciler = new SyncJobReconciler(syncJobService);

        reconciler.tick();

        verify(syncJobService).reconcileStaleJobs();
    }
}
