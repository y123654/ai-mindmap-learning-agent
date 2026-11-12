package com.example.aimindmap.agenttools;

import com.example.aimindmap.agentruntime.EvidenceEnvelope;
import com.example.aimindmap.agentruntime.EvidenceLedger;
import com.example.aimindmap.agentruntime.EvidenceSanitizer;
import com.example.aimindmap.agentruntime.MultiQueryRetriever;
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
import com.example.aimindmap.vectorindex.VectorResourceType;
import com.example.aimindmap.vectorindex.VectorSearchService;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 学习 Agent 第一版 6 个只读工具（方案 §8.1；阶段 4-B 补齐 search_my_memory）。
 * 安全规则（§8.2）：方法签名不含 userId；当前用户从 ToolContext 读取（R-03）；
 * AI 传入的资源 ID 必须按 userId 做归属校验（R-04）。
 *
 * <p>P1-5（方案 §P1-5）：检索类工具改走 {@link MultiQueryRetriever}（原 query + 至多 2 个改写 + RRF +
 * 每篇最多 N 个 chunk；N 与最终条数由 {@code app.agent.retrieval.max-chunks-per-document / max-results}
 * 配置，默认 2 / 5），开关 {@code app.agent.retrieval.multi-query-enabled} 默认开；
 * 关掉时 {@link MultiQueryRetriever} 直接委托 {@link VectorSearchService#search}，行为与改造前一致。
 * 多查询里的 <b>LLM 改写默认关</b>（{@code app.agent.retrieval.rewrite-llm-enabled=false}，
 * 所有者 2026-10-28 决定，依据 E2/E2b：+780ms、召回 +0.0pp），因此默认路径零 LLM 调用、只做规则改写；
 * 打开后恢复“LLM 优先、失败降级规则”。两种情况都照常走 RRF 融合。
 *
 * <p><b>P1-E3b（方案 §P1-7）</b>：默认（{@code app.agent.security.evidence-guard-enabled=true}）
 * 每条证据用 {@code <evidence id="E1" source="document_chunk:42">…</evidence>} 包起来，
 * 资料正文/标题/路径做 {@code & < >} 转义，并用 {@code EvidenceSanitizer} 扫一遍
 * “疑似指令文本”：命中则把该条证据标 {@code suspicious}（落 {@code agent_evidence.suspicious}）、
 * 在证据前加一行固定安全提示、打一条只含 runId/证据号/规则名的 warn（<b>不含正文</b>）。
 * {@code [E]} 编号规则、12 条上限、去重、证据落库字段<b>全部不变</b>；
 * 开关关掉时输出与 P1-E3b 之前<b>逐字节一致</b>（回滚用）。
 * 没有证据台账（直调工具、旧单测）时不包装——包装需要 {@code id="E1"}。
 */
@Component
public class LearningResourceTools {

    private static final Logger log = LoggerFactory.getLogger(LearningResourceTools.class);

    /** agent_evidence.snippet 的截断上限：整篇读取时只留开头，避免单条证据过长。 */
    private static final int EVIDENCE_SNIPPET_MAX_CHARS = 1000;

    /** read_my_document 带 query 时的摘录上限（方案 §P1-5 原文：最多 1500 字）。 */
    static final int READ_DOCUMENT_QUERY_MAX_CHARS = DocumentExcerptBuilder.DEFAULT_MAX_CHARS;

    /**
     * read_my_document 输出头部模板（与 P1-5 之前逐字节一致）。
     *
     * <p>{@code %s} 依次是：标题、证据编号前缀（可能为空）、documentId、contentVersion、更新时间。
     * 完整输出 = 本模板 + 正文 + 尾部。防护关闭时尾部就是一个换行，“不带 query”的返回值与改造前完全相同；
     * 防护打开时前缀里多一个 {@code <evidence …>} 开标签、尾部多一个闭标签。
     */
    private static final String DOCUMENT_HEADER_TEMPLATE = """
            # %s
            %s[来源类型: document, documentId: %d, contentVersion: %d, 更新时间: %s]

            """;

    /** 预留“证据编号前缀（最多 [E12] ）+ 正文尾部换行”的字符数，保证整段输出不超过 1500。 */
    private static final int EVIDENCE_PREFIX_AND_TRAILER_RESERVE = 8;

    /**
     * P1-E3b：防护打开时，{@code read_my_document} 尾部要额外留出闭合标签的位置，
     * 否则 1500 字上限会把 {@code </evidence>} 切掉、留下一个没闭合的证据块。
     */
    private static final String EVIDENCE_CLOSE_AND_TRAILER = "\n" + EvidenceEnvelope.closeTag() + "\n";

    private final VectorSearchService vectorSearchService;
    private final DocumentRepository documentRepository;
    private final MindMapRepository mindMapRepository;
    private final GraphJsonCodec graphJsonCodec;
    private final AgentTracingProperties properties;
    private final MultiQueryRetriever multiQueryRetriever;

    /**
     * 旧装配（只注入向量检索）：等价于“多查询开关关闭”，检索行为与 P1-5 之前一致。
     *
     * <p>保留它是因为旧单测/旧代码只依赖 5 个协作者；生产装配走下面带
     * {@link MultiQueryRetriever} 的构造器。
     */
    public LearningResourceTools(VectorSearchService vectorSearchService,
                                 DocumentRepository documentRepository,
                                 MindMapRepository mindMapRepository,
                                 GraphJsonCodec graphJsonCodec,
                                 AgentTracingProperties properties) {
        this(vectorSearchService, documentRepository, mindMapRepository, graphJsonCodec, properties,
                MultiQueryRetriever.legacy(vectorSearchService));
    }

    /** 生产装配：多查询开关与改写都在 {@link MultiQueryRetriever} 内部生效。 */
    @Autowired
    public LearningResourceTools(VectorSearchService vectorSearchService,
                                 DocumentRepository documentRepository,
                                 MindMapRepository mindMapRepository,
                                 GraphJsonCodec graphJsonCodec,
                                 AgentTracingProperties properties,
                                 MultiQueryRetriever multiQueryRetriever) {
        this.vectorSearchService = vectorSearchService;
        this.documentRepository = documentRepository;
        this.mindMapRepository = mindMapRepository;
        this.graphJsonCodec = graphJsonCodec;
        this.properties = properties;
        this.multiQueryRetriever = multiQueryRetriever == null
                ? MultiQueryRetriever.legacy(vectorSearchService)
                : multiQueryRetriever;
    }

    @Tool(name = "search_my_documents",
            description = "按自然语言语义搜索当前用户自己的课程笔记片段。当需要从用户的课程笔记中查找概念、原理、算法等内容时调用。返回最相关的若干片段及来源。")
    public String searchMyDocuments(
            @ToolParam(description = "要搜索的问题或关键词，例如：什么是时间片轮转", required = true)
            String query,
            ToolContext toolContext) {
        Long userId = currentUserId(toolContext);
        log.debug("工具 search_my_documents userId={} queryLength={}", userId, length(query));
        return runSearch(userId, query, List.of(VectorResourceType.DOCUMENT_CHUNK), toolContext, true);
    }

    @Tool(name = "read_my_document",
            description = "按笔记 ID 读取当前用户自己的某篇课程笔记全文。通常先用 search_my_documents 拿到 documentId 再调用。")
    public String readMyDocument(
            @ToolParam(description = "笔记 ID（search_my_documents 返回结果中的 documentId）", required = true)
            String documentId,
            @ToolParam(description = "可选：只想看与这个问题相关的部分时传入。传了只返回“标题大纲 + 相关段落”（最多 1500 字）；不传返回全文。",
                    required = false)
            String query,
            ToolContext toolContext) {
        Long userId = currentUserId(toolContext);
        Long parsedId = parseResourceId(documentId);
        DocumentEntity document = documentRepository.findByIdAndUserId(parsedId, userId).orElse(null);
        if (document == null || !"active".equals(document.getStatus())) {
            log.warn("工具 read_my_document 归属拒绝 userId={} documentId={}", userId, documentId);
            throw new AgentToolFailureException("AGENT_RESOURCE_DENIED",
                    "AGENT_RESOURCE_DENIED: 笔记不存在或不属于当前用户，拒绝访问", false);
        }
        log.debug("工具 read_my_document userId={} documentId={} withQuery={}",
                userId, parsedId, query != null && !query.isBlank());
        String content = document.getContent();
        boolean withQuery = query != null && !query.isBlank();
        String resourceId = String.valueOf(document.getId());
        boolean guard = guardEnabled();
        String body = content;
        if (withQuery) {
            // 1500 字是“整段工具输出”的上限：先把头部占用的字符扣掉，再让摘录器按剩余预算截断
            String bareHeader = documentHeader(document, "");
            int reserve = EVIDENCE_PREFIX_AND_TRAILER_RESERVE;
            if (guard) {
                // P1-E3b：防护打开时，开标签/闭标签也占字符，必须一起扣（编号按最大 E12 预留）
                reserve += EvidenceEnvelope.openTag(
                        "E" + EvidenceLedger.DEFAULT_MAX_ITEMS, "document", resourceId).length()
                        + 1 // 开标签后的换行
                        + EVIDENCE_CLOSE_AND_TRAILER.length();
            }
            int budget = READ_DOCUMENT_QUERY_MAX_CHARS - bareHeader.length() - reserve;
            body = DocumentExcerptBuilder.build(content, query.strip(), budget);
        }
        // 扫描的是“模型实际会看到的那段正文”，标题也是用户数据，一起扫
        EvidenceSanitizer.ScanResult scan = guard
                ? EvidenceSanitizer.scan(document.getTitle(), body)
                : EvidenceSanitizer.ScanResult.clean();
        String evidenceKey = registerWholeResource(toolContext, "document", resourceId,
                document.getTitle(), body, scan.suspicious());
        logSuspicious(evidenceLedger(toolContext), evidenceKey, "document", resourceId, 0, scan);
        boolean wrapped = guard && evidenceKey != null;
        // 提示行必须在 <evidence> 标签之外：它是系统说的话，放进标签里就会被当成资料
        String prefix = wrapped
                ? EvidenceSanitizer.hintLineIfSuspicious(scan)
                        + EvidenceEnvelope.openTag(evidenceKey, "document", resourceId) + "\n"
                        + evidencePrefix(evidenceKey)
                : evidencePrefix(evidenceKey);
        String trailer = wrapped ? EVIDENCE_CLOSE_AND_TRAILER : "\n";
        String header = documentHeader(document, prefix);
        String promptBody = wrapped ? EvidenceEnvelope.escapeText(body) : body;
        String text = header + promptBody + trailer;
        if (withQuery && text.length() > READ_DOCUMENT_QUERY_MAX_CHARS) {
            // 兜底：标题异常长、或转义后 & → &amp; 撑长，都不能超过 1500 字；
            // 截断时保住闭合标签，避免留下没闭合的证据块（正文已无原始 < >，截断不会造出标签）
            int keep = READ_DOCUMENT_QUERY_MAX_CHARS - header.length() - trailer.length() - 1;
            if (keep > 0 && keep < promptBody.length()) {
                text = header + promptBody.substring(0, keep) + "…" + trailer;
            } else {
                text = text.substring(0, READ_DOCUMENT_QUERY_MAX_CHARS - 1) + "…";
            }
        }
        return text;
    }

    /** 按 {@link #DOCUMENT_HEADER_TEMPLATE} 拼头部；证据编号前缀为空时就是旧格式。 */
    private static String documentHeader(DocumentEntity document, String evidencePrefix) {
        return DOCUMENT_HEADER_TEMPLATE.formatted(document.getTitle(), evidencePrefix,
                document.getId(), document.getContentVersion(), document.getUpdatedAt());
    }

    /**
     * 兼容旧调用方的不带 query 版本（等价于“不传 query → 返回全文”）。
     *
     * <p>不是 {@code @Tool}：Spring AI 只注册带注解的三参版本，参数 {@code query} 在 JSON Schema 里
     * 是 optional，模型不传时框架传 {@code null} 进来。
     */
    public String readMyDocument(String documentId, ToolContext toolContext) {
        return readMyDocument(documentId, null, toolContext);
    }

    @Tool(name = "search_mindmap_nodes",
            description = "按自然语言语义搜索当前用户自己的思维导图节点。当需要从思维导图的知识结构中查找知识点及其上下文时调用。")
    public String searchMindmapNodes(
            @ToolParam(description = "要搜索的问题或关键词，例如：进程调度算法有哪些", required = true)
            String query,
            ToolContext toolContext) {
        Long userId = currentUserId(toolContext);
        log.debug("工具 search_mindmap_nodes userId={} queryLength={}", userId, length(query));
        return runSearch(userId, query, List.of(VectorResourceType.MINDMAP_NODE), toolContext, true);
    }

    @Tool(name = "read_mindmap",
            description = "按思维导图 ID 读取当前用户自己的思维导图结构（精简的层级大纲形式）。通常先用 search_mindmap_nodes 拿到 mindmapId 再调用。")
    public String readMindmap(
            @ToolParam(description = "思维导图 ID（search_mindmap_nodes 返回结果中的 mindmapId）", required = true)
            String mindmapId,
            ToolContext toolContext) {
        Long userId = currentUserId(toolContext);
        Long parsedId = parseResourceId(mindmapId);
        MindMapEntity mindMap = mindMapRepository.findByIdAndUserId(parsedId, userId).orElse(null);
        if (mindMap == null) {
            log.warn("工具 read_mindmap 归属拒绝 userId={} mindmapId={}", userId, mindmapId);
            throw new AgentToolFailureException("AGENT_RESOURCE_DENIED",
                    "AGENT_RESOURCE_DENIED: 思维导图不存在或不属于当前用户，拒绝访问", false);
        }
        MindMapGraph graph;
        try {
            graph = graphJsonCodec.read(mindMap.getGraph());
        } catch (RuntimeException e) {
            log.error("工具 read_mindmap 解析导图结构失败 userId={} mindmapId={}", userId, parsedId, e);
            throw new AgentToolFailureException("AGENT_RETRIEVAL_FAILED",
                    "AGENT_RETRIEVAL_FAILED: 思维导图结构解析失败", true, e);
        }
        log.debug("工具 read_mindmap userId={} mindmapId={}", userId, parsedId);
        return formatMindmap(mindMap, graph, toolContext);
    }

    @Tool(name = "search_my_questions",
            description = "按自然语言语义搜索当前用户自己的已有题目（选择题、简答题等）。当需要查找练习题、考点、相似题目时调用。")
    public String searchMyQuestions(
            @ToolParam(description = "要搜索的问题或关键词，例如：考查进程同步的选择题", required = true)
            String query,
            ToolContext toolContext) {
        Long userId = currentUserId(toolContext);
        log.debug("工具 search_my_questions userId={} queryLength={}", userId, length(query));
        return runSearch(userId, query, List.of(VectorResourceType.QUESTION), toolContext, true);
    }

    private String runSearch(Long userId, String query, List<VectorResourceType> resourceTypes,
                             ToolContext toolContext, boolean multiQuery) {
        if (query == null || query.isBlank()) {
            // P1-D1：参数类错误标记 retryable，交给 TracingToolCallback 返回纠错提示而不是中止整轮
            throw new AgentToolFailureException("AGENT_ARGUMENT_INVALID",
                    "AGENT_ARGUMENT_INVALID: 搜索内容不能为空", false, true);
        }
        VectorSearchService.SearchOutcome outcome;
        try {
            outcome = multiQuery
                    ? multiQueryRetriever.search(userId, query, resourceTypes)
                    : vectorSearchService.search(userId, query, resourceTypes);
        } catch (VectorIndexExternalException e) {
            log.error("工具检索失败 userId={} resourceTypes={} error={}", userId, resourceTypes, e.toString(), e);
            throw new AgentToolFailureException("AGENT_RETRIEVAL_FAILED",
                    "AGENT_RETRIEVAL_FAILED: 检索服务暂时不可用，请稍后重试", true, e);
        }
        if (!outcome.matched() || outcome.hits().isEmpty()) {
            return "未检索到相关内容（matched=false）。请明确告知用户：你的笔记中暂未找到相关内容，不要编造。";
        }
        StringBuilder builder = new StringBuilder();
        builder.append("共检索到 ").append(outcome.hits().size()).append(" 条相关内容：\n\n");
        EvidenceLedger ledger = evidenceLedger(toolContext);
        // P1-E3b：包装用 <evidence id="E1">，没有证据编号就无从包装（生产链路由 Streamer 放入台账），
        // 此时沿用旧格式，保证既有单测/直调行为不变。
        boolean guard = guardEnabled() && ledger != null;
        for (VectorSearchService.VectorHit hit : outcome.hits()) {
            EvidenceSanitizer.ScanResult scan = guard
                    ? EvidenceSanitizer.scan(hit.title(), hit.content())
                    : EvidenceSanitizer.ScanResult.clean();
            String evidenceKey = registerEvidence(ledger, hit.sourceType(), hit.sourceId(),
                    hit.chunkIndex(), hit.score(), hit.title(), hit.content(), scan.suspicious());
            logSuspicious(ledger, evidenceKey, hit.sourceType(), hit.sourceId(), hit.chunkIndex(), scan);
            boolean wrapped = guard && evidenceKey != null;
            builder.append("---\n");
            if (wrapped) {
                builder.append(EvidenceSanitizer.hintLineIfSuspicious(scan));
                builder.append(EvidenceEnvelope.openTag(evidenceKey, hit.sourceType(), hit.sourceId()))
                        .append('\n');
            }
            builder.append(evidencePrefix(evidenceKey));
            builder.append("[来源类型: ").append(hit.sourceType())
                    .append(", 资源ID: ").append(wrapped
                            ? EvidenceEnvelope.escapeText(hit.sourceId()) : hit.sourceId())
                    .append(", 片段: ").append(hit.chunkIndex())
                    .append(", 相似度: ").append(String.format("%.4f", hit.score()))
                    .append("]\n");
            if (!hit.title().isBlank()) {
                builder.append("标题：").append(wrapped
                        ? EvidenceEnvelope.escapeText(hit.title()) : hit.title()).append("\n");
            }
            if (!hit.sourcePath().isEmpty()) {
                String path = String.join(" / ", hit.sourcePath());
                builder.append("路径：").append(wrapped
                        ? EvidenceEnvelope.escapeText(path) : path).append("\n");
            }
            builder.append("内容：\n").append(wrapped
                    ? EvidenceEnvelope.escapeText(hit.content()) : hit.content()).append("\n");
            if (wrapped) {
                builder.append(EvidenceEnvelope.closeTag()).append('\n');
            }
        }
        return builder.toString();
    }

    @Tool(name = "search_my_memory",
            description = "按自然语言语义搜索当前用户自己的长期记忆（偏好、目标、薄弱点、个人信息等）。当回答需要结合用户的长期背景或之前跨会话留下的信息时调用。返回最相关的记忆内容。")
    public String searchMyMemory(
            @ToolParam(description = "要搜索的问题或关键词，例如：我的学习偏好是什么", required = true)
            String query,
            ToolContext toolContext) {
        Long userId = currentUserId(toolContext);
        log.debug("工具 search_my_memory userId={} queryLength={}", userId, length(query));
        // 方案 §P1-5 只点名 documents / mindmap_nodes / questions 三个检索工具；
        // 记忆检索（P1-6 才做相关性）本轮保持单 query 旧路径，避免改到未批准的范围。
        return runSearch(userId, query, List.of(VectorResourceType.MEMORY), toolContext, false);
    }

    private String formatMindmap(MindMapEntity mindMap, MindMapGraph graph, ToolContext toolContext) {
        String outline = formatMindmapOutline(graph);
        String resourceId = String.valueOf(mindMap.getId());
        boolean guard = guardEnabled();
        EvidenceSanitizer.ScanResult scan = guard
                ? EvidenceSanitizer.scan(outline)
                : EvidenceSanitizer.ScanResult.clean();
        String evidenceKey = registerWholeResource(toolContext, "mindmap", resourceId,
                null, outline, scan.suspicious());
        logSuspicious(evidenceLedger(toolContext), evidenceKey, "mindmap", resourceId, 0, scan);
        boolean wrapped = guard && evidenceKey != null;
        return "思维导图结构（精简大纲）\n"
                + (wrapped
                        ? EvidenceSanitizer.hintLineIfSuspicious(scan)
                                + EvidenceEnvelope.openTag(evidenceKey, "mindmap", resourceId) + "\n"
                        : "")
                + evidencePrefix(evidenceKey)
                + "[来源类型: mindmap, mindmapId: " + mindMap.getId()
                + ", documentId: " + mindMap.getDocumentId()
                + ", graphVersion: " + mindMap.getGraphVersion()
                + "]\n\n"
                + (wrapped ? EvidenceEnvelope.escapeText(outline) : outline)
                + (wrapped ? EvidenceEnvelope.closeTag() + "\n" : "");
    }

    private String formatMindmapOutline(MindMapGraph graph) {
        StringBuilder builder = new StringBuilder();
        if (graph == null || graph.isEmpty() || graph.getTree() == null) {
            builder.append("（导图为空）\n");
            return builder.toString();
        }
        int[] visited = {0};
        boolean[] truncatedFlag = {false};
        appendNode(builder, graph.getTree(), 0, visited, truncatedFlag);
        if (truncatedFlag[0]) {
            builder.append("\n[系统提示：导图节点超过 ")
                    .append(properties.getReadMindmapMaxNodes())
                    .append(" 个，仅展示前部分节点，请缩小范围后重试]\n");
        }
        return builder.toString();
    }

    private void appendNode(StringBuilder builder, MindMapNode node, int depth,
                            int[] visited, boolean[] truncatedFlag) {
        if (node == null) {
            return;
        }
        if (visited[0] >= properties.getReadMindmapMaxNodes()) {
            truncatedFlag[0] = true;
            return;
        }
        visited[0]++;
        builder.append("  ".repeat(depth)).append("- ");
        if (node.getId() != null) {
            builder.append("[").append(node.getId()).append("] ");
        }
        builder.append(node.getLabel() == null ? "" : node.getLabel());
        if (node.getSummary() != null && !node.getSummary().isBlank()) {
            builder.append("：").append(node.getSummary());
        }
        builder.append("\n");
        for (MindMapNode child : node.getChildren()) {
            appendNode(builder, child, depth + 1, visited, truncatedFlag);
        }
    }

    /**
     * P1-E3b 总开关 {@code app.agent.security.evidence-guard-enabled}（默认 true）。
     *
     * <p>关掉时本类的输出与 P1-E3b 之前<b>逐字节一致</b>：不包装、不转义、不扫可疑、不加提示行。
     * 这是回滚开关，不是“下调强度”的旋钮。
     */
    private boolean guardEnabled() {
        return properties == null || properties.isEvidenceGuardEnabled();
    }

    /**
     * 从 ToolContext 取本 run 的证据台账；未接线（如单测直接调工具）时返回 null，
     * 此时工具输出保持原有格式、不带 {@code [E]} 编号。
     */
    private static EvidenceLedger evidenceLedger(ToolContext toolContext) {        if (toolContext == null) {
            return null;
        }
        Object value = toolContext.getContext().get(AgentToolContextKeys.EVIDENCE_LEDGER);
        return value instanceof EvidenceLedger ledger ? ledger : null;
    }

    private static String registerEvidence(EvidenceLedger ledger, String resourceType, String resourceId,
                                           Integer chunkIndex, double score, String title, String snippet,
                                           boolean suspicious) {
        if (ledger == null) {
            return null;
        }
        return ledger.register(resourceType, resourceId, chunkIndex, score, title, snippet, suspicious);
    }

    /** 整篇读取（read_my_document / read_mindmap）：记 1 条，chunkIndex=0、score=1.0。 */
    private static String registerWholeResource(ToolContext toolContext, String resourceType,
                                                String resourceId, String title, String snippet,
                                                boolean suspicious) {
        return registerEvidence(evidenceLedger(toolContext), resourceType, resourceId, 0, 1.0d,
                title, truncateSnippet(snippet), suspicious);
    }

    /**
     * P1-E3b：命中疑似指令文本时的告警。
     *
     * <p><b>只打 runId / 证据号 / 资料坐标 / 规则名，绝不打资料正文</b>——日志会进日志文件、
     * 可能被打包给别人，正文属于用户隐私。
     *
     * <p>runId 从台账上取（台账就是按 run 建的），不依赖 ThreadLocal：工具在虚拟线程里执行，
     * ThreadLocal/MDC 不会自动传递（项目既有教训）。
     */
    private void logSuspicious(EvidenceLedger ledger, String evidenceKey, String resourceType,
                               String resourceId, Integer chunkIndex, EvidenceSanitizer.ScanResult scan) {
        if (scan == null || !scan.suspicious()) {
            return;
        }
        log.warn("证据含疑似指令文本 runId={} evidenceKey={} resourceType={} resourceId={} chunkIndex={} rules={}",
                ledger == null ? null : ledger.runId(), evidenceKey, resourceType, resourceId,
                chunkIndex, String.join(",", scan.ruleNames()));
    }

    private static String evidencePrefix(String evidenceKey) {
        return evidenceKey == null ? "" : "[" + evidenceKey + "] ";
    }

    private static String truncateSnippet(String snippet) {
        if (snippet == null || snippet.length() <= EVIDENCE_SNIPPET_MAX_CHARS) {
            return snippet;
        }
        return snippet.substring(0, EVIDENCE_SNIPPET_MAX_CHARS) + "…";
    }

    private static Long currentUserId(ToolContext toolContext) {
        if (toolContext == null) {
            throw new AgentToolFailureException("AGENT_CONTEXT_MISSING",
                    "AGENT_CONTEXT_MISSING: 缺少工具上下文", true);
        }
        Object value = toolContext.getContext().get(AgentToolContextKeys.USER_ID);
        if (!(value instanceof Long userId)) {
            throw new AgentToolFailureException("AGENT_CONTEXT_MISSING",
                    "AGENT_CONTEXT_MISSING: 工具上下文中缺少当前用户", true);
        }
        return userId;
    }

    private static Long parseResourceId(String resourceId) {
        if (resourceId == null || resourceId.isBlank()) {
            // P1-D1：参数类错误标记 retryable，交给 TracingToolCallback 返回纠错提示而不是中止整轮
            throw new AgentToolFailureException("AGENT_ARGUMENT_INVALID",
                    "AGENT_ARGUMENT_INVALID: 资源 ID 不能为空", false, true);
        }
        try {
            return Long.parseLong(resourceId.trim());
        } catch (NumberFormatException e) {
            throw new AgentToolFailureException("AGENT_ARGUMENT_INVALID",
                    "AGENT_ARGUMENT_INVALID: 资源 ID 格式不正确：" + resourceId, false, true, e);
        }
    }

    private static int length(String text) {
        return text == null ? 0 : text.length();
    }
}
