package com.example.aimindmap.agentmemory;

import com.example.aimindmap.ai.AiProperties;
import com.example.aimindmap.metrics.BusinessMetrics;
import com.example.aimindmap.vectorindex.QdrantProperties;
import com.example.aimindmap.vectorindex.QdrantVectorGateway;
import com.example.aimindmap.vectorindex.VectorIndexExternalException;
import com.example.aimindmap.vectorindex.VectorIndexPermanentException;
import com.example.aimindmap.vectorindex.VectorIndexTaskProcessor;
import com.example.aimindmap.vectorindex.VectorIndexTaskService;
import com.example.aimindmap.vectorindex.VectorPointIds;
import com.example.aimindmap.vectorindex.VectorResourceType;
import com.example.aimindmap.vectorindex.VectorSearchService;
import com.example.aimindmap.vectorindex.VectorPointData;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Service;

/**
 * 记忆向量与外部调用层（任务四/五/六）。
 * 只在 PG 事务外被调用：去重检索/LLM 合并由 prepareUserMessage 事务外调用；
 * 点的写删由各 Service 注册的 afterCommit 同步回调触发，不放进数据库长事务。
 *
 * <p>阶段八-1：{@link #upsertPoint} 的 embedding 调用记 {@code aimindmap.embedding.call}
 * 耗时 Timer 与失败计数（operation=memory_upsert，model 取 {@code app.qdrant.embedding-model}）。
 * 失败计数写进<b>既有</b> catch 分支，不新增 catch、不改变异常类型与业务行为。
 */
@Service
public class MemoryVectorService {

    private static final Logger log = LoggerFactory.getLogger(MemoryVectorService.class);
    private static final int MERGE_MAX_TOKENS = 300;

    private final AgentMemoryRepository memoryRepository;
    private final EmbeddingModel embeddingModel;
    private final QdrantVectorGateway gateway;
    private final QdrantProperties qdrantProperties;
    private final AgentMemoryProperties memoryProperties;
    private final AiProperties aiProperties;
    private final ChatModel chatModel;
    private final VectorSearchService vectorSearchService;
    private final BusinessMetrics businessMetrics;

    public MemoryVectorService(AgentMemoryRepository memoryRepository,
                               EmbeddingModel embeddingModel,
                               QdrantVectorGateway gateway,
                               QdrantProperties qdrantProperties,
                               AgentMemoryProperties memoryProperties,
                               AiProperties aiProperties,
                               ChatModel chatModel,
                               VectorSearchService vectorSearchService,
                               BusinessMetrics businessMetrics) {
        this.memoryRepository = memoryRepository;
        this.embeddingModel = embeddingModel;
        this.gateway = gateway;
        this.qdrantProperties = qdrantProperties;
        this.memoryProperties = memoryProperties;
        this.aiProperties = aiProperties;
        this.chatModel = chatModel;
        this.vectorSearchService = vectorSearchService;
        this.businessMetrics = businessMetrics;
    }

    // ---------- 事务外：去重决策 ----------

    /** 显式记忆落库前决定新建还是合并（任务六）；任何检索失败降级为新建。 */
    public MemoryDecision decideExplicitMemory(Long userId, String memoryType, String content) {
        Optional<VectorSearchService.VectorHit> existing;
        try {
            existing = vectorSearchService.searchTopMemory(
                    userId, content, memoryProperties.getDedupMinScore());
        } catch (Exception e) {
            log.warn("显式记忆去重检索失败，降级为新建记忆 userId={} error={}", userId, e.toString());
            return new MemoryDecision.Create(memoryType, content);
        }
        if (existing.isEmpty()) {
            return new MemoryDecision.Create(memoryType, content);
        }

        VectorSearchService.VectorHit hit = existing.get();
        final Long oldMemoryId;
        try {
            oldMemoryId = Long.valueOf(hit.sourceId());
        } catch (NumberFormatException e) {
            log.warn("记忆去重命中但来源 ID 非法，降级为新建 userId={} sourceId={}",
                    userId, hit.sourceId());
            return new MemoryDecision.Create(memoryType, content);
        }

        try {
            String merged = mergeWithLlm(extractPayloadText(hit.content()), content);
            if (merged.isBlank()) {
                log.warn("记忆合并返回空内容，直接用新信息覆盖旧记忆 userId={} memoryId={}",
                        userId, oldMemoryId);
                return new MemoryDecision.Merge(oldMemoryId, content);
            }
            return new MemoryDecision.Merge(oldMemoryId, merged);
        } catch (Exception e) {
            log.warn("记忆合并 LLM 调用失败，直接用新信息覆盖旧记忆 userId={} memoryId={} error={}",
                    userId, oldMemoryId, e.toString());
            return new MemoryDecision.Merge(oldMemoryId, content);
        }
    }

    // ---------- 事务外：注入相关性检索 ----------

    /**
     * P1-E3：记忆相关性注入用的 top-N 检索（只读，不写库、不改记忆状态）。
     *
     * <p>既有 {@link VectorSearchService#searchTopMemory} 只返回 1 条（去重场景语义），
     * 注入侧需要 top 5，因此新增本方法并复用同一段检索代码；阈值/条数由调用方给。
     * 失败时抛 {@link VectorIndexExternalException}，由 AgentMemoryService 决定降级。
     */
    public List<VectorSearchService.VectorHit> searchMemories(
            Long userId, String query, double minScore, int limit) {
        return vectorSearchService.searchMemories(userId, query, minScore, limit);
    }

    // ---------- afterCommit 同步：点写删 ----------

    /** 覆盖写一条 active 记忆点（同 point id）。外部失败抛 VectorIndexExternalException。 */
    public void upsertPoint(AgentMemoryEntity memory) {
        String vectorText =
                MemoryTypeRules.vectorContent(memory.getMemoryType(), memory.getContent());
        float[] vector;
        long embeddingStartedNanos = businessMetrics.startTimer();
        try {
            vector = embeddingModel.embed(vectorText);
        } catch (Exception e) {
            businessMetrics.recordEmbeddingFailure(BusinessMetrics.EMBEDDING_OP_MEMORY_UPSERT,
                    qdrantProperties.getEmbeddingModel(), embeddingStartedNanos);
            throw new VectorIndexExternalException("Embedding 服务调用失败", e);
        }
        businessMetrics.recordEmbeddingSuccess(BusinessMetrics.EMBEDDING_OP_MEMORY_UPSERT,
                qdrantProperties.getEmbeddingModel(), embeddingStartedNanos);

        int version = resolveContentVersion(memory.getId());
        VectorPointData data = new VectorPointData(
                memory.getUserId(),
                VectorResourceType.MEMORY.wireName(),
                String.valueOf(memory.getId()),
                0,
                pointTitle(memory.getContent()),
                vectorText,
                "active",
                version,
                List.of(),
                qdrantProperties.getEmbeddingModel(),
                Instant.now());
        UUID pointId = memoryPointId(memory.getId());
        try {
            gateway.upsert(qdrantProperties.getCollection(),
                    new QdrantVectorGateway.VectorPoint(pointId, vector, data));
        } catch (Exception e) {
            throw new VectorIndexExternalException("Qdrant 写入失败", e);
        }
    }

    public void deletePoint(Long memoryId) {
        try {
            gateway.delete(qdrantProperties.getCollection(), memoryPointId(memoryId));
        } catch (Exception e) {
            throw new VectorIndexExternalException("Qdrant 删除失败", e);
        }
    }

    /** 用户级记忆重建后清理残留点：PG 中已不存在或非 active 的 memory 点。 */
    public void cleanupOrphanPoints(Long userId, List<Long> activeIds) {
        List<UUID> existing;
        try {
            existing = gateway.listPointIds(qdrantProperties.getCollection(),
                    VectorSearchService.buildFilter(userId, List.of(VectorResourceType.MEMORY)));
        } catch (Exception e) {
            log.warn("记忆残留点枚举失败，跳过本次清理 userId={} error={}", userId, e.toString());
            return;
        }
        Set<UUID> expected = activeIds.stream()
                .map(MemoryVectorService::memoryPointId)
                .collect(Collectors.toSet());
        for (UUID pointId : existing) {
            if (expected.contains(pointId)) {
                continue;
            }
            try {
                gateway.delete(qdrantProperties.getCollection(), pointId);
                log.info("已清理记忆残留点 userId={} pointId={}", userId, pointId);
            } catch (Exception e) {
                log.warn("记忆残留点删除失败 userId={} pointId={} error={}",
                        userId, pointId, e.toString());
            }
        }
    }

    // ---------- 任务队列：memory 任务处理 ----------

    /** 供 VectorIndexTaskProcessor 委托处理 MEMORY 类型任务（rebuild 链路）。 */
    public VectorIndexTaskProcessor.ProcessResult processTask(VectorIndexTaskService.TaskLoad task) {
        long memoryId = Long.parseLong(task.resourceId());
        return switch (task.operation()) {
            case UPSERT -> {
                AgentMemoryEntity memory = memoryRepository.findById(memoryId).orElse(null);
                if (memory == null
                        || !task.userId().equals(memory.getUserId())
                        || !"active".equals(memory.getStatus())) {
                    throw new VectorIndexPermanentException(
                            "记忆不存在或非 active，无法向量化 memoryId=" + memoryId);
                }
                String vectorText =
                        MemoryTypeRules.vectorContent(memory.getMemoryType(), memory.getContent());
                upsertPoint(memory);
                yield new VectorIndexTaskProcessor.ProcessResult.UpsertSucceeded(
                        memoryPointId(memoryId), sha256(vectorText));
            }
            case DELETE -> {
                deletePoint(memoryId);
                yield new VectorIndexTaskProcessor.ProcessResult.ProcessDeleteSucceeded();
            }
        };
    }

    // ---------- 内部 ----------

    private String mergeWithLlm(String oldContent, String newContent) {
        SystemMessage systemMessage = new SystemMessage("""
                你是长期记忆合并助手。两条记忆表达同一事实，请合并成一条简洁且信息完整的中文记忆：
                1. 保留两条记忆中的全部有效信息，去除重复；
                2. 不超过 200 字；
                3. 只输出记忆正文，不要标题、前缀或解释。
                """);
        UserMessage userMessage = new UserMessage(
                "已有记忆：" + oldContent + "\n新信息：" + newContent);
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(aiProperties.getModel())
                .temperature(aiProperties.getTemperature())
                .maxTokens(MERGE_MAX_TOKENS)
                .build();
        ChatResponse response =
                chatModel.call(new Prompt(List.of(systemMessage, userMessage), options));
        if (response == null || response.getResult() == null
                || response.getResult().getOutput() == null) {
            return "";
        }
        String text = response.getResult().getOutput().getText();
        return text == null ? "" : text.trim();
    }

    /** 检索点 content 形如"记忆类型：…\n记忆内容：…"，取出旧记忆正文。 */
    private static String extractPayloadText(String vectorContent) {
        int marker = vectorContent.indexOf("记忆内容：");
        return marker >= 0
                ? vectorContent.substring(marker + "记忆内容：".length())
                : vectorContent;
    }

    private static String pointTitle(String content) {
        String title = content.replaceAll("\\s+", " ").trim();
        return title.length() > 40 ? title.substring(0, 40) : title;
    }

    private int resolveContentVersion(Long memoryId) {
        try {
            Optional<Map<String, Object>> payload =
                    gateway.readPayload(qdrantProperties.getCollection(), memoryPointId(memoryId));
            if (payload.isPresent()
                    && payload.get().get("content_version") instanceof Number version) {
                return version.intValue() + 1;
            }
        } catch (Exception e) {
            log.warn("读取记忆 content_version 失败，按新版本处理 memoryId={} error={}",
                    memoryId, e.toString());
        }
        return 1;
    }

    static UUID memoryPointId(Long memoryId) {
        return VectorPointIds.pointId(
                VectorResourceType.MEMORY, String.valueOf(memoryId), 0);
    }

    private static String sha256(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 计算失败", e);
        }
    }
}
