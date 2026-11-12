package com.example.aimindmap.agenttools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.example.aimindmap.agentruntime.EvidenceItem;
import com.example.aimindmap.agentruntime.EvidenceLedger;
import com.example.aimindmap.agenttracing.AgentToolContextKeys;
import com.example.aimindmap.agenttracing.AgentTracingProperties;
import com.example.aimindmap.document.DocumentEntity;
import com.example.aimindmap.document.DocumentRepository;
import com.example.aimindmap.mindmap.GraphJsonCodec;
import com.example.aimindmap.mindmap.MindMapEntity;
import com.example.aimindmap.mindmap.MindMapRepository;
import com.example.aimindmap.mindmap.graph.MindMapGraph;
import com.example.aimindmap.mindmap.graph.MindMapNode;
import com.example.aimindmap.vectorindex.VectorIndexExternalException;
import com.example.aimindmap.vectorindex.VectorSearchService;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;

class LearningResourceToolsTest {

    private VectorSearchService vectorSearchService;
    private DocumentRepository documentRepository;
    private MindMapRepository mindMapRepository;
    private GraphJsonCodec graphJsonCodec;
    private LearningResourceTools tools;
    private final ToolContext toolContext =
            new ToolContext(Map.of(AgentToolContextKeys.USER_ID, 8L));

    @BeforeEach
    void setUp() {
        vectorSearchService = Mockito.mock(VectorSearchService.class);
        documentRepository = Mockito.mock(DocumentRepository.class);
        mindMapRepository = Mockito.mock(MindMapRepository.class);
        graphJsonCodec = Mockito.mock(GraphJsonCodec.class);
        tools = new LearningResourceTools(vectorSearchService, documentRepository,
                mindMapRepository, graphJsonCodec, new AgentTracingProperties());
    }

    @Test
    void searchMyDocumentsReturnsHitsWithSourceMarkers() {
        when(vectorSearchService.search(eq(8L), eq("什么是进程"), anyList()))
                .thenReturn(outcome(new VectorSearchService.VectorHit(
                        "document_chunk", "42", 0, "操作系统第三章", "进程是资源分配的基本单位",
                        List.of("操作系统", "进程管理"), 0.71, null)));

        String result = tools.searchMyDocuments("什么是进程", toolContext);

        assertThat(result).contains("[来源类型: document_chunk", "资源ID: 42", "进程是资源分配的基本单位");
        assertThat(result).contains("操作系统 / 进程管理");
    }

    @Test
    void searchReturnsClearNotFoundMessage() {
        when(vectorSearchService.search(eq(8L), eq("法式焦糖布丁"), anyList()))
                .thenReturn(new VectorSearchService.SearchOutcome(List.of(), false));

        String result = tools.searchMyDocuments("法式焦糖布丁", toolContext);

        assertThat(result).contains("笔记中暂未找到");
    }

    @Test
    void searchInfrastructureFailureIsFatal() {
        when(vectorSearchService.search(eq(8L), eq("什么是进程"), anyList()))
                .thenThrow(new VectorIndexExternalException("Qdrant 检索失败",
                        new RuntimeException("connection refused")));

        assertThatThrownBy(() -> tools.searchMyDocuments("什么是进程", toolContext))
                .isInstanceOf(AgentToolFailureException.class)
                .satisfies(ex -> {
                    AgentToolFailureException failure = (AgentToolFailureException) ex;
                    assertThat(failure.code()).isEqualTo("AGENT_RETRIEVAL_FAILED");
                    assertThat(failure.fatal()).isTrue();
                });
    }

    @Test
    void readMyDocumentReturnsContentAfterOwnershipCheck() {
        DocumentEntity document = document(42L, 8L, "操作系统第三章", "进程调度笔记正文", "active");
        when(documentRepository.findByIdAndUserId(42L, 8L)).thenReturn(java.util.Optional.of(document));

        String result = tools.readMyDocument("42", toolContext);

        assertThat(result).contains("操作系统第三章", "documentId: 42", "进程调度笔记正文");
    }

    @Test
    void readMyDocumentRejectsMissingId() {
        when(documentRepository.findByIdAndUserId(999L, 8L)).thenReturn(java.util.Optional.empty());

        assertThatThrownBy(() -> tools.readMyDocument("999", toolContext))
                .isInstanceOf(AgentToolFailureException.class)
                .satisfies(ex -> {
                    AgentToolFailureException failure = (AgentToolFailureException) ex;
                    assertThat(failure.code()).isEqualTo("AGENT_RESOURCE_DENIED");
                    assertThat(failure.fatal()).isFalse();
                });
    }

    @Test
    void readMyDocumentRejectsOtherUsersId() {
        DocumentEntity otherUsersDocument = document(42L, 99L, "别人的笔记", "别人的正文", "active");
        when(documentRepository.findByIdAndUserId(42L, 8L)).thenReturn(java.util.Optional.empty());
        when(documentRepository.findByIdAndUserId(42L, 99L))
                .thenReturn(java.util.Optional.of(otherUsersDocument));

        assertThatThrownBy(() -> tools.readMyDocument("42", toolContext))
                .isInstanceOf(AgentToolFailureException.class)
                .hasMessageContaining("AGENT_RESOURCE_DENIED");
    }

    @Test
    void searchMindmapNodesReturnsHits() {
        when(vectorSearchService.search(eq(8L), eq("进程调度算法"), anyList()))
                .thenReturn(outcome(new VectorSearchService.VectorHit(
                        "mindmap_node", "5:node-1", 0, "进程调度", "时间片轮转",
                        List.of("操作系统", "进程管理"), 0.68, null)));

        String result = tools.searchMindmapNodes("进程调度算法", toolContext);

        assertThat(result).contains("[来源类型: mindmap_node", "资源ID: 5:node-1", "时间片轮转");
    }

    @Test
    void readMindmapReturnsSimplifiedOutlineAfterOwnershipCheck() {
        MindMapEntity mindMap = Mockito.mock(MindMapEntity.class);
        when(mindMap.getId()).thenReturn(5L);
        when(mindMap.getDocumentId()).thenReturn(42L);
        when(mindMap.getGraphVersion()).thenReturn(1);
        when(mindMap.getGraph()).thenReturn("{}");
        when(mindMapRepository.findByIdAndUserId(5L, 8L)).thenReturn(java.util.Optional.of(mindMap));
        when(graphJsonCodec.read("{}")).thenReturn(sampleGraph());

        String result = tools.readMindmap("5", toolContext);

        assertThat(result).contains("mindmapId: 5", "[root]", "进程管理", "[n1]", "调度");
    }

    @Test
    void readMindmapRejectsOtherUsersId() {
        when(mindMapRepository.findByIdAndUserId(5L, 8L)).thenReturn(java.util.Optional.empty());

        assertThatThrownBy(() -> tools.readMindmap("5", toolContext))
                .isInstanceOf(AgentToolFailureException.class)
                .hasMessageContaining("AGENT_RESOURCE_DENIED");
    }

    @Test
    void searchMyQuestionsReturnsHits() {
        when(vectorSearchService.search(eq(8L), eq("考查同步的选择题"), anyList()))
                .thenReturn(outcome(new VectorSearchService.VectorHit(
                        "question", "77", 0, "进程同步选择题", "题干：下列说法正确的是",
                        List.of("操作系统"), 0.66, null)));

        String result = tools.searchMyQuestions("考查同步的选择题", toolContext);

        assertThat(result).contains("[来源类型: question", "资源ID: 77", "题干：下列说法正确的是");
    }

    @Test
    void searchMyMemoryReturnsHitsWithSourceMarkers() {
        when(vectorSearchService.search(eq(8L), eq("我的学习偏好"), anyList()))
                .thenReturn(outcome(new VectorSearchService.VectorHit(
                        "memory", "52", 0, "喜欢代码例子",
                        "记忆类型：偏好\n记忆内容：喜欢用代码例子学习",
                        List.of(), 0.87, null)));

        String result = tools.searchMyMemory("我的学习偏好", toolContext);

        assertThat(result).contains("[来源类型: memory", "资源ID: 52")
                .contains("喜欢用代码例子学习");
    }

    @Test
    void searchMyMemorySharesInfrastructureFailureHandling() {
        when(vectorSearchService.search(eq(8L), eq("x"), anyList()))
                .thenThrow(new VectorIndexExternalException("Qdrant 不可用", null));

        assertThatThrownBy(() -> tools.searchMyMemory("x", toolContext))
                .isInstanceOf(AgentToolFailureException.class);
    }

    @Test
    void toolSignaturesNeverAcceptUserIdButDoAcceptToolContext() {
        int toolCount = 0;
        for (Method method : LearningResourceTools.class.getDeclaredMethods()) {
            if (!method.isAnnotationPresent(Tool.class)) {
                continue;
            }
            toolCount++;
            assertThat(method.getParameters())
                    .extracting(Parameter::getName)
                    .doesNotContain("userId");
            assertThat(method.getParameterTypes()).contains(ToolContext.class);
        }
        assertThat(toolCount).isEqualTo(6);
    }

    private static VectorSearchService.SearchOutcome outcome(VectorSearchService.VectorHit hit) {
        return new VectorSearchService.SearchOutcome(List.of(hit), true);
    }

    /** 带证据台账的 ToolContext（生产链路由 KnowledgeAssistantStreamer 放入）。 */
    private static ToolContext contextWithLedger(EvidenceLedger ledger) {
        return new ToolContext(Map.of(
                AgentToolContextKeys.USER_ID, 8L,
                AgentToolContextKeys.EVIDENCE_LEDGER, ledger));
    }

    @Test
    void searchOutputCarriesEvidenceNumberAndRegistersHit() {
        when(vectorSearchService.search(eq(8L), eq("什么是进程"), anyList()))
                .thenReturn(outcome(new VectorSearchService.VectorHit(
                        "document_chunk", "42", 0, "操作系统第三章", "进程是资源分配的基本单位",
                        List.of("操作系统"), 0.71, null)));
        EvidenceLedger ledger = new EvidenceLedger(99L);

        String result = tools.searchMyDocuments("什么是进程", contextWithLedger(ledger));

        assertThat(result).contains("[E1] [来源类型: document_chunk");
        assertThat(ledger.size()).isEqualTo(1);
        EvidenceItem item = ledger.items().get(0);
        assertThat(item.key()).isEqualTo("E1");
        assertThat(item.resourceType()).isEqualTo("document_chunk");
        assertThat(item.resourceId()).isEqualTo("42");
        assertThat(item.chunkIndex()).isZero();
        assertThat(item.score()).isEqualTo(0.71d);
    }

    @Test
    void repeatedSearchReusesSameEvidenceNumber() {
        when(vectorSearchService.search(eq(8L), eq("什么是进程"), anyList()))
                .thenReturn(outcome(new VectorSearchService.VectorHit(
                        "document_chunk", "42", 0, "操作系统第三章", "进程是资源分配的基本单位",
                        List.of("操作系统"), 0.71, null)));
        EvidenceLedger ledger = new EvidenceLedger(99L);

        String first = tools.searchMyDocuments("什么是进程", contextWithLedger(ledger));
        String second = tools.searchMyDocuments("什么是进程", contextWithLedger(ledger));

        assertThat(first).contains("[E1]");
        assertThat(second).contains("[E1]").doesNotContain("[E2]");
        assertThat(ledger.size()).isEqualTo(1);
    }

    @Test
    void searchMindmapNodesKeepsNodeResourceIdInEvidence() {
        when(vectorSearchService.search(eq(8L), eq("进程调度算法"), anyList()))
                .thenReturn(outcome(new VectorSearchService.VectorHit(
                        "mindmap_node", "5:node-1", 0, "进程调度", "时间片轮转",
                        List.of("操作系统"), 0.68, null)));
        EvidenceLedger ledger = new EvidenceLedger(99L);

        String result = tools.searchMindmapNodes("进程调度算法", contextWithLedger(ledger));

        assertThat(result).contains("[E1] [来源类型: mindmap_node");
        assertThat(ledger.items()).singleElement()
                .satisfies(item -> {
                    assertThat(item.resourceType()).isEqualTo("mindmap_node");
                    assertThat(item.resourceId()).isEqualTo("5:node-1");
                });
    }

    @Test
    void readMyDocumentRegistersExactlyOneEvidenceItem() {
        DocumentEntity document = document(42L, 8L, "操作系统第三章", "进程调度笔记正文", "active");
        when(documentRepository.findByIdAndUserId(42L, 8L)).thenReturn(java.util.Optional.of(document));
        EvidenceLedger ledger = new EvidenceLedger(99L);

        String result = tools.readMyDocument("42", contextWithLedger(ledger));

        assertThat(result).contains("[E1] [来源类型: document");
        assertThat(ledger.size()).isEqualTo(1);
        EvidenceItem item = ledger.items().get(0);
        assertThat(item.resourceType()).isEqualTo("document");
        assertThat(item.resourceId()).isEqualTo("42");
        assertThat(item.chunkIndex()).isZero();
        assertThat(item.score()).isEqualTo(1.0d);
        assertThat(item.snippet()).isEqualTo("进程调度笔记正文");
    }

    @Test
    void readMindmapRegistersEvidenceForWholeMindmap() {
        MindMapEntity mindMap = Mockito.mock(MindMapEntity.class);
        when(mindMap.getId()).thenReturn(5L);
        when(mindMap.getDocumentId()).thenReturn(42L);
        when(mindMap.getGraphVersion()).thenReturn(1);
        when(mindMap.getGraph()).thenReturn("{}");
        when(mindMapRepository.findByIdAndUserId(5L, 8L)).thenReturn(java.util.Optional.of(mindMap));
        when(graphJsonCodec.read("{}")).thenReturn(sampleGraph());
        EvidenceLedger ledger = new EvidenceLedger(99L);

        String result = tools.readMindmap("5", contextWithLedger(ledger));

        assertThat(result).contains("[E1] [来源类型: mindmap");
        assertThat(ledger.items()).singleElement()
                .satisfies(item -> assertThat(item.resourceId()).isEqualTo("5"));
    }

    @Test
    void toolOutputHasNoEvidenceNumberWithoutLedger() {
        when(vectorSearchService.search(eq(8L), eq("什么是进程"), anyList()))
                .thenReturn(outcome(new VectorSearchService.VectorHit(
                        "document_chunk", "42", 0, "操作系统第三章", "进程是资源分配的基本单位",
                        List.of("操作系统"), 0.71, null)));

        String result = tools.searchMyDocuments("什么是进程", toolContext);

        assertThat(result).doesNotContain("[E1]");
    }

    @Test
    void invalidResourceIdIsRetryableArgumentErrorForModelCorrection() {
        // P1-D1：ID 非数字属于参数错误——非致命但可重试，由 TracingToolCallback 转成纠错提示
        assertThatThrownBy(() -> tools.readMyDocument("abc", toolContext))
                .isInstanceOf(AgentToolFailureException.class)
                .satisfies(ex -> {
                    AgentToolFailureException failure = (AgentToolFailureException) ex;
                    assertThat(failure.code()).isEqualTo("AGENT_ARGUMENT_INVALID");
                    assertThat(failure.fatal()).isFalse();
                    assertThat(failure.retryable()).isTrue();
                });
    }

    @Test
    void blankQueryIsRetryableArgumentError() {
        assertThatThrownBy(() -> tools.searchMyDocuments("  ", toolContext))
                .isInstanceOf(AgentToolFailureException.class)
                .satisfies(ex -> {
                    AgentToolFailureException failure = (AgentToolFailureException) ex;
                    assertThat(failure.code()).isEqualTo("AGENT_ARGUMENT_INVALID");
                    assertThat(failure.retryable()).isTrue();
                });
    }

    private static DocumentEntity document(Long id, Long userId, String title, String content, String status) {
        DocumentEntity entity = new DocumentEntity();
        entity.setUserId(userId);
        entity.setTitle(title);
        entity.setContent(content);
        entity.setStatus(status);
        setId(entity, id);
        return entity;
    }

    private static void setId(DocumentEntity entity, Long id) {
        try {
            java.lang.reflect.Field field = DocumentEntity.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(entity, id);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private static MindMapGraph sampleGraph() {
        Instant now = Instant.now();
        MindMapNode root = new MindMapNode("root", "进程管理", "管理进程", "markdown",
                null, null, List.of("操作系统"), now);
        MindMapNode child = new MindMapNode("n1", "调度", null, "markdown",
                null, null, List.of("操作系统", "进程管理"), now);
        root.getChildren().add(child);
        return new MindMapGraph("root", 1, root, now);
    }
}
