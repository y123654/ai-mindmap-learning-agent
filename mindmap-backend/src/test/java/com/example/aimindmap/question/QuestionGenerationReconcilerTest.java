package com.example.aimindmap.question;

import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/** 阶段六-2：对账器调度只委托 QuestionService.reconcileStaleJobs，纯 Mockito。 */
class QuestionGenerationReconcilerTest {

    private final QuestionService questionService = Mockito.mock(QuestionService.class);

    @Test
    void tickDelegatesToReconcileStaleJobs() {
        QuestionGenerationReconciler reconciler = new QuestionGenerationReconciler(questionService);

        reconciler.tick();

        verify(questionService).reconcileStaleJobs();
    }
}
