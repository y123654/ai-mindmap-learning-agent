package com.example.aimindmap.agentmemory;

import com.example.aimindmap.common.error.ApiException;
import com.example.aimindmap.vectorindex.VectorSearchService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 长期记忆管理业务（方案 §6.5 / §11.2）+ active 记忆上下文注入（§5.1）。
 * userId 全部来自调用方登录态透传；所有按 id 的操作先做归属校验，不命中抛 404。
 */
@Service
public class AgentMemoryService {

    private static final Logger log = LoggerFactory.getLogger(AgentMemoryService.class);

    static final int MEMORY_BLOCK_MAX_CHARS = 1200;
    /** P1-6：常驻三类（profile/preference/goal）合计最多占用的字符数（按注入行整条计入）。 */
    static final int RESIDENT_MEMORY_MAX_CHARS = 400;
    /** P1-6：相关召回条数 top 5。 */
    static final int RELEVANT_MEMORY_TOP_K = 5;
    /** P1-6：相关召回最低向量相似度。 */
    static final double RELEVANT_MEMORY_MIN_SCORE = 0.5d;
    /** 常驻记忆类型：跨问题都需要的用户画像类信息。 */
    static final Set<String> RESIDENT_TYPES = Set.of(
            MemoryTypeRules.PROFILE, MemoryTypeRules.PREFERENCE, MemoryTypeRules.GOAL);
    static final String BLOCK_HEADER =
            "----- 长期记忆（系统已了解的该用户信息；请自然运用，不要生硬复述） -----";
    static final String BLOCK_FOOTER = "\n----- 长期记忆结束 -----";
    static final int EXPLICIT_IMPORTANCE = 3;
    static final int EXPLICIT_CONFIDENCE = 100;
    static final String ACTIVE = "active";
    static final String CANDIDATE = "candidate";
    static final String ARCHIVED = "archived";
    static final String REJECTED = "rejected";
    static final Set<String> VALID_TYPES = Set.of(
            MemoryTypeRules.PROFILE, MemoryTypeRules.PREFERENCE, MemoryTypeRules.WEAKNESS,
            MemoryTypeRules.GOAL, MemoryTypeRules.TASK, MemoryTypeRules.FACT);
    /** GET status 过滤允许值（任务二：active / candidate / archived）。 */
    static final Set<String> FILTER_STATUSES = Set.of(ACTIVE, CANDIDATE, ARCHIVED);

    private final AgentMemoryRepository memoryRepository;
    private final MemoryVectorService vectorService;

    public AgentMemoryService(AgentMemoryRepository memoryRepository,
                              MemoryVectorService vectorService) {
        this.memoryRepository = memoryRepository;
        this.vectorService = vectorService;
    }

    // ---------- 管理接口 ----------

    @Transactional(readOnly = true)
    public List<AgentMemoryEntity> listMemories(Long userId, String status) {
        if (status == null || status.isBlank()) {
            return memoryRepository.findByUserIdOrderByImportanceDescUpdatedAtDesc(userId);
        }
        String normalized = status.trim();
        if (!FILTER_STATUSES.contains(normalized)) {
            throw ApiException.badRequest("MEMORY_STATUS_FILTER_INVALID",
                    "状态过滤只能是 active、candidate、archived");
        }
        return memoryRepository.findByUserIdAndStatusOrderByImportanceDescUpdatedAtDesc(
                userId, normalized);
    }

    /** 手动创建：固定落 candidate，不写 Qdrant。 */
    @Transactional
    public AgentMemoryEntity createCandidate(Long userId, AgentMemoryDtos.CreateMemoryRequest request) {
        if (request == null) {
            throw ApiException.badRequest("MEMORY_REQUEST_INVALID", "请求体不能为空");
        }
        String memoryType = normalizeType(request.memoryType());
        AgentMemoryEntity memory = new AgentMemoryEntity();
        memory.setUserId(userId);
        memory.setMemoryType(memoryType);
        memory.setContent(request.content().trim());
        memory.setImportance(request.importance());
        memory.setConfidence(request.confidence());
        memory.setStatus(CANDIDATE);
        AgentMemoryEntity saved = memoryRepository.save(memory);
        log.info("已手动创建候选记忆 userId={} memoryId={} type={} importance={} confidence={}",
                userId, saved.getId(), memoryType, saved.getImportance(), saved.getConfidence());
        return saved;
    }

    /** 更新四个允许字段；status 不允许通过 PUT 修改；active 且内容/类型变更时重建点。 */
    @Transactional
    public AgentMemoryEntity updateMemory(Long userId, Long memoryId,
                                          AgentMemoryDtos.UpdateMemoryRequest request) {
        if (request == null) {
            throw ApiException.badRequest("MEMORY_REQUEST_INVALID", "请求体不能为空");
        }
        AgentMemoryEntity memory = getOwned(memoryId, userId);
        String memoryType = normalizeType(request.memoryType());
        boolean pointDirty = ACTIVE.equals(memory.getStatus())
                && (!memory.getContent().equals(request.content().trim())
                || !memory.getMemoryType().equals(memoryType));

        memory.setContent(request.content().trim());
        memory.setMemoryType(memoryType);
        memory.setImportance(request.importance());
        memory.setConfidence(request.confidence());
        memory.setUpdatedAt(Instant.now());
        AgentMemoryEntity saved = memoryRepository.save(memory);

        if (pointDirty) {
            runAfterCommitSafely("重建", userId, memoryId, () -> vectorService.upsertPoint(saved));
        }
        return saved;
    }

    @Transactional
    public void deleteMemory(Long userId, Long memoryId) {
        AgentMemoryEntity memory = getOwned(memoryId, userId);
        memoryRepository.delete(memory);
        runAfterCommitSafely("删除", userId, memoryId, () -> vectorService.deletePoint(memoryId));
    }

    /** candidate（或 archived）→ active 并写点；已 active 幂等；rejected 拒绝。 */
    @Transactional
    public AgentMemoryEntity confirmMemory(Long userId, Long memoryId) {
        AgentMemoryEntity memory = getOwned(memoryId, userId);
        if (ACTIVE.equals(memory.getStatus())) {
            return memory;
        }
        if (!CANDIDATE.equals(memory.getStatus()) && !ARCHIVED.equals(memory.getStatus())) {
            throw ApiException.badRequest("MEMORY_STATUS_INVALID",
                    "rejected 状态的记忆不能确认，请删除后重新添加");
        }
        memory.setStatus(ACTIVE);
        memory.setUpdatedAt(Instant.now());
        AgentMemoryEntity saved = memoryRepository.save(memory);
        runAfterCommitSafely("确认", userId, memoryId, () -> vectorService.upsertPoint(saved));
        return saved;
    }

    /** → archived 并删点；已 archived 幂等；rejected 拒绝。 */
    @Transactional
    public AgentMemoryEntity archiveMemory(Long userId, Long memoryId) {
        AgentMemoryEntity memory = getOwned(memoryId, userId);
        if (ARCHIVED.equals(memory.getStatus())) {
            return memory;
        }
        if (REJECTED.equals(memory.getStatus())) {
            throw ApiException.badRequest("MEMORY_STATUS_INVALID",
                    "rejected 状态的记忆不能归档，请直接删除");
        }
        boolean wasActive = ACTIVE.equals(memory.getStatus());
        memory.setStatus(ARCHIVED);
        memory.setUpdatedAt(Instant.now());
        AgentMemoryEntity saved = memoryRepository.save(memory);
        if (wasActive) {
            runAfterCommitSafely("归档", userId, memoryId, () -> vectorService.deletePoint(memoryId));
        }
        return saved;
    }

    /** 清空本人全部记忆及其全部点（方案 §6.5 第 5 条）。 */
    @Transactional
    public int clearMemories(Long userId) {
        List<AgentMemoryEntity> memories = memoryRepository.findByUserIdOrderByImportanceDescUpdatedAtDesc(userId);
        List<Long> memoryIds = memories.stream().map(AgentMemoryEntity::getId).toList();
        memoryRepository.deleteAll(memories);
        for (Long memoryId : memoryIds) {
            runAfterCommitSafely("清空", userId, memoryId, () -> vectorService.deletePoint(memoryId));
        }
        log.info("已清空长期记忆 userId={} count={}", userId, memoryIds.size());
        return memoryIds.size();
    }

    // ---------- 显式记忆 PG 落库（prepareUserMessage 事务内调用） ----------

    @Transactional
    public AgentMemoryEntity createExplicitActiveMemory(
            Long userId, MemoryDecision.Create decision,
            Long sourceSessionId, Long sourceMessageId) {
        AgentMemoryEntity memory = new AgentMemoryEntity();
        memory.setUserId(userId);
        memory.setMemoryType(decision.memoryType());
        memory.setContent(decision.content());
        memory.setImportance((short) EXPLICIT_IMPORTANCE);
        memory.setConfidence((short) EXPLICIT_CONFIDENCE);
        memory.setStatus(ACTIVE);
        // 方案 §10.2：显式记忆回填溯源信息（触发它的会话与用户消息）
        memory.setSourceSessionId(sourceSessionId);
        memory.setSourceMessageId(sourceMessageId);
        AgentMemoryEntity saved = memoryRepository.save(memory);
        log.info("已按用户指令创建 active 记忆 userId={} memoryId={} type={}",
                userId, saved.getId(), decision.memoryType());
        return saved;
    }

    @Transactional
    public AgentMemoryEntity mergeExplicitMemory(Long userId, MemoryDecision.Merge decision) {
        AgentMemoryEntity memory = getOwned(decision.memoryId(), userId);
        // 方案 §10.2：合并只更新内容，source_session_id/source_message_id 保留该记忆最初来源
        memory.setContent(decision.mergedContent());
        memory.setUpdatedAt(Instant.now());
        AgentMemoryEntity saved = memoryRepository.save(memory);
        log.info("已按用户指令合并更新记忆 userId={} memoryId={}", userId, saved.getId());
        return saved;
    }

    // ---------- active 记忆注入 ----------

    /**
     * 单参版本（历史调用方）：等价于 query 为 null 的二参版本，
     * 行为与 P1-E3 之前逐字节一致（只按 importance desc 取，1200 字符硬预算）。
     */
    public MemoryInjection buildInjection(Long userId) {
        return buildInjection(userId, null);
    }

    /**
     * 方案 §P1-6 记忆注入"相关性 + 常驻"。
     *
     * <p>query 为空/空白 → 与单参版本逐字节一致（只按 importance desc，不加常驻/相关分区）。
     * 否则：常驻（profile/preference/goal，按重要度降序、整条计入、最多 400 字符）
     * → 相关（{@code searchMemories(userId, query, 0.5, 5)}，按"相关分 × 重要度"降序），
     * 按 memory id 去重，总预算仍 1200 字符，格式标记与单参版本一致。
     *
     * <p>向量检索不可用/抛异常 → 记 warn（userId + 原因，不打记忆正文）并用本次已读到的
     * active 列表回退到单参版本逻辑，本轮问答不失败。
     */
    public MemoryInjection buildInjection(Long userId, String query) {
        List<AgentMemoryEntity> activeMemories = memoryRepository
                .findByUserIdAndStatusOrderByImportanceDescUpdatedAtDesc(userId, ACTIVE);
        if (query == null || query.isBlank()) {
            return renderByImportance(activeMemories);
        }

        List<AgentMemoryEntity> residentMemories = selectResidentMemories(activeMemories);
        List<ScoredMemory> relevantMemories;
        try {
            relevantMemories = findRelevantMemories(userId, query, activeMemories);
        } catch (Exception e) {
            log.warn("记忆相关性检索失败，回退为重要度排序注入 userId={} error={}",
                    userId, e.toString());
            MemoryInjection fallback = renderByImportance(activeMemories);
            log.info("记忆注入降级完成 userId={} degraded=true 注入条数={} 总字符数={}",
                    userId, fallback.memories().size(), fallback.block().length());
            return fallback;
        }

        InjectionParts parts = composeInjection(residentMemories, relevantMemories);
        log.info("记忆注入 userId={} degraded=false 常驻条数={} 相关条数={} 注入条数={} 总字符数={}",
                userId, parts.residentCount(), parts.relevantCount(),
                parts.injection().memories().size(), parts.injection().block().length());
        return parts.injection();
    }

    /** 常驻三类：profile / preference / goal（顺序沿用重要度降序的 active 列表）。 */
    private static List<AgentMemoryEntity> selectResidentMemories(List<AgentMemoryEntity> activeMemories) {
        return activeMemories.stream()
                .filter(memory -> RESIDENT_TYPES.contains(memory.getMemoryType()))
                .toList();
    }

    /**
     * 相关召回：向量检索 top5（阈值 0.5），只保留 PG 中仍 active 的记忆，
     * 按「相关分 × 重要度」降序；向量分与重要度量纲见交付汇报。
     */
    private List<ScoredMemory> findRelevantMemories(Long userId, String query,
                                                    List<AgentMemoryEntity> activeMemories) {
        List<VectorSearchService.VectorHit> hits = vectorService.searchMemories(
                userId, query, RELEVANT_MEMORY_MIN_SCORE, RELEVANT_MEMORY_TOP_K);
        Map<Long, AgentMemoryEntity> activeById = new LinkedHashMap<>();
        for (AgentMemoryEntity memory : activeMemories) {
            if (memory.getId() != null) {
                activeById.putIfAbsent(memory.getId(), memory);
            }
        }
        List<ScoredMemory> scored = new ArrayList<>();
        Set<Long> seen = new LinkedHashSet<>();
        for (VectorSearchService.VectorHit hit : hits) {
            Long memoryId;
            try {
                memoryId = Long.valueOf(hit.sourceId());
            } catch (NumberFormatException e) {
                log.warn("记忆相关性命中来源 ID 非法，忽略该条 userId={} sourceId={}",
                        userId, hit.sourceId());
                continue;
            }
            AgentMemoryEntity memory = activeById.get(memoryId);
            if (memory == null) {
                // 残留点/已归档记忆：以 PG 为准，不注入正文
                log.warn("记忆相关性命中但 PG 中不存在或非 active，忽略该条 userId={} memoryId={}",
                        userId, memoryId);
                continue;
            }
            if (!seen.add(memoryId)) {
                continue;
            }
            scored.add(new ScoredMemory(memory, hit.score()));
        }
        scored.sort(Comparator.comparingDouble(
                (ScoredMemory scoredMemory) -> scoredMemory.score() * importanceOf(scoredMemory.memory()))
                .reversed());
        return scored;
    }

    /**
     * 组装注入块：常驻在前（≤400 字符，整条计入不切半）、相关在后，
     * 按 memory id 去重，总预算 1200 字符；格式标记与单参版本完全一致。
     */
    private static InjectionParts composeInjection(List<AgentMemoryEntity> residentMemories,
                                                   List<ScoredMemory> relevantMemories) {
        StringBuilder block = new StringBuilder(BLOCK_HEADER);
        List<AgentMemoryEntity> injected = new ArrayList<>();
        Set<Long> usedIds = new LinkedHashSet<>();
        int residentChars = 0;
        int residentCount = 0;
        int relevantCount = 0;
        boolean stop = false;

        for (AgentMemoryEntity memory : residentMemories) {
            String line = formatLine(memory);
            if (residentChars + line.length() > RESIDENT_MEMORY_MAX_CHARS) {
                log.debug("常驻记忆超 400 字符预算，跳过整条 userId={} memoryId={} residentChars={} lineLength={}",
                        memory.getUserId(), memory.getId(), residentChars, line.length());
                continue;
            }
            AppendResult result = tryAppendLine(block, line);
            if (result == AppendResult.SKIPPED) {
                continue;
            }
            residentChars += line.length();
            injected.add(memory);
            usedIds.add(memory.getId());
            residentCount++;
            if (result == AppendResult.TRUNCATED) {
                stop = true;
                break;
            }
        }

        if (!stop) {
            for (ScoredMemory scored : relevantMemories) {
                AgentMemoryEntity memory = scored.memory();
                if (memory.getId() == null || usedIds.contains(memory.getId())) {
                    // 去重：同一 memory id 在常驻块已出现过，只保留一次
                    continue;
                }
                String line = formatLine(memory);
                AppendResult result = tryAppendLine(block, line);
                if (result == AppendResult.SKIPPED) {
                    continue;
                }
                injected.add(memory);
                usedIds.add(memory.getId());
                relevantCount++;
                if (result == AppendResult.TRUNCATED) {
                    break;
                }
            }
        }

        if (injected.isEmpty()) {
            return new InjectionParts(new MemoryInjection("", List.of()), 0, 0);
        }
        block.append(BLOCK_FOOTER);
        return new InjectionParts(
                new MemoryInjection(block.toString(), List.copyOf(injected)),
                residentCount, relevantCount);
    }

    /**
     * 单参版本（也是二参版本的向量失败回退实现）：只按 importance desc 逐条加入，
     * 放不下跳过整条；单条自身超过 1200 才截断并加省略号。
     */
    private static MemoryInjection renderByImportance(List<AgentMemoryEntity> activeMemories) {
        StringBuilder block = new StringBuilder(BLOCK_HEADER);
        List<AgentMemoryEntity> injected = new ArrayList<>();

        for (AgentMemoryEntity memory : activeMemories) {
            String line = formatLine(memory);
            if (line.length() > MEMORY_BLOCK_MAX_CHARS) {
                int room = MEMORY_BLOCK_MAX_CHARS - block.length() - BLOCK_FOOTER.length() - 1;
                if (room > 8) {
                    block.append(line, 0, room).append("…");
                    injected.add(memory);
                }
                break;
            }
            if (block.length() + line.length() + BLOCK_FOOTER.length() > MEMORY_BLOCK_MAX_CHARS) {
                continue;
            }
            block.append(line);
            injected.add(memory);
        }

        if (injected.isEmpty()) {
            return new MemoryInjection("", List.of());
        }
        block.append(BLOCK_FOOTER);
        return new MemoryInjection(block.toString(), List.copyOf(injected));
    }

    /** 预算内追加一行：超总预算跳过（SKIPPED）；单行超预算截断（TRUNCATED）。 */
    private static AppendResult tryAppendLine(StringBuilder block, String line) {
        if (line.length() > MEMORY_BLOCK_MAX_CHARS) {
            int room = MEMORY_BLOCK_MAX_CHARS - block.length() - BLOCK_FOOTER.length() - 1;
            if (room > 8) {
                block.append(line, 0, room).append("…");
            }
            return AppendResult.TRUNCATED;
        }
        if (block.length() + line.length() + BLOCK_FOOTER.length() > MEMORY_BLOCK_MAX_CHARS) {
            return AppendResult.SKIPPED;
        }
        block.append(line);
        return AppendResult.APPENDED;
    }

    private static String formatLine(AgentMemoryEntity memory) {
        return "\n- [" + MemoryTypeRules.label(memory.getMemoryType()) + "] "
                + oneLine(memory.getContent());
    }

    private static short importanceOf(AgentMemoryEntity memory) {
        return memory.getImportance() == null ? 0 : memory.getImportance();
    }

    /** 批量更新被注入记忆的 last_used_at（prepare 事务内）。 */
    public void touchLastUsed(List<AgentMemoryEntity> memories) {
        if (memories.isEmpty()) {
            return;
        }
        Instant now = Instant.now();
        for (AgentMemoryEntity memory : memories) {
            memory.setLastUsedAt(now);
        }
        memoryRepository.saveAll(memories);
    }

    /** 供 prepareUserTurn 调用：事务提交后同步覆盖写显式记忆点（任务五时序）。 */
    public void syncPointAfterCommit(Long userId, AgentMemoryEntity memory) {
        runAfterCommitSafely("写入", userId, memory.getId(), () -> vectorService.upsertPoint(memory));
    }

    // ---------- 内部 ----------

    private AgentMemoryEntity getOwned(Long memoryId, Long userId) {
        return memoryRepository.findById(memoryId)
                .filter(memory -> userId.equals(memory.getUserId()))
                .orElseThrow(() -> ApiException.notFound("记忆不存在或无权访问：" + memoryId));
    }

    private static String normalizeType(String memoryType) {
        if (memoryType == null || memoryType.trim().isBlank()) {
            throw ApiException.badRequest("MEMORY_TYPE_INVALID", "记忆类型不能为空");
        }
        String normalized = memoryType.trim();
        if (!VALID_TYPES.contains(normalized)) {
            throw ApiException.badRequest("MEMORY_TYPE_INVALID",
                    "记忆类型只能是 profile、preference、weakness、goal、task、fact");
        }
        return normalized;
    }

    private static String oneLine(String content) {
        return content.replaceAll("\\s+", " ").trim();
    }

    /**
     * 注册 afterCommit 同步回调（同一线程、非 @Async）：点写删在 PG 事务提交后执行。
     * 外部失败只 warn（带 userId/memoryId），记忆以 PG 为准，不影响调用方。
     */
    private static void runAfterCommitSafely(String action, Long userId, Long memoryId,
                                             Runnable vectorOperation) {
        Runnable guarded = () -> {
            try {
                vectorOperation.run();
            } catch (Exception e) {
                log.warn("长期记忆向量{}失败，记忆以 PG 为准 userId={} memoryId={} error={}",
                        action, userId, memoryId, e.toString());
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    guarded.run();
                }
            });
        } else {
            guarded.run();
        }
    }

    /** 注入结果：拼好的记忆块与实际被注入的记忆。 */
    public record MemoryInjection(String block, List<AgentMemoryEntity> memories) {
    }

    /** P1-6：一条相关命中的记忆及其向量相关分（0~1 余弦相似度）。 */
    private record ScoredMemory(AgentMemoryEntity memory, double score) {
    }

    /** P1-6：组装结果 + 常驻/相关各自实际注入条数（用于日志，不打正文）。 */
    private record InjectionParts(MemoryInjection injection, int residentCount, int relevantCount) {
    }

    /** P1-6：单行追加结果。SKIPPED=超总预算跳过该条；TRUNCATED=单条超预算已截断。 */
    private enum AppendResult {
        APPENDED, SKIPPED, TRUNCATED
    }
}
