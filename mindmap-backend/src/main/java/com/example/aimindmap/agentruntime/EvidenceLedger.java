package com.example.aimindmap.agentruntime;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 单次 run 的证据台账（方案 §P0-3）：放在 ToolContext 里，由检索/读取工具写入，
 * 由 Streamer 在流结束后读快照做引用校验与落库。
 *
 * <p>规则：
 * <ol>
 *   <li>按 {@code (resourceType, resourceId, chunkIndex)} 去重，重复命中复用同一个 key；</li>
 *   <li>最多保留 {@value #DEFAULT_MAX_ITEMS} 条，超出时丢弃分数最低的一条并打 {@code warn}
 *       （带 runId / 被丢 key / 分数）；</li>
 *   <li>{@link #items()} 返回不可变快照，外部不能改台账。</li>
 * </ol>
 *
 * <p>并发：工具在 Spring AI 内部可能被并发调用，{@link #register} 与 {@link #items()}
 * 都是 {@code synchronized}，保证 key 分配与容量控制不出现竞态。
 */
public final class EvidenceLedger {

    private static final Logger log = LoggerFactory.getLogger(EvidenceLedger.class);

    /** 方案 §6 配置项 {@code app.agent.runtime.evidence.max-items} 的默认值 12。 */
    public static final int DEFAULT_MAX_ITEMS = 12;

    private final Long runId;
    private final int maxItems;
    private final AtomicInteger sequence = new AtomicInteger();
    private final Map<EvidenceIdentity, EvidenceItem> items = new LinkedHashMap<>();

    public EvidenceLedger(Long runId) {
        this(runId, DEFAULT_MAX_ITEMS);
    }

    public EvidenceLedger(Long runId, int maxItems) {
        if (maxItems <= 0) {
            throw new IllegalArgumentException("证据上限必须大于 0：" + maxItems);
        }
        this.runId = runId;
        this.maxItems = maxItems;
    }

    /**
     * 登记一条证据（P1-E3b 之前的 6 参写法，等价于 {@code suspicious=false}）。
     *
     * @return 证据编号（E1、E2…）；参数不完整时返回 {@code null}，调用方不输出编号即可
     */
    public synchronized String register(String resourceType,
                                        String resourceId,
                                        Integer chunkIndex,
                                        double score,
                                        String title,
                                        String snippet) {
        return register(resourceType, resourceId, chunkIndex, score, title, snippet, false);
    }

    /**
     * 登记一条证据。
     *
     * @param suspicious P1-E3b：{@code EvidenceSanitizer} 是否扫到疑似指令文本；
     *                   去重命中时<b>只升级不降级</b>（已有条目是 false、这次是 true → 改成 true，
     *                   防止“同一片段第二次命中时标记丢失”）
     * @return 证据编号（E1、E2…）；参数不完整时返回 {@code null}，调用方不输出编号即可
     */
    public synchronized String register(String resourceType,
                                        String resourceId,
                                        Integer chunkIndex,
                                        double score,
                                        String title,
                                        String snippet,
                                        boolean suspicious) {
        if (resourceType == null || resourceType.isBlank() || resourceId == null || resourceId.isBlank()) {
            log.warn("证据登记参数不完整，跳过登记 runId={} resourceType={} resourceId={}",
                    runId, resourceType, resourceId);
            return null;
        }
        EvidenceIdentity identity = new EvidenceIdentity(resourceType, resourceId, chunkIndex);
        EvidenceItem existing = items.get(identity);
        if (existing != null) {
            if (suspicious && !existing.suspicious()) {
                items.put(identity, new EvidenceItem(existing.key(), existing.resourceType(),
                        existing.resourceId(), existing.chunkIndex(), existing.score(),
                        existing.title(), existing.snippet(), true));
                log.warn("证据重复命中且本次扫到疑似指令文本，升级可疑标记 runId={} evidenceKey={}",
                        runId, existing.key());
            }
            log.debug("证据重复命中，复用编号 runId={} key={} resourceType={} resourceId={} chunkIndex={}",
                    runId, existing.key(), resourceType, resourceId, identity.chunkIndex());
            return existing.key();
        }
        String key = "E" + sequence.incrementAndGet();
        items.put(identity, new EvidenceItem(key, resourceType, resourceId, identity.chunkIndex(),
                score, title, snippet, suspicious));
        while (items.size() > maxItems) {
            evictLowestScore();
        }
        return key;
    }

    /** 本台账所属的 run id（P1-E3b：工具要在日志里带 runId，但不想再穿一层参数）。 */
    public Long runId() {
        return runId;
    }

    /** 当前台账快照（不可变）。 */
    public synchronized List<EvidenceItem> items() {
        return List.copyOf(items.values());
    }

    public synchronized int size() {
        return items.size();
    }

    private void evictLowestScore() {
        EvidenceIdentity lowestIdentity = null;
        EvidenceItem lowest = null;
        for (Map.Entry<EvidenceIdentity, EvidenceItem> entry : items.entrySet()) {
            if (lowest == null || entry.getValue().score() < lowest.score()) {
                lowestIdentity = entry.getKey();
                lowest = entry.getValue();
            }
        }
        if (lowest == null) {
            return;
        }
        items.remove(lowestIdentity);
        log.warn("证据条目超过上限，丢弃最低分条目 runId={} maxItems={} droppedKey={} droppedScore={} "
                        + "resourceType={} resourceId={} chunkIndex={}",
                runId, maxItems, lowest.key(), lowest.score(),
                lowest.resourceType(), lowest.resourceId(), lowest.chunkIndex());
    }

    /** 去重键：资料类型 + 资料 ID + 片段序号；chunkIndex 为空按 0 处理（整篇读取）。 */
    private record EvidenceIdentity(String resourceType, String resourceId, Integer chunkIndex) {

        private EvidenceIdentity {
            if (chunkIndex == null) {
                chunkIndex = 0;
            }
        }
    }
}
