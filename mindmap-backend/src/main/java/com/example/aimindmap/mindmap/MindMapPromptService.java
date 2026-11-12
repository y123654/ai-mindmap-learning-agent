package com.example.aimindmap.mindmap;

import com.example.aimindmap.ai.AiGatewayException;
import com.example.aimindmap.ai.AiJsonRequest;
import com.example.aimindmap.ai.AiProperties;
import com.example.aimindmap.mindmap.graph.MindMapNode;
import com.example.aimindmap.parser.MarkdownBlock;
import com.example.aimindmap.parser.MarkdownDiff;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * v1.1 §12.1 思维导图增量提示词构造，以及 §16.4 单次请求字符上限控制。
 */
@Service
public class MindMapPromptService {

    /** JSON 外层结构需要预留的输出 token。 */
    private static final int RESERVED_OUTPUT_TOKENS = 300;
    /** 一个段落对应一个操作，按较保守的 token 体积估算，避免 JSON 被 max_tokens 截断。 */
    private static final int ESTIMATED_TOKENS_PER_PARAGRAPH = 280;
    /** 即使把 AI_MAX_OUTPUT_TOKENS 调得很大，也保持小批量，降低长 JSON 失败概率。 */
    private static final int MAX_PARAGRAPHS_PER_BATCH = 6;
    private static final int MIN_CHANGED_BLOCK_CHARS = 500;
    private static final int PROMPT_OVERHEAD_CHARS = 128;

    private static final String SYSTEM_PROMPT = """
            你是一个学科知识结构化助手。

            任务：
            根据“新增或修改的 Markdown 普通段落”和“当前思维导图”，生成增量操作建议。

            硬性规则：
            1. 只能返回 JSON。
            2. 不允许返回 Markdown 代码块。
            3. 不允许删除节点；删除内容由后端规则处理，你不能返回 propose_remove_source_node。
            4. 不允许修改 sourceType=manual 的节点。
            5. 不允许覆盖 manuallyEdited=true 的节点。
            6. 新增节点的临时 ID 必须使用 tmp_ 前缀（如 tmp_001）。
            7. 正式节点 ID 由后端生成，你不能伪造已有正式节点 ID；新增节点 node 对象里不允许带 id。
            8. 每个节点标题不超过 20 个中文字符。
            9. 每个 summary 不超过 60 个中文字符。
            10. 节点层级最多 6 层。
            11. 只能依据 Markdown 内容做适度归纳，不能编造没有依据的事实；没有有效内容时返回 noop。
            12. 每个 changedBlocks 输入块最多返回一个 add_node，并且必须把该输入块的 blockId
                原样填入 node.sourceBlockId；禁止为同一段落返回分组节点、目录节点或多个层级。
            13. 新增的普通段落节点必须是“单个具体知识点”，禁止使用 parentClientTempId，
                parentNodeId/afterNodeId 一律返回 null；后端会自动把节点平铺到该段落所属标题下。
            14. 禁止照抄或改写 Markdown 标题作为节点标题，禁止生成“一、什么是 Agent”
                “1. 大语言模型”这类章节名；标题必须是该段落的具体要点，而不是文章目录。
            15. 禁止新增根节点或重复 currentMindmap 里已有的标题结构；不要概括整篇文档，
                只概括当前 blockId 对应段落本身。
            16. 修改已有 AI 节点只能用 update_node，nodeId 必须取自 currentMindmap 中、
                且其 sourceBlockId 出现在输入块 fromBlockId 里的节点。
            17. 后端可能把本次同步拆成多个小批次请求；你每次只能处理本次 changedBlocks 中
                实际列出的 blockId，禁止为未列出的块返回操作。

            信息抽取与降噪规则：
            18. 把当前普通段落压缩成一个短知识点：优先保留核心名词、关键结论、重要限制，
                删除铺垫、过渡语、重复解释和修饰性长句。
            19. 禁止直接粘贴整段原文；label 必须是短标题，summary 只能是一句简短说明。
            20. 遇到案例、故事或举例，不复制案例过程，只提炼案例支持的核心结论；
                如果案例没有明确结论，则返回 noop。
            21. 同一段内语义重复的内容合并表达；风险、警告、约束、前提条件不能丢失。
            22. “备注、补充说明、注意事项”等普通段落仍归入它所在章节，禁止为此新增根节点、
                主分支或目录节点。
            23. 严格遵守输入块的 headingPath：不能把 A 章节段落归纳到 B 章节；不得通过
                parentNodeId、parentClientTempId 或 afterNodeId 改变后端归属。

            输出前三轮自检：
            24. 完整性：本次 changedBlocks 中每个有效块都要有 add_node 或 noop 结论，不能漏块。
            25. 层级：所有新增节点的父相关字段都为 null，正式归属由后端按 headingPath 决定。
            26. 内容：没有编造原文不存在的知识，没有长段复制，没有 Mermaid、XMind、图片或解释文字。

            返回格式：
            {
              "operations": [
                {
                  "type": "add_node | update_node | noop",
                  "clientTempId": "tmp_001",
                  "parentClientTempId": null,
                  "parentNodeId": "node_001",
                  "nodeId": null,
                  "afterNodeId": null,
                  "node": {
                    "label": "节点标题",
                    "summary": "简短说明",
                    "sourceType": "ai_enhanced",
                    "sourcePath": [],
                    "sourceBlockId": "blk_xxx"
                  },
                  "fields": null,
                  "reason": "简短中文原因"
                }
              ]
            }
            """;

    private final ObjectMapper objectMapper;
    private final AiProperties aiProperties;

    public MindMapPromptService(ObjectMapper objectMapper, AiProperties aiProperties) {
        this.objectMapper = objectMapper;
        this.aiProperties = aiProperties;
    }

    /**
     * 把普通段落按输出 token 风险和输入字符上限拆成小批次。
     * 每个批次仍携带同一份规则操作，以便 AI 节点能挂到本次新增/修改的 Markdown 标题下。
     */
    public List<AiPromptBatch> buildBatches(AiSyncContext context) {
        if (!context.required()) {
            return List.of();
        }

        String currentMindmapJson = toJson(compactGraph(context));
        int maxBlocks = Math.max(1, Math.min(
                MAX_PARAGRAPHS_PER_BATCH,
                (aiProperties.getMaxOutputTokens() - RESERVED_OUTPUT_TOKENS)
                        / ESTIMATED_TOKENS_PER_PARAGRAPH));
        int maxBlockChars = Math.max(
                MIN_CHANGED_BLOCK_CHARS,
                aiProperties.getMaxPromptChars() - currentMindmapJson.length() - PROMPT_OVERHEAD_CHARS);

        PromptBatchBuilder builder = new PromptBatchBuilder(context, maxBlocks, maxBlockChars);
        for (MarkdownBlock block : context.addedParagraphs()) {
            builder.addAdded(block);
        }
        for (MarkdownDiff.ChangedBlock pair : context.changedParagraphs()) {
            builder.addChanged(pair);
        }
        for (MarkdownDiff.ChangedBlock pair : context.uncertainParagraphs()) {
            builder.addUncertain(pair);
        }

        List<AiPromptBatch> batches = new ArrayList<>();
        for (AiSyncContext batchContext : builder.finish()) {
            batches.add(new AiPromptBatch(batchContext, buildRequest(batchContext)));
        }
        return batches;
    }

    public AiJsonRequest buildRequest(AiSyncContext context) {
        Map<String, Object> userContent = new LinkedHashMap<>();
        userContent.put("currentMindmap", compactGraph(context));
        userContent.put("changedBlocks", changedBlocks(context));

        String userPrompt = toJson(userContent);
        if (userPrompt.length() > aiProperties.getMaxPromptChars()) {
            throw new AiGatewayException("AI_INPUT_TOO_LARGE",
                    "本次要交给 AI 的内容超过单次上限 " + aiProperties.getMaxPromptChars()
                            + " 字符（当前 " + userPrompt.length()
                            + "）。请把文档拆小后分批同步（长文档按章节分批属于后续增强）。");
        }
        return new AiJsonRequest(SYSTEM_PROMPT, userPrompt, true,
                aiProperties.getMaxOutputTokens(), aiProperties.getTemperature());
    }

    private List<Map<String, Object>> compactGraph(AiSyncContext context) {
        List<Map<String, Object>> nodes = new ArrayList<>();
        if (context.currentGraph() != null && context.currentGraph().getTree() != null) {
            append(context.currentGraph().getTree(), nodes);
        }
        return nodes;
    }

    private void append(MindMapNode node, List<Map<String, Object>> nodes) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", node.getId());
        item.put("label", node.getLabel());
        if (node.getSummary() != null && !node.getSummary().isBlank()) {
            item.put("summary", abbreviate(node.getSummary(), 80));
        }
        item.put("sourceType", node.getSourceType());
        item.put("manuallyEdited", node.isManuallyEdited());
        item.put("manualLocked", node.isManualLocked());
        item.put("status", node.getStatus());
        if (node.getSourceBlockId() != null) {
            item.put("sourceBlockId", node.getSourceBlockId());
        }
        if (node.getSourcePath() != null && !node.getSourcePath().isEmpty()) {
            item.put("sourcePath", node.getSourcePath());
        }
        nodes.add(item);
        node.getChildren().forEach(child -> append(child, nodes));
    }

    private List<Map<String, Object>> changedBlocks(AiSyncContext context) {
        List<Map<String, Object>> blocks = new ArrayList<>();
        for (MarkdownBlock block : context.addedParagraphs()) {
            blocks.add(blockEntry("added", null, block));
        }
        for (MarkdownDiff.ChangedBlock pair : context.changedParagraphs()) {
            blocks.add(blockEntry("changed", pair.oldBlock(), pair.newBlock()));
        }
        for (MarkdownDiff.ChangedBlock pair : context.uncertainParagraphs()) {
            blocks.add(blockEntry("uncertain", pair.oldBlock(), pair.newBlock()));
        }
        return blocks;
    }

    private Map<String, Object> blockEntry(String change, MarkdownBlock oldBlock, MarkdownBlock newBlock) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("change", change);
        entry.put("blockId", newBlock.blockId());
        entry.put("headingPath", newBlock.headingPath());
        entry.put("text", newBlock.normalizedText());
        if (oldBlock != null) {
            entry.put("fromBlockId", oldBlock.blockId());
        }
        return entry;
    }

    private static String abbreviate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("提示词 JSON 序列化失败", e);
        }
    }

    /** 一次 AI 请求及其对应的小批量上下文。 */
    public record AiPromptBatch(
            AiSyncContext context,
            AiJsonRequest request
    ) {
    }

    private final class PromptBatchBuilder {

        private final AiSyncContext originalContext;
        private final int maxBlocks;
        private final int maxBlockChars;
        private final List<AiSyncContext> finished = new ArrayList<>();
        private final List<MarkdownBlock> added = new ArrayList<>();
        private final List<MarkdownDiff.ChangedBlock> changed = new ArrayList<>();
        private final List<MarkdownDiff.ChangedBlock> uncertain = new ArrayList<>();
        private int blockCount;
        private int blockChars;

        private PromptBatchBuilder(AiSyncContext originalContext, int maxBlocks, int maxBlockChars) {
            this.originalContext = originalContext;
            this.maxBlocks = maxBlocks;
            this.maxBlockChars = maxBlockChars;
        }

        private void addAdded(MarkdownBlock block) {
            int nextChars = toJson(blockEntry("added", null, block)).length();
            flushIfNeeded(nextChars);
            added.add(block);
            blockCount++;
            blockChars += nextChars;
        }

        private void addChanged(MarkdownDiff.ChangedBlock pair) {
            int nextChars = toJson(blockEntry("changed", pair.oldBlock(), pair.newBlock())).length();
            flushIfNeeded(nextChars);
            changed.add(pair);
            blockCount++;
            blockChars += nextChars;
        }

        private void addUncertain(MarkdownDiff.ChangedBlock pair) {
            int nextChars = toJson(blockEntry("uncertain", pair.oldBlock(), pair.newBlock())).length();
            flushIfNeeded(nextChars);
            uncertain.add(pair);
            blockCount++;
            blockChars += nextChars;
        }

        private void flushIfNeeded(int nextChars) {
            if (blockCount > 0
                    && (blockCount >= maxBlocks || blockChars + nextChars > maxBlockChars)) {
                flush();
            }
        }

        private void flush() {
            if (blockCount == 0) {
                return;
            }
            finished.add(new AiSyncContext(
                    originalContext.currentGraph(),
                    List.copyOf(added),
                    List.copyOf(changed),
                    List.copyOf(uncertain),
                    originalContext.ruleOperations()));
            added.clear();
            changed.clear();
            uncertain.clear();
            blockCount = 0;
            blockChars = 0;
        }

        private List<AiSyncContext> finish() {
            flush();
            return List.copyOf(finished);
        }
    }
}

