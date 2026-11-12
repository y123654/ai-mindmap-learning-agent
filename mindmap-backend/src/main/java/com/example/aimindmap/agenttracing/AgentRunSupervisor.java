package com.example.aimindmap.agenttracing;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.stereotype.Component;

/**
 * Agent 整体执行超时控制（方案 §4.3：60~90 秒可配置）。
 * 用虚拟线程执行任务，超时即中断并抛出稳定错误码 AGENT_RUN_TIMEOUT。
 */
@Component
public class AgentRunSupervisor {

    public <T> T executeWithTimeout(Duration timeout, Callable<T> task) {
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        Future<T> future = executor.submit(task);
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new AgentRunAbortException("AGENT_RUN_TIMEOUT",
                    "Agent 执行超时（超过 " + timeout.toSeconds() + " 秒），已自动中止，请把问题拆小后重试", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new AgentRunAbortException("AGENT_TOOL_FAILED", "Agent 执行失败，请稍后重试", cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AgentRunAbortException("AGENT_RUN_INTERRUPTED", "Agent 执行被中断，请重新提问", e);
        } finally {
            executor.shutdownNow();
        }
    }
}
