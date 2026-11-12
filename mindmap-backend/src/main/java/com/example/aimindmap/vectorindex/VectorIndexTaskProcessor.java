package com.example.aimindmap.vectorindex;

import com.example.aimindmap.agentmemory.MemoryVectorService;
import com.example.aimindmap.document.DocumentEntity;
import com.example.aimindmap.document.DocumentRepository;
import com.example.aimindmap.metrics.BusinessMetrics;
import com.example.aimindmap.mindmap.GraphJsonCodec;
import com.example.aimindmap.mindmap.MindMapEntity;
import com.example.aimindmap.mindmap.MindMapRepository;
import com.example.aimindmap.mindmap.graph.GraphIndex;
import com.example.aimindmap.mindmap.graph.MindMapGraph;
import com.example.aimindmap.mindmap.graph.MindMapNode;
import com.example.aimindmap.question.QuestionEntity;
import com.example.aimindmap.question.QuestionRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Component;

/**
 * 单条向量任务的外部处理：加载最新数据 → 向量化 → 写/删 Qdrant。
 *
 * <p>阶段八-1：{@code processUpsert} 的 embedding 调用记 {@code aimindmap.embedding.call}
 * 耗时 Timer 与失败计数（operation=index，model 取 {@code app.qdrant.embedding-model}）。
 * 失败计数写进<b>既有</b> catch 分支，不新增 catch、不改变异常类型与业务行为；
 * 内容哈希未变化的 Skipped 分支根本不调 embedding，因此不产生指标。
 */
@Component
public class VectorIndexTaskProcessor {

    private static final Logger log = LoggerFactory.getLogger(VectorIndexTaskProcessor.class);

    private final EmbeddingModel embeddingModel;
    private final QdrantVectorGateway gateway;
    private final VectorContentBuilder contentBuilder;
    private final VectorIndexEntryRepository entryRepository;
    private final DocumentRepository documentRepository;
    private final MindMapRepository mindMapRepository;
    private final QuestionRepository questionRepository;
    private final GraphJsonCodec graphJsonCodec;
    private final QdrantProperties properties;
    private final MemoryVectorService memoryVectorService;
    private final BusinessMetrics businessMetrics;
    private final MarkdownChunker chunker = new MarkdownChunker();

    public VectorIndexTaskProcessor(EmbeddingModel embeddingModel,
                                    QdrantVectorGateway gateway,
                                    VectorContentBuilder contentBuilder,
                                    VectorIndexEntryRepository entryRepository,
                                    DocumentRepository documentRepository,
                                    MindMapRepository mindMapRepository,
                                   QuestionRepository questionRepository,
                                   GraphJsonCodec graphJsonCodec,
                                    QdrantProperties properties,
                                     MemoryVectorService memoryVectorService,
                                     BusinessMetrics businessMetrics) {
        this.embeddingModel = embeddingModel;
        this.gateway = gateway;
        this.contentBuilder = contentBuilder;
        this.entryRepository = entryRepository;
        this.documentRepository = documentRepository;
        this.mindMapRepository = mindMapRepository;
        this.questionRepository = questionRepository;
        this.graphJsonCodec = graphJsonCodec;
        this.properties = properties;
        this.memoryVectorService = memoryVectorService;
        this.businessMetrics = businessMetrics;
    }

    /** 处理一条任务，返回成功结果；外部失败抛 {@link VectorIndexExternalException}。 */
    public ProcessResult process(VectorIndexTaskService.TaskLoad task) {
        if (task.resourceType() == VectorResourceType.MEMORY) {
            return memoryVectorService.processTask(task);
        }
        return switch (task.operation()) {
            case UPSERT -> processUpsert(task);
            case DELETE -> processDelete(task);
        };
    }

    private ProcessResult processUpsert(VectorIndexTaskService.TaskLoad task) {
        VectorContentBuilder.VectorItem item = loadItem(task);
        String hash = sha256(item.content());

        String existingHash = entryRepository.findByResourceTypeAndResourceIdAndChunkIndex(
                        task.resourceType().wireName(), task.resourceId(), task.chunkIndex())
                .filter(entry -> VectorEntryStatus.ACTIVE.name().equals(entry.getStatus()))
                .map(VectorIndexEntryEntity::getContentHash)
                .orElse(null);
        if (hash.equals(existingHash)) {
            log.debug("内容未变化，跳过向量化 taskId={} resourceId={}", task.taskId(), task.resourceId());
            return new ProcessResult.Skipped(hash);
        }

        float[] vector;
        long embeddingStartedNanos = businessMetrics.startTimer();
        try {
            vector = embeddingModel.embed(item.content());
        } catch (Exception e) {
            businessMetrics.recordEmbeddingFailure(BusinessMetrics.EMBEDDING_OP_INDEX,
                    properties.getEmbeddingModel(), embeddingStartedNanos);
            throw new VectorIndexExternalException("Embedding 服务调用失败", e);
        }
        businessMetrics.recordEmbeddingSuccess(BusinessMetrics.EMBEDDING_OP_INDEX,
                properties.getEmbeddingModel(), embeddingStartedNanos);

        UUID pointId = VectorPointIds.pointId(task.resourceType(), task.resourceId(), task.chunkIndex());
        VectorPointData data = new VectorPointData(
                task.userId(),
                task.resourceType().wireName(),
                sourceId(task),
                task.chunkIndex(),
                item.title(),
                item.content(),
                "active",
                item.contentVersion(),
                item.sourcePath(),
                properties.getEmbeddingModel(),
                Instant.now());
        try {
            gateway.upsert(properties.getCollection(),
                    new QdrantVectorGateway.VectorPoint(pointId, vector, data));
        } catch (Exception e) {
            throw new VectorIndexExternalException("Qdrant 写入失败", e);
        }
        return new ProcessResult.UpsertSucceeded(pointId, hash);
    }

    private ProcessResult processDelete(VectorIndexTaskService.TaskLoad task) {
        UUID pointId = VectorPointIds.pointId(task.resourceType(), task.resourceId(), task.chunkIndex());
        try {
            gateway.delete(properties.getCollection(), pointId);
        } catch (Exception e) {
            throw new VectorIndexExternalException("Qdrant 删除失败", e);
        }
        return new ProcessResult.ProcessDeleteSucceeded();
    }

    private VectorContentBuilder.VectorItem loadItem(VectorIndexTaskService.TaskLoad task) {
        return switch (task.resourceType()) {
            case DOCUMENT_CHUNK -> loadDocumentChunk(task);
            case MINDMAP_NODE -> loadMindmapNode(task);
            case QUESTION -> loadQuestion(task);
            default -> throw new VectorIndexPermanentException(
                    "不支持的资料类型 taskId=" + task.taskId());
        };
    }

    private VectorContentBuilder.VectorItem loadDocumentChunk(VectorIndexTaskService.TaskLoad task) {
        Long documentId = Long.valueOf(task.resourceId());
        DocumentEntity document = documentRepository.findById(documentId)
                .orElseThrow(() -> new VectorIndexPermanentException(
                        "笔记已不存在，无法向量化 documentId=" + documentId));
        if (!"active".equals(document.getStatus())) {
            throw new VectorIndexPermanentException(
                    "笔记已删除，无法向量化 documentId=" + documentId);
        }
        List<MarkdownChunker.Chunk> chunks = chunker.chunk(document.getContent());
        MarkdownChunker.Chunk matching = chunks.stream()
                .filter(chunk -> chunk.chunkIndex() == task.chunkIndex())
                .findFirst()
                .orElseThrow(() -> new VectorIndexPermanentException(
                        "笔记片段已不存在 documentId=" + documentId
                                + " chunkIndex=" + task.chunkIndex()));
        return contentBuilder.documentChunk(document.getTitle(), document.getContentVersion(), matching);
    }

    private VectorContentBuilder.VectorItem loadMindmapNode(VectorIndexTaskService.TaskLoad task) {
        ParsedNodeResource parsed = parseNodeResource(task.resourceId());
        MindMapEntity mindMap = mindMapRepository.findById(parsed.mindmapId)
                .orElseThrow(() -> new VectorIndexPermanentException(
                        "导图已不存在 mindmapId=" + parsed.mindmapId));
        MindMapGraph graph = graphJsonCodec.read(mindMap.getGraph());
        MindMapNode node = GraphIndex.of(graph).findById(parsed.nodeId);
        if (node == null) {
            throw new VectorIndexPermanentException(
                    "导图节点已不存在 mindmapId=" + parsed.mindmapId
                            + " nodeId=" + parsed.nodeId);
        }
        return contentBuilder.mindmapNode(node);
    }

    private VectorContentBuilder.VectorItem loadQuestion(VectorIndexTaskService.TaskLoad task) {
        Long questionId = Long.valueOf(task.resourceId());
        QuestionEntity question = questionRepository.findById(questionId)
                .orElseThrow(() -> new VectorIndexPermanentException(
                        "题目已不存在 questionId=" + questionId));

        List<String> relatedTitles = new ArrayList<>();
        MindMapEntity mindMap = mindMapRepository.findById(question.getMindmapId()).orElse(null);
        if (mindMap != null) {
            GraphIndex graphIndex = GraphIndex.of(graphJsonCodec.read(mindMap.getGraph()));
            for (String nodeId : contentBuilder.readNodeIds(question)) {
                MindMapNode node = graphIndex.findById(nodeId);
                if (node != null) {
                    relatedTitles.add(node.getLabel());
                }
            }
        }
        VectorContentBuilder.QuestionView view = contentBuilder.toQuestionView(question, relatedTitles);
        return contentBuilder.question(view);
    }

    private String sourceId(VectorIndexTaskService.TaskLoad task) {
        return switch (task.resourceType()) {
            case MINDMAP_NODE -> parseNodeResource(task.resourceId()).nodeId;
            default -> task.resourceId();
        };
    }

    private static ParsedNodeResource parseNodeResource(String resourceId) {
        int separator = resourceId.indexOf(':');
        if (separator <= 0 || separator == resourceId.length() - 1) {
            throw new VectorIndexPermanentException("导图节点资源 ID 格式非法：" + resourceId);
        }
        return new ParsedNodeResource(
                Long.valueOf(resourceId.substring(0, separator)),
                resourceId.substring(separator + 1));
    }

    private static String sha256(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 计算失败", e);
        }
    }

    private record ParsedNodeResource(Long mindmapId, String nodeId) {
    }

    /** 处理成功结果。 */
    public sealed interface ProcessResult {
        record UpsertSucceeded(UUID pointId, String contentHash) implements ProcessResult {
        }

        record ProcessDeleteSucceeded() implements ProcessResult {
        }

        record Skipped(String contentHash) implements ProcessResult {
        }
    }
}
