package com.example.aimindmap.agentmemory;

/**
 * 显式记忆处理决策（prepareUserMessage 事务外决定、事务内落库）。
 * Create：新建 active 记忆；Merge：更新同一条记忆（不新增行）。
 */
public sealed interface MemoryDecision {

    record None() implements MemoryDecision {
    }

    record Create(String memoryType, String content) implements MemoryDecision {
    }

    record Merge(Long memoryId, String mergedContent) implements MemoryDecision {
    }
}
