package com.example.aimindmap.agenttracing;

/**
 * 工具调用过程事件的出口（P0-2，方案 §5.2.1）。
 *
 * <p>生产实现由 {@code KnowledgeAssistantStreamer} 按 run 创建并写入 SSE；
 * 单测 / 无 SSE 场景用 {@link #noop()}。实现内部必须吞掉推送异常，
 * 事件推送只是锦上添花，任何失败都不得影响工具执行与主回答流程。</p>
 */
public interface AgentToolEventSink {

    /**
     * 分配本次工具调用的序号（从 1 开始，同一调用的 start 与结束事件共用一个序号）。
     * 实现必须线程安全，保证缓存命中与真实调用混用时序号仍全局递增。
     */
    int nextSeq();

    /** 推送一个过程事件；实现内部必须捕获并吞掉所有推送异常。 */
    void emit(AgentToolCallEvent event);

    /** 空实现：不推送、不计数（单测与无 SSE 场景）。 */
    static AgentToolEventSink noop() {
        return Noop.INSTANCE;
    }

    /** 单例空实现：没有 SSE 通道时事件直接丢弃。 */
    final class Noop implements AgentToolEventSink {

        private static final Noop INSTANCE = new Noop();

        private Noop() {
        }

        @Override
        public int nextSeq() {
            return 0;
        }

        @Override
        public void emit(AgentToolCallEvent event) {
            // 无 SSE 通道，事件直接丢弃
        }
    }
}
