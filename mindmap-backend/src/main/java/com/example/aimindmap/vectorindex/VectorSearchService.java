package com.example.aimindmap.vectorindex;

import com.example.aimindmap.metrics.BusinessMetrics;
import io.qdrant.client.ConditionFactory;
import io.qdrant.client.grpc.Common.Filter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;

/**
 * 向量检索服务（方案 §7.5；供阶段 3 工具使用，本阶段不接入聊天）。
 * filter 必含 user_id 等值 + status=active；top 8 → 同源合并 → 收敛 3~5 条；
 * 低于可配置最低分阈值视为未命中。
 *
 * <p>阶段八-1：两个检索入口的 embedding 调用各记一次 {@code aimindmap.embedding.call}
 * 耗时 Timer 与失败计数（operation=search / memory_search，model 取
 * {@code app.qdrant.embedding-model}）。失败计数写进<b>既有</b> catch 分支，
 * 不新增 catch、不改变异常类型与业务行为；tag 不带 userId 与查询正文。
 */
@Service
public class VectorSearchService {

    private static final Logger log = LoggerFactory.getLogger(VectorSearchService.class);

    private final EmbeddingModel embeddingModel;
    private final QdrantVectorGateway gateway;
    private final QdrantProperties properties;
    private final BusinessMetrics businessMetrics;

    public VectorSearchService(EmbeddingModel embeddingModel,
                               QdrantVectorGateway gateway,
                               QdrantProperties properties,
                               BusinessMetrics businessMetrics) {
        this.embeddingModel = embeddingModel;
        this.gateway = gateway;
        this.properties = properties;
        this.businessMetrics = businessMetrics;
    }

    public SearchOutcome search(Long userId, String query, List<VectorResourceType> resourceTypes) {
        List<VectorHit> chunks = retrieveTopChunks(userId, query, resourceTypes);

        // 同源合并：同一 (resourceType, sourceId) 只保留分数最高的 1 个 chunk（每篇 1 chunk，旧行为）
        Map<String, VectorHit> bestBySource = new LinkedHashMap<>();
        for (VectorHit hit : chunks) {
            String key = hit.sourceType() + ":" + hit.sourceId();
            VectorHit existing = bestBySource.get(key);
            if (existing == null || hit.score() > existing.score()) {
                bestBySource.put(key, hit);
            }
        }

        List<VectorHit> hits = new ArrayList<>(bestBySource.values());
        hits.sort(Comparator.comparingDouble(VectorHit::score).reversed());
        if (hits.size() > properties.getSearchMaxResults()) {
            hits = hits.subList(0, properties.getSearchMaxResults());
        }
        if (hits.isEmpty()) {
            log.info("向量检索未命中 userId={} queryLength={} minScore={}",
                    userId, query.length(), properties.getMinScore());
        }
        return new SearchOutcome(List.copyOf(hits), !hits.isEmpty());
    }

    /**
     * P1-5 多查询融合用的“原样 top-K 片段”检索（{@code MultiQueryRetriever} 专用）。
     *
     * <p>与 {@link #search} 的唯一区别：<b>不做同源合并</b>（一篇文档可以有多个 chunk 进入融合），
     * 也<b>不按 {@code search-max-results} 收敛</b>，只按
     * {@code (resourceType, resourceId, chunkIndex)} 去重、过滤 {@code min-score}、按分数降序取前 topK。
     *
     * <p>阈值口径与旧路径完全相同（同一个 {@code app.qdrant.min-score}，本轮不动阈值）；
     * {@link #search} 的行为与 P1-5 之前逐条一致，两个入口共用同一段 embedding/Qdrant 代码。
     */
    public List<VectorHit> searchTopChunks(Long userId, String query,
                                           List<VectorResourceType> resourceTypes, int topK) {
        List<VectorHit> chunks = retrieveTopChunks(userId, query, resourceTypes);
        int limit = Math.max(1, topK);
        if (chunks.size() <= limit) {
            return chunks;
        }
        return List.copyOf(chunks.subList(0, limit));
    }

    /** embedding + Qdrant 检索 + minScore 过滤 + 按片段去重并降序；两个检索入口共用。 */
    private List<VectorHit> retrieveTopChunks(Long userId, String query,
                                              List<VectorResourceType> resourceTypes) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("检索内容不能为空");
        }
        float[] queryVector;
        long embeddingStartedNanos = businessMetrics.startTimer();
        try {
            queryVector = embeddingModel.embed(query);
        } catch (Exception e) {
            businessMetrics.recordEmbeddingFailure(BusinessMetrics.EMBEDDING_OP_SEARCH,
                    properties.getEmbeddingModel(), embeddingStartedNanos);
            throw new VectorIndexExternalException("Embedding 服务调用失败", e);
        }
        businessMetrics.recordEmbeddingSuccess(BusinessMetrics.EMBEDDING_OP_SEARCH,
                properties.getEmbeddingModel(), embeddingStartedNanos);

        Filter filter = buildFilter(userId, resourceTypes);
        List<QdrantVectorGateway.RetrievedPoint> points;
        try {
            points = gateway.search(properties.getCollection(), filter, queryVector, properties.getSearchTopK());
        } catch (Exception e) {
            throw new VectorIndexExternalException("Qdrant 检索失败", e);
        }

        Map<String, VectorHit> bestByIdentity = new LinkedHashMap<>();
        for (QdrantVectorGateway.RetrievedPoint point : points) {
            if (point.score() < properties.getMinScore()) {
                continue;
            }
            VectorHit hit = toVectorHit(point);
            String key = hit.sourceType() + ":" + hit.sourceId() + ":" + hit.chunkIndex();
            VectorHit existing = bestByIdentity.get(key);
            if (existing == null || hit.score() > existing.score()) {
                bestByIdentity.put(key, hit);
            }
        }

        List<VectorHit> hits = new ArrayList<>(bestByIdentity.values());
        hits.sort(Comparator.comparingDouble(VectorHit::score).reversed());
        return List.copyOf(hits);
    }

    /**
     * 长期记忆去重检索（任务六）：仅在该用户 active 记忆点中取 limit 1，
     * 相似度 ≥ 调用方给定阈值时才算命中。
     *
     * <p>P1-E3：实现改为委托 {@link #searchMemoryPoints}（limit=1），
     * 对真实网关（检索返回 ≤1 条）行为与改造前逐条一致；指标 operation 仍为 memory_search。
     */
    public Optional<VectorHit> searchTopMemory(Long userId, String query, double minScore) {
        List<VectorHit> hits = searchMemoryPoints(userId, query, minScore, 1);
        return hits.isEmpty() ? Optional.empty() : Optional.of(hits.get(0));
    }

    /**
     * P1-E3 记忆相关性注入：在该用户 active 记忆点中取相似度 ≥ minScore 的 top-N，按分数降序。
     *
     * <p>与 {@link #searchTopMemory} 共用同一段 embedding/Qdrant 代码，唯一差别是检索条数由
     * 调用方给定（注入侧用 5）；阈值只用于过滤结果，不写回配置。
     */
    public List<VectorHit> searchMemories(Long userId, String query, double minScore, int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("检索条数必须大于 0");
        }
        return searchMemoryPoints(userId, query, minScore, limit);
    }

    /**
     * 记忆点检索：embedding + Qdrant（filter 限该用户 + status=active + resource_type=memory）
     * + minScore 过滤 + 同源去重 + 分数降序 + 收敛 limit。
     * 异常类型与业务行为与改造前的 searchTopMemory 一致（Embedding/Qdrant 失败抛
     * {@link VectorIndexExternalException}），由调用方决定是否降级。
     */
    private List<VectorHit> searchMemoryPoints(Long userId, String query, double minScore, int limit) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("检索内容不能为空");
        }
        float[] queryVector;
        long embeddingStartedNanos = businessMetrics.startTimer();
        try {
            queryVector = embeddingModel.embed(query);
        } catch (Exception e) {
            businessMetrics.recordEmbeddingFailure(BusinessMetrics.EMBEDDING_OP_MEMORY_SEARCH,
                    properties.getEmbeddingModel(), embeddingStartedNanos);
            throw new VectorIndexExternalException("Embedding 服务调用失败", e);
        }
        businessMetrics.recordEmbeddingSuccess(BusinessMetrics.EMBEDDING_OP_MEMORY_SEARCH,
                properties.getEmbeddingModel(), embeddingStartedNanos);
        Filter filter = buildFilter(userId, List.of(VectorResourceType.MEMORY));
        List<QdrantVectorGateway.RetrievedPoint> points;
        try {
            points = gateway.search(properties.getCollection(), filter, queryVector, limit);
        } catch (Exception e) {
            throw new VectorIndexExternalException("Qdrant 检索失败", e);
        }

        Map<String, VectorHit> bestByIdentity = new LinkedHashMap<>();
        for (QdrantVectorGateway.RetrievedPoint point : points) {
            if (point.score() < minScore) {
                continue;
            }
            VectorHit hit = toVectorHit(point);
            String key = hit.sourceType() + ":" + hit.sourceId() + ":" + hit.chunkIndex();
            VectorHit existing = bestByIdentity.get(key);
            if (existing == null || hit.score() > existing.score()) {
                bestByIdentity.put(key, hit);
            }
        }

        List<VectorHit> hits = new ArrayList<>(bestByIdentity.values());
        hits.sort(Comparator.comparingDouble(VectorHit::score).reversed());
        if (hits.size() <= limit) {
            return List.copyOf(hits);
        }
        return List.copyOf(hits.subList(0, limit));
    }

    /** 构造检索 filter：必含 user_id 等值 + status=active，可选 resource_type 集合。 */
    public static Filter buildFilter(Long userId, List<VectorResourceType> resourceTypes) {
        Filter.Builder builder = Filter.newBuilder()
                .addMust(ConditionFactory.matchValues("user_id", List.of(userId)))
                .addMust(ConditionFactory.matchKeyword("status", "active"));
        if (resourceTypes != null && !resourceTypes.isEmpty()) {
            builder.addMust(ConditionFactory.matchKeywords("resource_type",
                    resourceTypes.stream().map(VectorResourceType::wireName).toList()));
        }
        return builder.build();
    }

    @SuppressWarnings("unchecked")
    private static List<String> readStringList(Object value) {
        if (value instanceof List<?> list) {
            List<String> result = new ArrayList<>();
            for (Object element : list) {
                result.add(String.valueOf(element));
            }
            return result;
        }
        return List.of();
    }

    private static VectorHit toVectorHit(QdrantVectorGateway.RetrievedPoint point) {
        Map<String, Object> payload = point.payload();
        return new VectorHit(
                String.valueOf(payload.getOrDefault("resource_type", "")),
                String.valueOf(payload.getOrDefault("source_id", "")),
                ((Number) payload.getOrDefault("chunk_index", 0)).intValue(),
                String.valueOf(payload.getOrDefault("title", "")),
                String.valueOf(payload.getOrDefault("content", "")),
                readStringList(payload.get("source_path")),
                point.score(),
                point.pointId());
    }

    public record VectorHit(
            String sourceType,
            String sourceId,
            int chunkIndex,
            String title,
            String content,
            List<String> sourcePath,
            double score,
            UUID pointId) {
    }

    public record SearchOutcome(List<VectorHit> hits, boolean matched) {
    }
}
