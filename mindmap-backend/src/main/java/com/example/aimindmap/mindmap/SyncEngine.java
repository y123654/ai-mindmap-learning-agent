package com.example.aimindmap.mindmap;

import com.example.aimindmap.ai.AiGateway;
import com.example.aimindmap.ai.AiJsonRequest;
import com.example.aimindmap.ai.AiJsonResult;
import com.example.aimindmap.ai.AiUsage;
import com.example.aimindmap.ai.dto.AiOperationResponse;
import com.example.aimindmap.mindmap.graph.MindMapGraph;
import com.example.aimindmap.parser.BlockType;
import com.example.aimindmap.parser.MarkdownBlock;
import com.example.aimindmap.parser.MarkdownBlockDiffer;
import com.example.aimindmap.parser.MarkdownDiff;
import com.example.aimindmap.parser.MarkdownParser;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * v1.1 §13.2 同步引擎（阶段 3）。
 * 流程：解析 → 比较 → 规则操作（标题/列表/删除建议）→ 必要时 AI 归纳段落
 * → 合并 → 校验 → 目标图预览。任一环节失败抛出异常，当前已发布图不被修改（§5.3）。
 *
 * <p>三级裁剪激活时（本次预计新增超过默认上限），合并后的操作会先经过
 * {@link MindMapNodeBudget} 做节点预算与折叠，把超宽的列表项折进父节点 summary，
 * 再按 {@link MindMapProperties#getMaxNewNodes()} 校验；不触发裁剪的文档行为不变。
 */
@Service
public class SyncEngine {

    private static final Logger log = LoggerFactory.getLogger(SyncEngine.class);

    private final MarkdownParser markdownParser;
    private final MarkdownBlockDiffer blockDiffer;
    private final RuleOperationBuilder ruleOperationBuilder;
    private final MindMapPromptService promptService;
    private final AiGateway aiGateway;
    private final AiOperationConverter aiOperationConverter;
    private final MindMapOperationValidator operationValidator;
    private final GraphPreviewBuilder graphPreviewBuilder;
    private final MindMapDepthCap depthCap;
    private final MindMapNodeBudget nodeBudget;
    private final MindMapProperties mindMapProperties;

    public SyncEngine(MarkdownParser markdownParser,
                      MarkdownBlockDiffer blockDiffer,
                      RuleOperationBuilder ruleOperationBuilder,
                      MindMapPromptService promptService,
                      AiGateway aiGateway,
                      AiOperationConverter aiOperationConverter,
                      MindMapOperationValidator operationValidator,
                      GraphPreviewBuilder graphPreviewBuilder,
                      MindMapDepthCap depthCap,
                      MindMapNodeBudget nodeBudget,
                      MindMapProperties mindMapProperties) {
        this.markdownParser = markdownParser;
        this.blockDiffer = blockDiffer;
        this.ruleOperationBuilder = ruleOperationBuilder;
        this.promptService = promptService;
        this.aiGateway = aiGateway;
        this.aiOperationConverter = aiOperationConverter;
        this.operationValidator = operationValidator;
        this.graphPreviewBuilder = graphPreviewBuilder;
        this.depthCap = depthCap;
        this.nodeBudget = nodeBudget;
        this.mindMapProperties = mindMapProperties;
    }

    public SyncPreview prepare(String documentTitle,
                               String newMarkdown,
                               List<MarkdownBlock> oldBlocks,
                               MindMapGraph currentGraph) {
        return prepare(documentTitle, newMarkdown, oldBlocks, currentGraph, stage -> { });
    }

    public SyncPreview prepare(String documentTitle,
                               String newMarkdown,
                               List<MarkdownBlock> oldBlocks,
                               MindMapGraph currentGraph,
                               SyncProgress progress) {
        progress.onStage(SyncStatus.PARSING.name());
        List<MarkdownBlock> newBlocks = markdownParser.parse(newMarkdown);

        progress.onStage(SyncStatus.DIFFING.name());
        MarkdownDiff diff = blockDiffer.diff(oldBlocks, newBlocks);

        // §9.1：规则与 AI 共用同一个正式 ID 生成器，避免 ID 冲突
        NodeIdGenerator idGenerator = NodeIdGenerator.basedOn(currentGraph);
        RuleOperationBuilder.BuildResult ruleResult =
                ruleOperationBuilder.build(newBlocks, diff, currentGraph, documentTitle, idGenerator);

        List<ValidatedOperation> aiOperations = new ArrayList<>();
        AiCallMeta aiMeta = null;

        AiSyncContext rawAiContext = buildAiContext(diff, currentGraph, ruleResult.operations());

        // §9.4 / §14.5.4：预计新增节点超过默认上限 100 时，本次只生成“前三级”内容
        // （H1–H3 标题、一级缩进列表项、挂载深度 ≤ 3 的段落），深层嵌套列表不生成。
        MindMapDepthCap.Decision capDecision = depthCap.decide(
                currentGraph, ruleResult.operations(), rawAiContext.allNewParagraphBlocks().size());

        List<ValidatedOperation> effectiveRuleOperations = ruleResult.operations();
        AiSyncContext aiContext = rawAiContext;
        Set<String> allowedBlockIds = null;
        if (capDecision.active()) {
            allowedBlockIds = depthCap.allowedRuleBlockIds(newBlocks);
            effectiveRuleOperations =
                    depthCap.filterRuleOperations(ruleResult.operations(), allowedBlockIds);
            aiContext = depthCap.filterAiContext(new AiSyncContext(
                    currentGraph,
                    rawAiContext.addedParagraphs(),
                    rawAiContext.changedParagraphs(),
                    rawAiContext.uncertainParagraphs(),
                    effectiveRuleOperations));
        }

        List<MindMapPromptService.AiPromptBatch> aiBatches = promptService.buildBatches(aiContext);
        if (!aiBatches.isEmpty()) {
            progress.onStage(SyncStatus.AI_GENERATING.name());
            AiUsage totalUsage = AiUsage.empty();
            long totalElapsedMillis = 0L;
            for (MindMapPromptService.AiPromptBatch batch : aiBatches) {
                AiJsonResult<AiOperationResponse> result =
                        aiGateway.completeJsonForResult(batch.request(), AiOperationResponse.class);
                totalUsage = totalUsage.plus(result.usage());
                totalElapsedMillis += result.elapsedMillis();
                aiOperations.addAll(aiOperationConverter.convert(
                        result.value(), batch.context(), idGenerator));
            }
            aiMeta = new AiCallMeta(aiGateway.provider(), aiGateway.model(),
                    totalElapsedMillis, totalUsage);
        }

        progress.onStage(SyncStatus.VALIDATING.name());
        List<ValidatedOperation> mergedOperations = new ArrayList<>(effectiveRuleOperations);
        mergedOperations.addAll(aiOperations);

        if (capDecision.active()) {
            MindMapDepthCap.FilterResult filterResult =
                    depthCap.filterMergedOperations(mergedOperations, allowedBlockIds);
            mergedOperations = filterResult.operations();

            // 三级裁剪只按深度裁，救不了“同级列表项很多”的宽文档：
            // 这里把超出上限的列表项/段落折进父节点 summary，保证新增节点数 ≤ 上限，不再整单失败。
            MindMapNodeBudget.BudgetResult budget =
                    nodeBudget.apply(mergedOperations, currentGraph, mindMapProperties);
            mergedOperations = budget.operations();
            if (budget.foldedAdds() > 0) {
                // MindMapNodeBudget 内部已有一行同样的折叠日志，这里只补“引擎侧已应用”的计数，避免两条文案重复
                log.info("sync 引擎已应用节点预算：折叠 {} 项，保留新增 {} 个，涉及 {} 个父节点，摘要截断 {} 次",
                        budget.foldedAdds(), budget.keptAdds(),
                        budget.foldedParents(), budget.truncatedSummaries());
            }

            // 三级裁剪 + 节点预算后允许达到配置上限（默认 200，§9.4 第 8 条硬上限），常规同步仍是 100
            operationValidator.validate(mergedOperations, currentGraph,
                    mindMapProperties.getMaxNewNodes());
        } else {
            log.info("本次同步不做层级裁剪，执行常规校验，参与校验的操作共 {} 个", mergedOperations.size());
            operationValidator.validate(mergedOperations, currentGraph);
        }
        MindMapGraph targetGraph = graphPreviewBuilder.preview(currentGraph, mergedOperations);

        return new SyncPreview(mergedOperations, targetGraph, diff, newBlocks, aiMeta);
    }

    private AiSyncContext buildAiContext(MarkdownDiff diff,
                                         MindMapGraph currentGraph,
                                         List<ValidatedOperation> ruleOperations) {
        List<MarkdownBlock> addedParagraphs = diff.added().stream()
                .filter(b -> b.type() == BlockType.PARAGRAPH)
                .toList();
        List<MarkdownDiff.ChangedBlock> changedParagraphs = diff.changed().stream()
                .filter(pair -> pair.newBlock().type() == BlockType.PARAGRAPH)
                .toList();
        List<MarkdownDiff.ChangedBlock> uncertainParagraphs = diff.uncertain().stream()
                .filter(pair -> pair.newBlock().type() == BlockType.PARAGRAPH)
                .toList();
        return new AiSyncContext(currentGraph, addedParagraphs, changedParagraphs,
                uncertainParagraphs, ruleOperations);
    }


    /** 同步阶段回调（任务状态机持久化 + SSE 推送）。 */
    @FunctionalInterface
    public interface SyncProgress {
        void onStage(String status);
    }

    /** AI 调用记录（写入 change_summary.ai，§16.4），不改表结构。 */
    public record AiCallMeta(
            String provider,
            String model,
            long elapsedMillis,
            AiUsage usage
    ) {
    }

    /**
     * @param operations  校验通过的正式操作（规则 + AI）
     * @param targetGraph 完整目标图（无变化时与当前图为同一对象、版本不变）
     * @param diff        块差异（写入任务 change_summary）
     * @param newBlocks   新版本全部块（发布成功后写快照）
     * @param aiMeta      AI 调用元数据；本次未调用 AI 时为 null
     */
    public record SyncPreview(
            List<ValidatedOperation> operations,
            MindMapGraph targetGraph,
            MarkdownDiff diff,
            List<MarkdownBlock> newBlocks,
            AiCallMeta aiMeta
    ) {
        public boolean hasChanges() {
            return !operations.isEmpty();
        }
    }
}