package com.example.aimindmap.question;

import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * 题目生成后台执行器，避免 AI 调用长时间占用 HTTP 请求。两种运行模式（阶段六-2）：
 * <ul>
 *   <li>app.mq.enabled=false（§3.5 降级路径）：任务创建事务提交后发
 *       {@link QuestionGenerationRequested} 事件，本 Worker @Async 调 processGenerationJob，
 *       行为与阶段 4 完全一致；</li>
 *   <li>app.mq.enabled=true（缺省）：创建任务改写同事务 outbox，不再发布事件，
 *       本 Worker 收不到事件、保持空闲；执行权在 {@link QuestionGenerationMessageConsumer}，
 *       对账在 {@link QuestionGenerationReconciler}。</li>
 * </ul>
 */
@Service
public class QuestionGenerationWorker {

    private final QuestionService questionService;

    public QuestionGenerationWorker(QuestionService questionService) {
        this.questionService = questionService;
    }

    @Async("syncJobTaskExecutor")
    @EventListener
    public void onQuestionGenerationRequested(QuestionGenerationRequested event) {
        questionService.processGenerationJob(event.jobId());
    }
}
