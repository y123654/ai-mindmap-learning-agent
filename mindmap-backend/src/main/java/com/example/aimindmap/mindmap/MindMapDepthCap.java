package com.example.aimindmap.mindmap;

import com.example.aimindmap.mindmap.graph.MindMapGraph;
import com.example.aimindmap.mindmap.graph.MindMapNode;
import com.example.aimindmap.parser.BlockType;
import com.example.aimindmap.parser.MarkdownBlock;
import com.example.aimindmap.parser.MarkdownDiff;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * v1.1 §9.4 / §14.5.4（2026-09-17 用户选定“偏向 C”方案）：
 * 大导图三级裁剪。
 *
 * <p>规则：当本次同步预计新增节点总数（规则标题/列表节点 + 段落 AI 节点估计数）
 * 超过单次默认上限 {@link MindMapOperationValidator#MAX_NEW_NODES}（100）时，
 * 本次同步只生成“前三级”内容：
 * <ul>
 *   <li>标题只生成 H1–H3（中心主题 + 一级、二级标题）；</li>
 *   <li>无序列表只生成一级缩进的列表项（如“感知、思考、行动”），
 *       二级及更深嵌套列表项（如“接收用户输入”）本次不生成；</li>
 *   <li>段落 AI 节点只保留挂载后深度 ≤ 3 的节点。</li>
 * </ul>
 *
 * <p>深层 Markdown 内容仍保留在文档与块快照中，只是本次不生成对应节点；
 * 裁剪发生在“操作合并前后、校验前”，不改表结构、不改接口契约；
 * update_node / propose_remove_source_node 操作不受影响。
 * 裁剪后仍受 {@link MindMapOperationValidator#MAX_NEW_NODES_HARD}（200）硬上限保护。
 */
@Component
public class MindMapDepthCap {

    private static final Logger log = LoggerFactory.getLogger(MindMapDepthCap.class);

    /** 根节点为深度 0；只生成到第 3 级（中心主题 + 一、二、三级）。 */
    public static final int MAX_GENERATED_DEPTH = 3;
    /** 允许生成的最深 Markdown 标题级别：H1–H3。 */
    public static final int MAX_HEADING_LEVEL = 3;
    /** 只生成该缩进级别的无序列表项：一级缩进。 */
    public static final int MIN_BULLET_INDENT = 1;

    /**
     * 依据“规则操作 + 段落 AI 节点估计数”预判本次新增节点总数，决定是否启用三级裁剪。
     *
     * @param currentGraph           当前已发布图（增量同步时已有节点不计入新增）
     * @param ruleOperations         规则构建出的全部操作（标题/列表，未裁剪）
     * @param predictedParagraphAdds 段落 AI 节点估计新增数（按送 AI 的段落块数计上限）
     */
    public Decision decide(MindMapGraph currentGraph,
                           List<ValidatedOperation> ruleOperations,
                           int predictedParagraphAdds) {
        int ruleAdds = 0;
        for (ValidatedOperation op : ruleOperations) {
            if (isAdd(op)) {
                ruleAdds++;
            }
        }
        int predictedAdds = ruleAdds + Math.max(0, predictedParagraphAdds);
        boolean active = predictedAdds > MindMapOperationValidator.MAX_NEW_NODES;

        if (active) {
            log.warn("本次同步预计新增 {} 个节点（规则节点 {} 个 + 段落节点估计 {} 个），"
                            + "超过默认上限 {} 个，启用三级裁剪：只生成 H1-H3 标题、一级缩进列表项，"
                            + "以及挂载深度 ≤ {} 的段落节点",
                    predictedAdds, ruleAdds, Math.max(0, predictedParagraphAdds),
                    MindMapOperationValidator.MAX_NEW_NODES, MAX_GENERATED_DEPTH);
        } else {
            log.info("本次同步预计新增 {} 个节点（规则节点 {} 个 + 段落节点估计 {} 个），"
                            + "未超过默认上限 {} 个，不做层级裁剪",
                    predictedAdds, ruleAdds, Math.max(0, predictedParagraphAdds),
                    MindMapOperationValidator.MAX_NEW_NODES);
        }
        return new Decision(active, predictedAdds);
    }

    /** 三级裁剪允许生成节点的 Markdown 知识块 ID：H1–H3 标题 + 一级缩进无序列表项。 */
    public Set<String> allowedRuleBlockIds(List<MarkdownBlock> newBlocks) {
        Set<String> ids = new HashSet<>();
        for (MarkdownBlock block : newBlocks) {
            if (block.type() == BlockType.HEADING && block.level() <= MAX_HEADING_LEVEL) {
                ids.add(block.blockId());
            } else if (block.type() == BlockType.BULLET_LIST_ITEM) {
                int indent = block.level() == null ? 1 : block.level();
                if (indent == MIN_BULLET_INDENT) {
                    ids.add(block.blockId());
                }
            }
        }
        return ids;
    }

    /**
     * 段落块在唯一 H1 根下的挂载深度约等于 headingPath 层数
     * （H2 段落深度 2、H3 段落深度 3）。深度兜底在操作合并后再做一次。
     */
    public boolean paragraphAllowed(MarkdownBlock paragraphBlock) {
        if (paragraphBlock == null) {
            return false;
        }
        int pathDepth = paragraphBlock.headingPath() == null ? 0 : paragraphBlock.headingPath().size();
        return pathDepth <= MAX_GENERATED_DEPTH;
    }

    /** 过滤规则新增操作：丢弃 H4+ 标题和二级及更深缩进列表项对应的 add_node。 */
    public List<ValidatedOperation> filterRuleOperations(List<ValidatedOperation> ruleOperations,
                                                         Set<String> allowedBlockIds) {
        List<ValidatedOperation> kept = new ArrayList<>();
        int dropped = 0;
        for (ValidatedOperation op : ruleOperations) {
            if (isAdd(op) && op.node() != null
                    && "markdown".equals(op.node().getSourceType())
                    && op.node().getSourceBlockId() != null
                    && !allowedBlockIds.contains(op.node().getSourceBlockId())) {
                dropped++;
                continue;
            }
            kept.add(op);
        }
        log.info("三级裁剪（规则阶段）：保留操作 {} 个，丢弃 H4+ 标题/深层嵌套列表新增 {} 个",
                kept.size(), dropped);
        return kept;
    }

    /**
     * 合并规则与 AI 操作后做最终裁剪：
     * Markdown 来源节点再次按允许块 ID 过滤；AI 段落节点只按深度兜底（≤3）。
     */
    public FilterResult filterMergedOperations(List<ValidatedOperation> mergedOperations,
                                               Set<String> allowedBlockIds) {
        Map<String, Integer> depthById = new HashMap<>();
        assignBatchDepths(depthById, mergedOperations);

        List<ValidatedOperation> kept = new ArrayList<>();
        int droppedStructural = 0;
        int droppedDeep = 0;
        int keptAdds = 0;
        for (ValidatedOperation op : mergedOperations) {
            if (!isAdd(op)) {
                kept.add(op);
                continue;
            }
            MindMapNode node = op.node();
            if (node != null
                    && "markdown".equals(node.getSourceType())
                    && node.getSourceBlockId() != null
                    && !allowedBlockIds.contains(node.getSourceBlockId())) {
                droppedStructural++;
                log.debug("三级裁剪丢弃结构外 Markdown 节点 nodeId={} label={}",
                        op.nodeId(), node.getLabel());
                continue;
            }
            int depth = depthById.getOrDefault(op.nodeId(), 0);
            if (depth > MAX_GENERATED_DEPTH) {
                droppedDeep++;
                log.debug("三级裁剪丢弃过深节点 nodeId={} depth={} label={}",
                        op.nodeId(), depth, node == null ? null : node.getLabel());
                continue;
            }
            keptAdds++;
            kept.add(op);
        }
        log.info("三级裁剪（合并阶段）完成：保留新增节点 {} 个、其它操作 {} 个；"
                        + "丢弃结构外节点 {} 个、深度大于 {} 的节点 {} 个",
                keptAdds, kept.size() - keptAdds, droppedStructural,
                MAX_GENERATED_DEPTH, droppedDeep);
        return new FilterResult(kept, keptAdds, droppedStructural + droppedDeep);
    }

    /** 按新构建的 AI 上下文段落白名单过滤 AI 上下文（裁剪激活时不把深层段落送给 AI）。 */
    public AiSyncContext filterAiContext(AiSyncContext context) {
        List<MarkdownBlock> added = context.addedParagraphs().stream()
                .filter(this::paragraphAllowed).toList();
        List<MarkdownDiff.ChangedBlock> changed = context.changedParagraphs().stream()
                .filter(pair -> paragraphAllowed(pair.newBlock())).toList();
        List<MarkdownDiff.ChangedBlock> uncertain = context.uncertainParagraphs().stream()
                .filter(pair -> paragraphAllowed(pair.newBlock())).toList();
        int removed = context.addedParagraphs().size() - added.size()
                + context.changedParagraphs().size() - changed.size()
                + context.uncertainParagraphs().size() - uncertain.size();
        if (removed > 0) {
            log.info("三级裁剪：{} 个深层段落不送 AI 归纳", removed);
        }
        return new AiSyncContext(context.currentGraph(), added, changed, uncertain,
                context.ruleOperations());
    }

    private static boolean isAdd(ValidatedOperation op) {
        return op != null && "add_node".equals(op.type());
    }

    /**
     * 多趟扫描为批次 add_node 分配深度：父节点可能是同批次更早的节点
     * （规则标题在前，AI 平铺段落节点的父一定是标题节点）。
     */
    private void assignBatchDepths(Map<String, Integer> depths, List<ValidatedOperation> operations) {
        List<ValidatedOperation> adds = new ArrayList<>();
        for (ValidatedOperation op : operations) {
            if (isAdd(op)) {
                adds.add(op);
            }
        }
        boolean changed = true;
        int guard = 0;
        while (changed && guard++ <= adds.size() + 1) {
            changed = false;
            for (ValidatedOperation op : adds) {
                if (depths.containsKey(op.nodeId())) {
                    continue;
                }
                if (op.parentId() == null) {
                    depths.put(op.nodeId(), 0);
                    changed = true;
                    continue;
                }
                Integer parentDepth = depths.get(op.parentId());
                if (parentDepth != null) {
                    depths.put(op.nodeId(), parentDepth + 1);
                    changed = true;
                }
            }
        }
        // 父节点无法解析的新增节点（理论上不应出现，后续校验会报错）按深度 0 兜底，避免 NPE
        for (ValidatedOperation op : adds) {
            depths.putIfAbsent(op.nodeId(), 0);
        }
    }

    /**
     * @param active         是否启用三级裁剪
     * @param predictedAdds  预计新增节点总数（裁剪前）
     */
    public record Decision(boolean active, int predictedAdds) {
    }

    /**
     * @param operations   裁剪后保留的全部操作
     * @param keptAdds     保留的新增节点数
     * @param droppedAdds  被丢弃的新增节点数
     */
    public record FilterResult(List<ValidatedOperation> operations, int keptAdds, int droppedAdds) {
    }
}