package com.example.aimindmap.agenttools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.aimindmap.agentruntime.EvidenceItem;
import com.example.aimindmap.agentruntime.EvidenceLedger;
import com.example.aimindmap.agentruntime.MultiQueryRetriever;
import com.example.aimindmap.agentruntime.RetrievalProperties;
import com.example.aimindmap.agenttracing.AgentToolContextKeys;
import com.example.aimindmap.agenttracing.AgentTracingProperties;
import com.example.aimindmap.document.DocumentEntity;
import com.example.aimindmap.document.DocumentRepository;
import com.example.aimindmap.mindmap.GraphJsonCodec;
import com.example.aimindmap.mindmap.MindMapRepository;
import com.example.aimindmap.vectorindex.QdrantProperties;
import com.example.aimindmap.vectorindex.VectorSearchService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;

/**
 * P1-5 接线的确定性单测：多查询开关、证据编号不变、{@code read_my_document} 的可选 query。
 *
 * <p>不真调 AI、不真连 Qdrant：检索器/仓库/网关全部是 mock 或 fake。
 */
class LearningResourceToolsMultiQueryTest {

    private static final Long USER_ID = 8L;

    private final VectorSearchService vectorSearchService = mock(VectorSearchService.class);
    private final DocumentRepository documentRepository = mock(DocumentRepository.class);
    private final MindMapRepository mindMapRepository = mock(MindMapRepository.class);
    private final GraphJsonCodec graphJsonCodec = mock(GraphJsonCodec.class);
    private final QdrantProperties qdrantProperties = new QdrantProperties();
    private final RetrievalProperties retrievalProperties = new RetrievalProperties();

    private final ToolContext toolContext = new ToolContext(Map.of(AgentToolContextKeys.USER_ID, USER_ID));

    @BeforeEach
    void setUp() {
        retrievalProperties.setMultiQueryEnabled(true);
    }

    // ---------- 1) 接线：三个检索工具走多查询，记忆保持旧路径 ----------

    @Test
    void searchToolsUseMultiQueryRetrieverWhileMemoryKeepsOldPath() {
        MultiQueryRetriever retriever = mock(MultiQueryRetriever.class);
        when(retriever.search(eq(USER_ID), anyString(), anyList()))
                .thenReturn(outcome(hit("42", 0, 0.71d)));
        when(vectorSearchService.search(eq(USER_ID), eq("我的学习偏好"), anyList()))
                .thenReturn(outcome(hit("52", 0, 0.87d)));
        LearningResourceTools tools = toolsWith(retriever);

        tools.searchMyDocuments("什么是进程", toolContext);
        tools.searchMindmapNodes("进程调度算法", toolContext);
        tools.searchMyQuestions("考查同步的选择题", toolContext);
        verify(retriever, times(3)).search(eq(USER_ID), anyString(), anyList());

        tools.searchMyMemory("我的学习偏好", toolContext);

        // 方案 §P1-5 只点名三个检索工具；记忆检索保持单 query 旧路径
        verify(vectorSearchService).search(eq(USER_ID), eq("我的学习偏好"), anyList());
        verify(retriever, times(3)).search(eq(USER_ID), anyString(), anyList());
    }

    // ---------- 2) 开关关闭 = 旧行为（1 次检索、输出格式不变） ----------

    @Test
    void switchOffKeepsSingleLegacySearchAndOldOutputFormat() {
        retrievalProperties.setMultiQueryEnabled(false);
        MultiQueryRetriever retriever = new MultiQueryRetriever(vectorSearchService, null,
                qdrantProperties, retrievalProperties);
        when(vectorSearchService.search(eq(USER_ID), eq("什么是进程"), anyList()))
                .thenReturn(outcome(hit("42", 0, 0.71d)));
        LearningResourceTools tools = toolsWith(retriever);

        String result = tools.searchMyDocuments("什么是进程", toolContext);

        verify(vectorSearchService, times(1)).search(eq(USER_ID), eq("什么是进程"), anyList());
        verify(vectorSearchService, never()).searchTopChunks(any(), anyString(), anyList(), anyInt());
        assertThat(result).startsWith("共检索到 1 条相关内容：")
                .contains("[来源类型: document_chunk, 资源ID: 42, 片段: 0, 相似度: 0.7100");
    }

    // ---------- 3) 证据编号：仍从 E1 连续、无重复、不超 12 条 ----------

    @Test
    void evidenceNumbersStayContinuousUniqueAndWithinLedgerLimit() {
        MultiQueryRetriever retriever = mock(MultiQueryRetriever.class);
        when(retriever.search(eq(USER_ID), eq("查询一"), anyList())).thenReturn(outcome(
                hit("1", 0, 0.9d), hit("2", 0, 0.8d), hit("3", 0, 0.7d),
                hit("4", 0, 0.6d), hit("5", 0, 0.5d)));
        when(retriever.search(eq(USER_ID), eq("查询二"), anyList())).thenReturn(outcome(
                hit("6", 0, 0.49d), hit("7", 0, 0.48d), hit("8", 0, 0.47d), hit("9", 0, 0.46d),
                hit("10", 0, 0.45d), hit("11", 0, 0.44d), hit("12", 0, 0.43d), hit("13", 0, 0.42d)));
        EvidenceLedger ledger = new EvidenceLedger(99L);
        ToolContext context = contextWithLedger(ledger);
        LearningResourceTools tools = toolsWith(retriever);

        String first = tools.searchMyDocuments("查询一", context);
        assertThat(first).contains("[E1] [来源类型: document_chunk", "[E5] [来源类型: document_chunk");
        assertThat(ledger.items()).extracting(EvidenceItem::key)
                .containsExactly("E1", "E2", "E3", "E4", "E5");

        // 同一组命中再来一次：复用原编号，不新增
        String repeated = tools.searchMyDocuments("查询一", context);
        assertThat(repeated).contains("[E1]").doesNotContain("[E6]");
        assertThat(ledger.size()).isEqualTo(5);

        // 再来 8 条新命中：台账上限 12 起效，但编号仍然唯一、仍从 E1 开始
        tools.searchMyDocuments("查询二", context);
        List<String> keys = ledger.items().stream().map(EvidenceItem::key).toList();
        assertThat(ledger.size()).isEqualTo(EvidenceLedger.DEFAULT_MAX_ITEMS);
        assertThat(keys).doesNotHaveDuplicates().allMatch(key -> key.matches("E\\d+"));
        assertThat(keys).first().isEqualTo("E1");
    }

    // ---------- 4) read_my_document 带 query：大纲 + 相关段落，≤1500 字 ----------

    @Test
    void readMyDocumentWithQueryReturnsOutlineAndRelatedParagraphsWithinLimit() {
        DocumentEntity document = document(longDocument());
        when(documentRepository.findByIdAndUserId(42L, USER_ID)).thenReturn(Optional.of(document));
        LearningResourceTools tools = toolsWith(MultiQueryRetriever.legacy(vectorSearchService));

        String result = tools.readMyDocument("42", "时间片轮转", toolContext);

        assertThat(result.length())
                .as("整段工具输出（含头部）必须 ≤1500 字，实测 %d", result.length())
                .isLessThanOrEqualTo(LearningResourceTools.READ_DOCUMENT_QUERY_MAX_CHARS);
        assertThat(result)
                .startsWith("# 操作系统笔记")
                .contains("[来源类型: document, documentId: 42")
                .contains("【大纲】")
                .contains("- 操作系统笔记")
                .contains("  - 进程管理")
                .contains("    - 调度")
                .contains("【相关段落】")
                .contains("时间片轮转")
                .doesNotContain("笔记末尾标记ABC");
    }

    // ---------- 5) read_my_document 不带 query：与旧输出逐字节一致 ----------

    @Test
    void readMyDocumentWithoutQueryKeepsOldFullTextOutput() {
        DocumentEntity document = document(longDocument());
        when(documentRepository.findByIdAndUserId(42L, USER_ID)).thenReturn(Optional.of(document));
        LearningResourceTools tools = toolsWith(MultiQueryRetriever.legacy(vectorSearchService));

        String result = tools.readMyDocument("42", toolContext);

        // 旧基线：改造前 read_my_document 的输出模板（不带证据编号时前缀为空）
        String baseline = "# " + document.getTitle() + "\n"
                + "[来源类型: document, documentId: 42, contentVersion: 1, 更新时间: "
                + document.getUpdatedAt() + "]\n\n"
                + document.getContent() + "\n";
        assertThat(result).isEqualTo(baseline);
        assertThat(result.length()).as("全文必须超过 1500 字，才能证明没有走摘录").isGreaterThan(1500);
    }

    // ---------- 6) 真实工具回调：query 在 JSON Schema 里是可选参数 ----------

    @Test
    void readMyDocumentCallbackTreatsQueryAsOptional() throws Exception {
        DocumentEntity document = document(longDocument());
        when(documentRepository.findByIdAndUserId(42L, USER_ID)).thenReturn(Optional.of(document));
        LearningResourceTools tools = toolsWith(MultiQueryRetriever.legacy(vectorSearchService));
        ToolCallback callback = Arrays.stream(ToolCallbacks.from(tools))
                .filter(candidate -> "read_my_document".equals(candidate.getToolDefinition().name()))
                .findFirst()
                .orElseThrow();

        // 工具名与描述保持不变（避免破坏现有工具调用与评测）
        assertThat(callback.getToolDefinition().description())
                .isEqualTo("按笔记 ID 读取当前用户自己的某篇课程笔记全文。通常先用 search_my_documents 拿到 documentId 再调用。");

        JsonNode schema = new ObjectMapper().readTree(callback.getToolDefinition().inputSchema());
        assertThat(schema.at("/properties/query").isMissingNode())
                .as("参数列表里必须有 query 字段")
                .isFalse();
        List<String> required = new ArrayList<>();
        schema.withArray("required").forEach(node -> required.add(node.asText()));
        assertThat(required).containsExactly("documentId");

        // 模型不传 query：框架传 null 进来 → 仍然输出全文（旧行为）
        String withoutQuery = callback.call("{\"documentId\":\"42\"}", toolContext);
        assertThat(withoutQuery.length()).isGreaterThan(1500);

        String withQuery = callback.call(
                "{\"documentId\":\"42\",\"query\":\"时间片轮转\"}", toolContext);
        assertThat(withQuery.length())
                .isLessThanOrEqualTo(LearningResourceTools.READ_DOCUMENT_QUERY_MAX_CHARS);
        assertThat(withQuery).contains("【大纲】").contains("时间片轮转");
    }

    // ---------- 工具方法 ----------

    private LearningResourceTools toolsWith(MultiQueryRetriever retriever) {
        return new LearningResourceTools(vectorSearchService, documentRepository,
                mindMapRepository, graphJsonCodec, new AgentTracingProperties(), retriever);
    }

    private static ToolContext contextWithLedger(EvidenceLedger ledger) {
        return new ToolContext(Map.of(
                AgentToolContextKeys.USER_ID, USER_ID,
                AgentToolContextKeys.EVIDENCE_LEDGER, ledger));
    }

    private static VectorSearchService.VectorHit hit(String sourceId, int chunkIndex, double score) {
        return new VectorSearchService.VectorHit("document_chunk", sourceId, chunkIndex,
                "标题" + sourceId, "正文" + sourceId + "#" + chunkIndex, List.of("操作系统"), score, null);
    }

    private static VectorSearchService.SearchOutcome outcome(
            VectorSearchService.VectorHit... hits) {
        return new VectorSearchService.SearchOutcome(List.of(hits), hits.length > 0);
    }

    private static DocumentEntity document(String content) {
        DocumentEntity entity = new DocumentEntity();
        entity.setUserId(USER_ID);
        entity.setTitle("操作系统笔记");
        entity.setContent(content);
        entity.setStatus("active");
        setId(entity, 42L);
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

    /** 带标题层级 + 无关长附录的笔记：用来验证“大纲 + 相关段落 ≤1500 字”与“不带 query 仍全文”。 */
    private static String longDocument() {
        return "# 操作系统笔记\n\n"
                + "## 进程管理\n\n"
                + "进程是资源分配的基本单位，进程之间通过调度器竞争 CPU。\n\n"
                + "### 调度\n\n"
                + "时间片轮转：每个进程按固定时间片轮流执行，时间片用完就回到就绪队列。\n\n"
                + "## 内存管理\n\n"
                + "分页把虚拟地址空间切成固定大小的页面，通过页表映射到物理页框。\n\n"
                + "## 附录\n\n"
                + "下面是无关的长段落，用来把文档撑到 1500 字以上，避免整篇塞进上下文。".repeat(60)
                + "\n\n笔记末尾标记ABC\n";
    }
}
