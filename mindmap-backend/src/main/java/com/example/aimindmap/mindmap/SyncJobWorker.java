package com.example.aimindmap.mindmap;

import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * 同步任务后台执行器（§5.2 / §13.3），两种运行模式（阶段六-1）：
 * <ul>
 *   <li>app.mq.enabled=false（§3.5 降级路径）：任务创建事务提交后发 {@link SyncJobRequested}
 *       事件，本 Worker @Async 调 processJob，行为与阶段 3 完全一致；</li>
 *   <li>app.mq.enabled=true（缺省）：创建任务改写同事务 outbox，不再发布事件，
 *       本 Worker 收不到事件、保持空闲；执行权在 {@link SyncJobMessageConsumer}，
 *       对账在 {@link SyncJobReconciler}。</li>
 * </ul>
 * AI 调用在独立线程进行，不占用 HTTP 请求线程，也不包在数据库长事务里。
 */
@Service
public class SyncJobWorker {

    private final SyncJobService syncJobService;

    public SyncJobWorker(SyncJobService syncJobService) {
        this.syncJobService = syncJobService;
    }

    @Async("syncJobTaskExecutor")
    @EventListener
    public void onSyncJobRequested(SyncJobRequested event) {
        syncJobService.processJob(event.jobId());
    }
}
