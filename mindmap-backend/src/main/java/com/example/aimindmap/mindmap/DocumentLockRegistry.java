package com.example.aimindmap.mindmap;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Component;

/**
 * 同一文档的同步任务、手动节点编辑、内容保存共用一把进程内锁，
 * 配合 sync_jobs 活动任务检查（§13.4）避免并发破坏图数据。
 */
@Component
public class DocumentLockRegistry {

    private final ConcurrentMap<Long, Object> locks = new ConcurrentHashMap<>();

    public Object lockFor(Long documentId) {
        return locks.computeIfAbsent(documentId, key -> new Object());
    }
}