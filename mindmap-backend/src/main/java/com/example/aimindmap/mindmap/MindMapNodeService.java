package com.example.aimindmap.mindmap;

import com.example.aimindmap.common.error.ApiException;
import com.example.aimindmap.document.DocumentEntity;
import com.example.aimindmap.document.DocumentRepository;
import com.example.aimindmap.mindmap.NodeEditDtos.AddNodeRequest;
import com.example.aimindmap.mindmap.NodeEditDtos.NodeActionResponse;
import com.example.aimindmap.mindmap.NodeEditDtos.ResetLayoutRequest;
import com.example.aimindmap.mindmap.NodeEditDtos.UpdateNodePositionRequest;
import com.example.aimindmap.mindmap.NodeEditDtos.UpdateNodeRequest;
import com.example.aimindmap.mindmap.graph.GraphIndex;
import com.example.aimindmap.mindmap.graph.MindMapGraph;
import com.example.aimindmap.mindmap.graph.MindMapNode;
import com.example.aimindmap.mindmap.graph.NodeLayout;
import com.example.aimindmap.parser.BlockType;
import com.example.aimindmap.parser.MarkdownBlock;
import com.example.aimindmap.parser.MarkdownParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import com.example.aimindmap.vectorindex.VectorIndexTaskService;

/**
 * v1.1 §11.4 思维导图手动编辑：新增子节点、修改标题/摘要、删除节点。
 * 2026-09-13 修订：手动新增节点自动回写 Markdown；节点坐标可自由拖拽保存。
 * 动画/同步进行中一律拒绝，前后端双重锁定。
 */
@Service
public class MindMapNodeService {

    private static final int MAX_LABEL_LENGTH = MindMapOperationValidator.MAX_LABEL_LENGTH;
    private static final int MAX_SUMMARY_LENGTH = MindMapOperationValidator.MAX_SUMMARY_LENGTH;
    private static final int MAX_DEPTH = MindMapOperationValidator.MAX_DEPTH;
    private static final double MAX_COORDINATE = 10_000d;

    private final MindMapRepository mindMapRepository;
    private final DocumentRepository documentRepository;
    private final SyncJobRepository syncJobRepository;
    private final BlockSnapshotRepository blockSnapshotRepository;
    private final GraphJsonCodec graphJsonCodec;
    private final DocumentLockRegistry lockRegistry;
    private final MarkdownParser markdownParser;
    private final ManualNodeMarkdownWriter markdownWriter;
    private final ObjectMapper objectMapper;
    private final VectorIndexTaskService vectorIndexTaskService;

    public MindMapNodeService(MindMapRepository mindMapRepository,
                              DocumentRepository documentRepository,
                              SyncJobRepository syncJobRepository,
                              BlockSnapshotRepository blockSnapshotRepository,
                              GraphJsonCodec graphJsonCodec,
                              DocumentLockRegistry lockRegistry,
                              MarkdownParser markdownParser,
                              ManualNodeMarkdownWriter markdownWriter,
                              ObjectMapper objectMapper,
                              VectorIndexTaskService vectorIndexTaskService) {
        this.mindMapRepository = mindMapRepository;
        this.documentRepository = documentRepository;
        this.syncJobRepository = syncJobRepository;
        this.blockSnapshotRepository = blockSnapshotRepository;
        this.graphJsonCodec = graphJsonCodec;
        this.lockRegistry = lockRegistry;
        this.markdownParser = markdownParser;
        this.markdownWriter = markdownWriter;
        this.objectMapper = objectMapper;
        this.vectorIndexTaskService = vectorIndexTaskService;
    }

    @Transactional
    public NodeActionResponse addNode(Long mindmapId, AddNodeRequest request) {
        MindMapEntity mindMap = getMindMapOrThrow(mindmapId);
        synchronized (lockRegistry.lockFor(mindMap.getDocumentId())) {
            requireNoActiveJob(mindMap.getDocumentId());
            DocumentEntity document = getDocumentOrThrow(mindMap.getDocumentId());
            MindMapGraph graph = requireReadyGraph(mindMap);
            requireGraphVersion(mindMap, request.expectedGraphVersion());
            requireValidLabel(request.label());
            String summary = normalizeSummary(request.summary());

            GraphIndex index = GraphIndex.of(graph);
            MindMapNode parent = index.findById(request.parentId());
            if (parent == null) {
                throw ApiException.notFound("父节点不存在：" + request.parentId());
            }
            if (index.depth(request.parentId()) + 1 > MAX_DEPTH) {
                throw ApiException.badRequest("VALIDATION_FAILED", "节点层级超过 6 层（§9.4）");
            }

            List<MarkdownBlock> oldBlocks = markdownParser.parse(document.getContent());
            String nodeId = NodeIdGenerator.basedOn(graph).next();
            MindMapNode node = new MindMapNode(nodeId, request.label().trim(), summary, "manual",
                    null, null, expectedHeadingPath(graph, request.parentId()), Instant.now());
            node.setManualLocked(true);
            parent.getChildren().add(node);

            ManualNodeMarkdownWriter.WriteResult writeResult = markdownWriter.insertManualNode(
                    document.getContent(), graph, request.parentId(), request.label().trim(),
                    summary, oldBlocks);
            List<MarkdownBlock> newBlocks = markdownParser.parse(writeResult.markdown());
            MarkdownBlock sourceBlock = findInsertedBlock(newBlocks, request.label().trim(),
                    node.getSourcePath());
            node.setSourceBlockId(sourceBlock.blockId());
            node.setSourceAnchor(sourceBlock.anchor());
            node.setSourcePath(List.copyOf(sourceBlock.headingPath()));

            document.setContent(writeResult.markdown());
            document.setContentVersion(document.getContentVersion() + 1);
            document.touch();
            documentRepository.save(document);
            replaceSnapshots(document.getId(), document.getContentVersion(), newBlocks);

            vectorIndexTaskService.enqueueNodeUpsert(mindMap.getUserId(), mindMap.getId(), nodeId);
            vectorIndexTaskService.enqueueDocument(mindMap.getUserId(), document.getId());
            return finishWithDocument(mindMap, graph, document);
        }
    }

    @Transactional
    public NodeActionResponse updateNode(Long mindmapId, String nodeId, UpdateNodeRequest request) {
        MindMapEntity mindMap = getMindMapOrThrow(mindmapId);
        synchronized (lockRegistry.lockFor(mindMap.getDocumentId())) {
            requireNoActiveJob(mindMap.getDocumentId());
            MindMapGraph graph = requireReadyGraph(mindMap);
            requireGraphVersion(mindMap, request.expectedGraphVersion());
            requireValidLabel(request.label());

            GraphIndex index = GraphIndex.of(graph);
            MindMapNode node = index.findById(nodeId);
            if (node == null) {
                throw ApiException.notFound("节点不存在：" + nodeId);
            }
            node.setLabel(request.label().trim());
            node.setSummary(normalizeSummary(request.summary()));
            node.setManuallyEdited(true);
            node.setUpdatedAt(Instant.now());

            vectorIndexTaskService.enqueueNodeUpsert(mindMap.getUserId(), mindMap.getId(), nodeId);
            return finish(mindMap, graph);
        }
    }

    /** §11.4 接受 / 拒绝删除建议（propose_remove_source_node 的后续）。 */
    @Transactional
    public NodeActionResponse decideRemoval(Long mindmapId,
                                            String nodeId,
                                            NodeEditDtos.RemovalDecisionRequest request) {
        MindMapEntity mindMap = getMindMapOrThrow(mindmapId);
        synchronized (lockRegistry.lockFor(mindMap.getDocumentId())) {
            requireNoActiveJob(mindMap.getDocumentId());
            MindMapGraph graph = requireReadyGraph(mindMap);
            requireGraphVersion(mindMap, request.expectedGraphVersion());

            String decision = request.decision() == null ? "" : request.decision().trim();
            if (!"accept".equals(decision) && !"keep".equals(decision)) {
                throw ApiException.badRequest("VALIDATION_ERROR", "decision 只能是 accept 或 keep");
            }
            GraphIndex index = GraphIndex.of(graph);
            MindMapNode node = index.findById(nodeId);
            if (node == null) {
                throw ApiException.notFound("节点不存在：" + nodeId);
            }

            if (!"removed_suggested".equals(node.getStatus())) {
                return NodeActionResponse.graphOnly(graph.getVersion(), graph);
            }

            if ("accept".equals(decision)) {
                if (graph.getRootId() != null && graph.getRootId().equals(nodeId)) {
                    throw ApiException.badRequest("ROOT_NODE_PROTECTED", "根节点不允许删除");
                }
                MindMapNode parent = index.findParent(nodeId);
                if (parent == null) {
                    throw ApiException.badRequest("ROOT_NODE_PROTECTED", "根节点不允许删除");
                }
                parent.getChildren().removeIf(child -> child.getId().equals(nodeId));
                vectorIndexTaskService.enqueueNodeDelete(mindMap.getUserId(), mindMap.getId(), nodeId);
            } else {
                node.setStatus("active");
                node.setUpdatedAt(Instant.now());
                vectorIndexTaskService.enqueueNodeUpsert(mindMap.getUserId(), mindMap.getId(), nodeId);
            }
            return finish(mindMap, graph);
        }
    }

    @Transactional
    public NodeActionResponse deleteNode(Long mindmapId, String nodeId, Integer expectedGraphVersion) {
        MindMapEntity mindMap = getMindMapOrThrow(mindmapId);
        synchronized (lockRegistry.lockFor(mindMap.getDocumentId())) {
            requireNoActiveJob(mindMap.getDocumentId());
            MindMapGraph graph = requireReadyGraph(mindMap);
            requireGraphVersion(mindMap, expectedGraphVersion);

            if (graph.getRootId() != null && graph.getRootId().equals(nodeId)) {
                throw ApiException.badRequest("ROOT_NODE_PROTECTED", "根节点不允许删除");
            }
            GraphIndex index = GraphIndex.of(graph);
            MindMapNode node = index.findById(nodeId);
            if (node == null) {
                throw ApiException.notFound("节点不存在：" + nodeId);
            }
            MindMapNode parent = index.findParent(nodeId);
            if (parent == null) {
                throw ApiException.badRequest("ROOT_NODE_PROTECTED", "根节点不允许删除");
            }
            parent.getChildren().removeIf(child -> child.getId().equals(nodeId));

            vectorIndexTaskService.enqueueNodeDelete(mindMap.getUserId(), mindMap.getId(), nodeId);
            return finish(mindMap, graph);
        }
    }

    @Transactional
    public NodeActionResponse updatePosition(Long mindmapId,
                                             String nodeId,
                                             UpdateNodePositionRequest request) {
        MindMapEntity mindMap = getMindMapOrThrow(mindmapId);
        synchronized (lockRegistry.lockFor(mindMap.getDocumentId())) {
            requireNoActiveJob(mindMap.getDocumentId());
            MindMapGraph graph = requireReadyGraph(mindMap);
            requireGraphVersion(mindMap, request.expectedGraphVersion());
            if (request.x() == null || request.y() == null
                    || !Double.isFinite(request.x()) || !Double.isFinite(request.y())
                    || Math.abs(request.x()) > MAX_COORDINATE || Math.abs(request.y()) > MAX_COORDINATE) {
                throw ApiException.badRequest("VALIDATION_ERROR", "节点坐标无效");
            }
            MindMapNode node = GraphIndex.of(graph).findById(nodeId);
            if (node == null) {
                throw ApiException.notFound("节点不存在：" + nodeId);
            }
            node.setLayout(new NodeLayout(request.x(), request.y(), true));
            persistDisplayLayout(mindMap, graph);
            return NodeActionResponse.graphOnly(graph.getVersion(), graph);
        }
    }

    @Transactional
    public NodeActionResponse resetLayout(Long mindmapId, ResetLayoutRequest request) {
        MindMapEntity mindMap = getMindMapOrThrow(mindmapId);
        synchronized (lockRegistry.lockFor(mindMap.getDocumentId())) {
            requireNoActiveJob(mindMap.getDocumentId());
            MindMapGraph graph = requireReadyGraph(mindMap);
            requireGraphVersion(mindMap, request.expectedGraphVersion());
            clearLayout(graph.getTree());
            persistDisplayLayout(mindMap, graph);
            return NodeActionResponse.graphOnly(graph.getVersion(), graph);
        }
    }

    private NodeActionResponse finish(MindMapEntity mindMap, MindMapGraph graph) {
        graph.setVersion(graph.getVersion() + 1);
        graph.setUpdatedAt(Instant.now());
        mindMap.setGraph(graphJsonCodec.write(graph));
        mindMap.setGraphVersion(graph.getVersion());
        mindMap.setUpdatedAt(Instant.now());
        mindMapRepository.save(mindMap);
        return NodeActionResponse.graphOnly(graph.getVersion(), graph);
    }

    private NodeActionResponse finishWithDocument(MindMapEntity mindMap,
                                                  MindMapGraph graph,
                                                  DocumentEntity document) {
        graph.setVersion(graph.getVersion() + 1);
        graph.setUpdatedAt(Instant.now());
        mindMap.setGraph(graphJsonCodec.write(graph));
        mindMap.setGraphVersion(graph.getVersion());
        mindMap.setUpdatedAt(Instant.now());
        mindMapRepository.save(mindMap);
        return new NodeActionResponse(graph.getVersion(), graph,
                document.getContent(), document.getContentVersion());
    }

    private void persistDisplayLayout(MindMapEntity mindMap, MindMapGraph graph) {
        graph.setUpdatedAt(Instant.now());
        mindMap.setGraph(graphJsonCodec.write(graph));
        mindMap.setUpdatedAt(Instant.now());
        mindMapRepository.save(mindMap);
    }

    private void clearLayout(MindMapNode node) {
        if (node == null) {
            return;
        }
        node.setLayout(null);
        node.getChildren().forEach(this::clearLayout);
    }

    private List<String> expectedHeadingPath(MindMapGraph graph, String parentId) {
        List<MindMapNode> chain = findChain(graph.getTree(), parentId, new ArrayList<>());
        for (int i = chain.size() - 1; i >= 0; i--) {
            MindMapNode node = chain.get(i);
            if (node.getSourceAnchor() != null && node.getSourceAnchor().contains("#heading(")) {
                List<String> path = new ArrayList<>(node.getSourcePath());
                path.add(node.getLabel());
                return List.copyOf(path);
            }
            if (node.getSourceAnchor() != null
                    && (node.getSourceAnchor().contains("#bullet(")
                    || node.getSourceAnchor().contains("#ordered("))) {
                return List.copyOf(node.getSourcePath());
            }
        }
        return List.of();
    }

    private List<MindMapNode> findChain(MindMapNode node, String targetId, List<MindMapNode> path) {
        if (node == null) {
            return List.of();
        }
        path.add(node);
        if (node.getId().equals(targetId)) {
            return List.copyOf(path);
        }
        for (MindMapNode child : node.getChildren()) {
            List<MindMapNode> found = findChain(child, targetId, path);
            if (!found.isEmpty()) {
                return found;
            }
        }
        path.remove(path.size() - 1);
        return List.of();
    }

    private MarkdownBlock findInsertedBlock(List<MarkdownBlock> blocks,
                                            String label,
                                            List<String> expectedHeadingPath) {
        MarkdownBlock fallback = null;
        for (MarkdownBlock block : blocks) {
            if (block.type() != BlockType.BULLET_LIST_ITEM && block.type() != BlockType.ORDERED_LIST_ITEM) {
                continue;
            }
            boolean textMatches = block.normalizedText().equals(label)
                    || block.normalizedText().startsWith(label + " ");
            if (!textMatches) {
                continue;
            }
            if (block.headingPath().equals(expectedHeadingPath)) {
                return block;
            }
            fallback = block;
        }
        if (fallback != null) {
            return fallback;
        }
        throw new IllegalStateException("手动节点已写入 Markdown，但未找到对应列表块：" + label);
    }

    private void replaceSnapshots(Long documentId, Integer documentVersion,
                                  List<MarkdownBlock> newBlocks) {
        blockSnapshotRepository.deleteByDocumentIdAndDocumentVersion(documentId, documentVersion);
        blockSnapshotRepository.flush();
        List<BlockSnapshotEntity> entities = new ArrayList<>();
        for (MarkdownBlock block : newBlocks) {
            BlockSnapshotEntity entity = new BlockSnapshotEntity();
            entity.setDocumentId(documentId);
            entity.setDocumentVersion(documentVersion);
            entity.setBlockId(block.blockId());
            entity.setBlockType(block.type().name());
            entity.setAnchor(block.anchor());
            entity.setHeadingPath(writeStringList(block.headingPath()));
            entity.setNormalizedText(block.normalizedText());
            entity.setContentHash(block.contentHash());
            entity.setBlockOrder(block.order());
            entities.add(entity);
        }
        blockSnapshotRepository.saveAll(entities);
    }

    private String writeStringList(List<String> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("JSON 序列化失败", e);
        }
    }

    private MindMapEntity getMindMapOrThrow(Long mindmapId) {
        return mindMapRepository.findById(mindmapId)
                .orElseThrow(() -> ApiException.notFound("思维导图不存在：" + mindmapId));
    }

    private DocumentEntity getDocumentOrThrow(Long documentId) {
        return documentRepository.findById(documentId)
                .orElseThrow(() -> ApiException.notFound("文档不存在：" + documentId));
    }

    private MindMapGraph requireReadyGraph(MindMapEntity mindMap) {
        MindMapGraph graph = graphJsonCodec.read(mindMap.getGraph());
        if (graph.isEmpty()) {
            throw ApiException.conflict("GRAPH_EMPTY",
                    "思维导图还未生成，请先点击“同步到思维导图”");
        }
        return graph;
    }

    private void requireGraphVersion(MindMapEntity mindMap, Integer expectedVersion) {
        if (expectedVersion == null || !mindMap.getGraphVersion().equals(expectedVersion)) {
            throw ApiException.conflict("VERSION_CONFLICT",
                    "思维导图版本不一致，请刷新后重试。服务器版本：" + mindMap.getGraphVersion());
        }
    }

    private void requireNoActiveJob(Long documentId) {
        boolean active = !syncJobRepository
                .findByDocumentIdAndStatusInOrderByIdDesc(documentId, SyncStatus.ACTIVE)
                .isEmpty();
        if (active) {
            throw ApiException.conflict("DOCUMENT_LOCKED",
                    "思维导图正在同步或播放动画，请完成后再编辑");
        }
    }

    private void requireValidLabel(String label) {
        if (label == null || label.isBlank()) {
            throw ApiException.badRequest("VALIDATION_ERROR", "节点标题不能为空");
        }
        if (label.trim().length() > MAX_LABEL_LENGTH) {
            throw ApiException.badRequest("VALIDATION_ERROR",
                    "节点标题不能超过 " + MAX_LABEL_LENGTH + " 个字符");
        }
    }

    private String normalizeSummary(String summary) {
        if (summary == null || summary.isBlank()) {
            return null;
        }
        String trimmed = summary.trim();
        if (trimmed.length() > MAX_SUMMARY_LENGTH) {
            throw ApiException.badRequest("VALIDATION_ERROR",
                    "节点摘要不能超过 " + MAX_SUMMARY_LENGTH + " 个字符");
        }
        return trimmed;
    }
}
