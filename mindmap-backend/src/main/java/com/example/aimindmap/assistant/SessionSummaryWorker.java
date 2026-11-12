package com.example.aimindmap.assistant;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 会话摘要后台执行器（方案 §5.4 第 5 条），阶段六-3 后仅在 app.mq.enabled=false
 * 降级路径使用：该模式下 KnowledgeAssistantService 仍发布
 * {@link SessionSummaryRequested} 事件，本 Worker AFTER_COMMIT + @Async 执行摘要。
 * app.mq.enabled=true（缺省）时发布点改写同事务 outbox，事件不再发出，本 Worker 空闲，
 * 执行权在 {@link SessionSummaryMessageConsumer}（SESSION_SUMMARY 独立队列 + 三档重试 + DLQ）。
 * AFTER_COMMIT 保证异步线程读到已提交的消息；@Async 保证摘要不占用、不影响 SSE 主链路。
 */
@Component
public class SessionSummaryWorker {

    private final SessionSummaryService sessionSummaryService;

    public SessionSummaryWorker(SessionSummaryService sessionSummaryService) {
        this.sessionSummaryService = sessionSummaryService;
    }

    @Async("syncJobTaskExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSessionSummaryRequested(SessionSummaryRequested event) {
        sessionSummaryService.generateSummary(event.sessionId(), event.userId());
    }
}
