package com.example.aimindmap.mindmap;

import com.example.aimindmap.common.error.ApiException;
import com.example.aimindmap.mindmap.graph.GraphIndex;
import com.example.aimindmap.mindmap.graph.MindMapGraph;
import com.example.aimindmap.mindmap.graph.MindMapNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 节点预算器（《思维导图节点预算与折叠实施方案-v1.0》§4.2）。
 *
 * <p>触发场景：文档“太宽”（同级列表项很多）时，{@link MindMapDepthCap} 按深度裁剪救不下来，
 * 最终撞上 {@link MindMapOperationValidator#MAX_NEW_NODES_HARD} 整单失败。
 * 本类的职责是在校验前把本次新增节点数压到 {@link MindMapProperties#getMaxNewNodes()} 以内：
 *
 * <ul>
 *   <li>标题节点（{@code sourceAnchor} 含 {@code #heading(}）永不折叠，定义结构；</li>
 *   <li>第一轮折叠一级列表项（{@code #bullet(}），仍超限时第二轮折叠 {@code ai_enhanced} 段落节点；</li>
 *   <li>只折叠叶子节点（不能是任何 add 的父节点），避免产生孤儿；</li>
 *   <li>名额按父组大小用最大余数法分配，保证 {@code sum(keep) == keepTotal} 且确定；</li>
 *   <li>被折叠项的文本写入父节点 summary（父节点不可写时写最后一个保留子节点），格式
 *       {@code [节点预算折叠] 已折叠 N 项：a；b；…}，已存在旧段先删再写，重复同步不堆叠；</li>
 *   <li>summary 上限 = {@code min(summaryMaxChars, MAX_SUMMARY_LENGTH)}，超限截断加 {@code …}；</li>
 *   <li>折叠解决不了（标题本身超限）时抛 {@code MINDMAP_STRUCTURE_TOO_LARGE}，提示拆分文档，不提示重试。</li>
 * </ul>
 *
 * <p>日志只打计数与节点 ID，禁止打印节点正文。
 */
@Component
public class MindMapNodeBudget {

    private static final Logger log = LoggerFactory.getLogger(MindMapNodeBudget.class);

    /** summary 中的折叠标记；重写前先删掉带该标记的旧行，保证幂等。 */
    public static final String FOLD_MARKER = "[节点预算折叠]";
    /** 折叠文本前缀（与标记一起写入：{@code [节点预算折叠] 已折叠 N 项：…}）。 */
    private static final String FOLD_PREFIX = "已折叠 ";
    /** 根节点（无父节点）在读顺序索引里用的占位 key。 */
    private static final String ROOT_PARENT_KEY = "\u0000root";
    /** 节点预算生成的 update_node 的 reason。 */
    private static final String UPDATE_REASON = "节点预算折叠";
    private static final String TYPE_ADD_NODE = OperationType.ADD_NODE.wire();
    private static final String TYPE_UPDATE_NODE = OperationType.UPDATE_NODE.wire();
    private static final String TYPE_PROPOSE_REMOVE = OperationType.PROPOSE_REMOVE_SOURCE_NODE.wire();

    /**
     * 对本次同步的操作做节点预算与折叠。
     *
     * @param operations   合并后的全部操作（只读，不会被修改顺序；add 节点对象在需要写 summary 时可能被就地更新）
     * @param currentGraph 当前已发布的图（增量同步时用于判断父节点是否存在/是否可写）
     * @param properties   节点预算配置
     * @return 折叠后的操作与计数
     * @throws ApiException {@code MINDMAP_STRUCTURE_TOO_LARGE}：标题/不可折叠节点本身超过上限
     */
    public BudgetResult apply(List<ValidatedOperation> operations,
                              MindMapGraph currentGraph,
                              MindMapProperties properties) {
        MindMapProperties props = properties == null ? new MindMapProperties() : properties;
        List<ValidatedOperation> result = new ArrayList<>();
        if (operations != null) {
            result.addAll(operations);
        }
        int cap = props.getMaxNewNodes();
        int addCount = countAdds(result);
        if (!props.isFoldEnabled() || addCount <= cap) {
            return new BudgetResult(result, addCount, 0, 0, 0);
        }

        GraphIndex graphIndex = GraphIndex.of(currentGraph);
        int summaryLimit = resolveSummaryLimit(props);
        Map<String, MindMapNode> extraUpdateNodes = new LinkedHashMap<>();
        log.info("节点预算折叠开始：本次新增 {} 个，上限 {} 个，summary 上限 {} 字符",
                addCount, cap, summaryLimit);

        int foldedAdds = 0;
        int foldedParents = 0;
        int truncatedSummaries = 0;

        FoldPass bulletPass = fold(result, graphIndex, extraUpdateNodes, summaryLimit, cap, CandidateType.BULLET);
        result = bulletPass.operations();
        foldedAdds += bulletPass.foldedAdds();
        foldedParents += bulletPass.foldedParents();
        truncatedSummaries += bulletPass.truncatedSummaries();

        if (countAdds(result) > cap) {
            FoldPass aiPass = fold(result, graphIndex, extraUpdateNodes, summaryLimit, cap,
                    CandidateType.AI_PARAGRAPH);
            result = aiPass.operations();
            foldedAdds += aiPass.foldedAdds();
            foldedParents += aiPass.foldedParents();
            truncatedSummaries += aiPass.truncatedSummaries();
        }

        int keptAdds = countAdds(result);
        if (keptAdds > cap) {
            throw structureTooLarge(keptAdds, cap, countHeadings(result), "折叠后仍超出上限");
        }

        // 父节点是已有图节点时，折叠文本通过一条合并后的 update_node 写回（同一 parentId 只一条）
        List<ValidatedOperation> output = new ArrayList<>(result);
        for (Map.Entry<String, MindMapNode> entry : extraUpdateNodes.entrySet()) {
            output.add(ValidatedOperation.update(entry.getKey(), entry.getValue(), UPDATE_REASON));
        }

        if (foldedAdds > 0) {
            log.warn("节点预算折叠生效：新增 {} -> {}，折叠 {} 项，涉及 {} 个父节点，摘要截断 {} 次",
                    addCount, keptAdds, foldedAdds, foldedParents, truncatedSummaries);
        }
        return new BudgetResult(output, keptAdds, foldedAdds, foldedParents, truncatedSummaries);
    }

    /** 单趟折叠：收集指定类型的叶子候选，按父组分配保留名额，折叠其余项并把文本写进 summary。 */
    private FoldPass fold(List<ValidatedOperation> ops,
                          GraphIndex graphIndex,
                          Map<String, MindMapNode> extraUpdateNodes,
                          int summaryLimit,
                          int cap,
                          CandidateType type) {
        int addCount = countAdds(ops);
        if (addCount - cap <= 0) {
            return FoldPass.noop(ops);
        }

        Map<String, ValidatedOperation> addById = new HashMap<>();
        Set<String> parentIdsOfAdds = new HashSet<>();
        Map<String, MindMapNode> existingUpdateNodes = new HashMap<>();
        for (ValidatedOperation op : ops) {
            if (isAddOp(op)) {
                if (op.nodeId() != null) {
                    addById.put(op.nodeId(), op);
                }
                if (op.parentId() != null) {
                    parentIdsOfAdds.add(op.parentId());
                }
            } else if (TYPE_UPDATE_NODE.equals(op.type()) && op.nodeId() != null && op.node() != null) {
                existingUpdateNodes.put(op.nodeId(), op.node());
            }
        }

        Map<String, List<ValidatedOperation>> groups = new LinkedHashMap<>();
        for (ValidatedOperation op : ops) {
            if (!isAddOp(op) || op.node() == null || op.parentId() == null) {
                continue;
            }
            if (!isCandidate(op.node(), type)) {
                continue;
            }
            // 只折叠叶子：是任何 add 的父节点就跳过（宁可少折，也不产生孤儿）
            if (parentIdsOfAdds.contains(op.nodeId())) {
                continue;
            }
            groups.computeIfAbsent(op.parentId(), k -> new ArrayList<>()).add(op);
        }

        int foldable = 0;
        for (List<ValidatedOperation> candidates : groups.values()) {
            foldable += candidates.size();
        }
        if (foldable == 0) {
            log.info("节点预算折叠：{} 候选为 0 个（非叶子或类型不匹配），本轮不折叠", type);
            return FoldPass.noop(ops);
        }

        int fixed = addCount - foldable;
        // 偏离方案 §4.2.3（原为 fixed >= cap）：fixed == cap 时把所有候选都折掉正好等于上限，
        // 按 >= 会白白失败、违背 §2.1 目标 1，因此只有 fixed 严格大于上限才放弃本轮折叠。
        if (fixed > cap) {
            log.info("节点预算折叠：{} 的固定节点 {} 个已超过上限 {} 个，本轮不折叠", type, fixed, cap);
            return FoldPass.noop(ops);
        }

        int keepTotal = Math.min(foldable, cap - fixed);
        int foldTotal = foldable - keepTotal;
        log.info("节点预算折叠：类型 {}，候选 {} 个、固定 {} 个、可保留 {} 个、计划折叠 {} 个",
                type, foldable, fixed, keepTotal, foldTotal);

        Map<String, Integer> keepByParent = allocateKeep(groups, keepTotal);
        FoldContext context = new FoldContext(graphIndex, addById, existingUpdateNodes,
                extraUpdateNodes, summaryLimit);

        // keep=0 且父节点不可写：先从 keep>1 的组借 1 个名额（sum(keep) 不变），借不到则明确失败
        for (String parentId : new ArrayList<>(groups.keySet())) {
            if (keepByParent.getOrDefault(parentId, 0) != 0) {
                continue;
            }
            if (canWriteParent(parentId, context)) {
                continue;
            }
            if (tryBorrowOneKeep(keepByParent, groups, parentId)) {
                log.warn("节点预算折叠：父节点不可写且未分到保留名额，回退保留 1 个承接文本"
                        + "（fallbackKeep=1，parentId={}）", parentId);
            } else {
                throw structureTooLarge(addCount, cap, countHeadings(ops),
                        "不可写父节点 parentId=" + parentId + " 下无保留子节点承接文本，且无其它父组可借名额");
            }
        }

        Set<String> removedIds = new LinkedHashSet<>();
        int groupsFolded = 0;
        int truncatedSummaries = 0;
        for (Map.Entry<String, List<ValidatedOperation>> entry : groups.entrySet()) {
            String parentId = entry.getKey();
            List<ValidatedOperation> candidates = entry.getValue();
            int keep = keepByParent.getOrDefault(parentId, 0);
            if (keep >= candidates.size()) {
                continue;
            }
            List<ValidatedOperation> folded = candidates.subList(keep, candidates.size());
            String text = foldText(folded);
            ValidatedOperation lastKeptChild = keep > 0 ? candidates.get(keep - 1) : null;
            if (writeFoldSummary(parentId, lastKeptChild, text, folded.size(), context)) {
                truncatedSummaries++;
            }
            groupsFolded++;
            for (ValidatedOperation op : folded) {
                removedIds.add(op.nodeId());
            }
        }

        List<ValidatedOperation> output = filterAndRepair(ops, removedIds);
        int actualFolded = removedIds.size();
        if (actualFolded != foldTotal) {
            log.warn("节点预算折叠：{} 实际折叠 {} 个与计划 {} 个不一致（keepTotal={}），以实际为准",
                    type, actualFolded, foldTotal, keepTotal);
        }
        return new FoldPass(output, actualFolded, groupsFolded, truncatedSummaries);
    }

    /**
     * 按父组大小用最大余数法分配保留名额。
     *
     * <p>规则（§4.2.4）：名额够时每组先保 1 个；剩余名额按组大小比例分配，取整误差用最大余数补齐，
     * 余数相同按父组出现顺序。结果保证 {@code sum(keep) == keepTotal}、{@code 0 <= keep_i <= size_i}。
     */
    private static Map<String, Integer> allocateKeep(Map<String, List<ValidatedOperation>> groups,
                                                     int keepTotal) {
        int groupCount = groups.size();
        Map<String, Integer> keep = new LinkedHashMap<>();
        int assigned = 0;
        int base = keepTotal >= groupCount ? 1 : 0;
        for (Map.Entry<String, List<ValidatedOperation>> entry : groups.entrySet()) {
            int value = Math.min(base, entry.getValue().size());
            keep.put(entry.getKey(), value);
            assigned += value;
        }

        int left = keepTotal - assigned;
        if (left > 0) {
            int foldable = 0;
            for (List<ValidatedOperation> candidates : groups.values()) {
                foldable += candidates.size();
            }
            Map<String, Double> remainders = new LinkedHashMap<>();
            for (Map.Entry<String, List<ValidatedOperation>> entry : groups.entrySet()) {
                String parentId = entry.getKey();
                int size = entry.getValue().size();
                double quota = (double) left * size / foldable;
                int floor = (int) Math.floor(quota);
                int capacity = size - keep.get(parentId);
                int add = Math.min(floor, capacity);
                keep.put(parentId, keep.get(parentId) + add);
                assigned += add;
                remainders.put(parentId, quota - floor);
            }

            int stillLeft = keepTotal - assigned;
            List<String> ordered = new ArrayList<>(groups.keySet());
            // 稳定排序：余数从大到小，余数相同保持父组出现顺序（确定性）
            ordered.sort((first, second) -> Double.compare(remainders.get(second), remainders.get(first)));
            while (stillLeft > 0) {
                boolean progressed = false;
                for (String parentId : ordered) {
                    if (stillLeft == 0) {
                        break;
                    }
                    if (keep.get(parentId) < groups.get(parentId).size()) {
                        keep.put(parentId, keep.get(parentId) + 1);
                        stillLeft--;
                        assigned++;
                        progressed = true;
                    }
                }
                if (!progressed) {
                    break;
                }
            }
        }

        if (assigned != keepTotal) {
            log.error("节点预算分配失败：sum(keep)={}，期望 keepTotal={}，父组数={}",
                    assigned, keepTotal, groupCount);
            throw new IllegalStateException("节点预算分配失败：sum(keep)=" + assigned
                    + "，期望 " + keepTotal);
        }
        return keep;
    }

    /** 从 keep 最大（平局按组顺序）且 keep>1 的组借 1 个名额给 borrower，保证 sum(keep) 不变。 */
    private static boolean tryBorrowOneKeep(Map<String, Integer> keep,
                                            Map<String, List<ValidatedOperation>> groups,
                                            String borrower) {
        String lender = null;
        int best = 1;
        for (Map.Entry<String, List<ValidatedOperation>> entry : groups.entrySet()) {
            String parentId = entry.getKey();
            if (parentId.equals(borrower)) {
                continue;
            }
            int value = keep.getOrDefault(parentId, 0);
            if (value > best) {
                best = value;
                lender = parentId;
            }
        }
        if (lender == null) {
            return false;
        }
        keep.put(lender, keep.get(lender) - 1);
        keep.put(borrower, 1);
        return true;
    }

    /** 把折叠文本写入父节点（或最后一个保留子节点）的 summary，返回是否发生截断。 */
    private boolean writeFoldSummary(String parentId,
                                     ValidatedOperation lastKeptChild,
                                     String text,
                                     int foldedCount,
                                     FoldContext context) {
        MindMapNode target = resolveSummaryTarget(parentId, lastKeptChild, context);
        if (target == null) {
            log.warn("节点预算折叠：找不到可写 summary 的目标节点（parentId={}，折叠 {} 项）",
                    parentId, foldedCount);
            throw new IllegalStateException("节点预算折叠找不到可写 summary 的目标节点：parentId=" + parentId);
        }

        String base = stripFoldSegment(target.getSummary());
        String segment = FOLD_MARKER + " " + text;
        String joined = base.isEmpty() ? segment : base + "\n" + segment;
        boolean truncated = false;
        if (joined.length() > context.summaryLimit()) {
            truncated = true;
            if (base.isEmpty()) {
                joined = truncateWithEllipsis(segment, context.summaryLimit());
            } else {
                int room = context.summaryLimit() - base.length() - 1;
                if (room >= 1) {
                    joined = base + "\n" + truncateWithEllipsis(segment, room);
                } else {
                    joined = truncateWithEllipsis(base, context.summaryLimit());
                }
            }
            log.warn("节点预算折叠：summary 超过上限 {} 字符被截断"
                            + "（parentId={}，折叠 {} 项，折叠文本原长 {}）",
                    context.summaryLimit(), parentId, foldedCount, text.length());
        }
        target.setSummary(joined);
        return truncated;
    }

    /**
     * 选择 summary 写入目标：本批 add 父节点 → 父节点本身；已有图节点且可写 → 合并后的 update 节点；
     * 父节点不可写（手动保护/类型 manual）或不在图里 → 最后一个保留子节点（都是本批新增，安全）。
     */
    private static MindMapNode resolveSummaryTarget(String parentId,
                                                    ValidatedOperation lastKeptChild,
                                                    FoldContext context) {
        ValidatedOperation parentAdd = context.addById().get(parentId);
        if (parentAdd != null && parentAdd.node() != null) {
            return parentAdd.node();
        }
        MindMapNode extra = context.extraUpdateNodes().get(parentId);
        if (extra != null) {
            return extra;
        }
        MindMapNode existingUpdate = context.existingUpdateNodes().get(parentId);
        if (existingUpdate != null) {
            return existingUpdate;
        }
        MindMapNode existing = context.graphIndex().findById(parentId);
        if (existing != null && isWritableExisting(existing)) {
            MindMapNode copy = existing.deepCopy();
            context.extraUpdateNodes().put(parentId, copy);
            return copy;
        }
        return lastKeptChild == null ? null : lastKeptChild.node();
    }

    private static boolean canWriteParent(String parentId, FoldContext context) {
        if (parentId == null) {
            return false;
        }
        if (context.addById().containsKey(parentId)) {
            return true;
        }
        if (context.extraUpdateNodes().containsKey(parentId)) {
            return true;
        }
        if (context.existingUpdateNodes().containsKey(parentId)) {
            return true;
        }
        MindMapNode existing = context.graphIndex().findById(parentId);
        return existing != null && isWritableExisting(existing);
    }

    /**
     * 已有图节点是否可被本批同步覆盖：手动锁 / 用户手改 / 手动创建（{@code source_type=manual}）都不可覆盖
     * （最后一项与校验器 validateUpdate 的保护口径对齐，否则生成的 update_node 会在校验阶段整单失败）。
     */
    private static boolean isWritableExisting(MindMapNode node) {
        return !node.isManualLocked()
                && !node.isManuallyEdited()
                && !"manual".equals(node.getSourceType());
    }

    /** 生成折叠文本：{@code 已折叠 N 项：标签1；标签2；…}（标签压成单行）。 */
    private static String foldText(List<ValidatedOperation> folded) {
        StringBuilder sb = new StringBuilder(FOLD_PREFIX).append(folded.size()).append(" 项：");
        for (int i = 0; i < folded.size(); i++) {
            if (i > 0) {
                sb.append('；');
            }
            sb.append(oneLine(folded.get(i).node().getLabel()));
        }
        return sb.toString();
    }

    private static String oneLine(String label) {
        if (label == null) {
            return "";
        }
        return label.replaceAll("\\s+", " ").trim();
    }

    /** 删除 summary 里带 {@link #FOLD_MARKER} 的旧行（按行匹配），其余行保留。 */
    private static String stripFoldSegment(String summary) {
        if (summary == null || summary.isEmpty()) {
            return "";
        }
        if (!summary.contains(FOLD_MARKER)) {
            return summary;
        }
        StringBuilder sb = new StringBuilder();
        String[] lines = summary.split("\n", -1);
        for (String line : lines) {
            if (line.contains(FOLD_MARKER)) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(line);
        }
        return sb.toString().stripTrailing();
    }

    private static String truncateWithEllipsis(String text, int maxLength) {
        if (text.length() <= maxLength) {
            return text;
        }
        if (maxLength <= 0) {
            return "";
        }
        if (maxLength == 1) {
            return "…";
        }
        return text.substring(0, maxLength - 1) + "…";
    }

    private static int resolveSummaryLimit(MindMapProperties properties) {
        int configured = properties.getSummaryMaxChars();
        int limit = Math.min(configured, MindMapOperationValidator.MAX_SUMMARY_LENGTH);
        if (limit <= 0) {
            // 配置非法（<=0）时退回校验器上限，避免写入空摘要
            log.warn("节点预算配置 summary-max-chars={} 非法，退回校验器上限 {} 字符",
                    configured, MindMapOperationValidator.MAX_SUMMARY_LENGTH);
            return MindMapOperationValidator.MAX_SUMMARY_LENGTH;
        }
        return limit;
    }

    /**
     * 删除被折叠的 add；清理引用被折叠节点的 update_node / propose_remove_source_node；
     * 修复保留 add 的 afterNodeId（同父组内向前找最近保留节点，找不到置 null）。
     *
     * <p>包级可见，便于单测直接覆盖“折了非叶子 / 父节点被删”的孤儿兜底分支。
     */
    static List<ValidatedOperation> filterAndRepair(List<ValidatedOperation> ops, Set<String> removedIds) {
        if (ops == null || ops.isEmpty() || removedIds == null || removedIds.isEmpty()) {
            return ops == null ? new ArrayList<>() : new ArrayList<>(ops);
        }

        Map<String, List<ValidatedOperation>> addsByParent = new LinkedHashMap<>();
        Map<String, Integer> addIndex = new HashMap<>();
        for (ValidatedOperation op : ops) {
            if (!isAddOp(op)) {
                continue;
            }
            String parentKey = op.parentId() == null ? ROOT_PARENT_KEY : op.parentId();
            List<ValidatedOperation> siblings = addsByParent.computeIfAbsent(parentKey,
                    k -> new ArrayList<>());
            siblings.add(op);
            addIndex.put(op.nodeId(), siblings.size() - 1);
        }

        List<ValidatedOperation> output = new ArrayList<>(ops.size());
        for (ValidatedOperation op : ops) {
            if (isAddOp(op)) {
                if (removedIds.contains(op.nodeId())) {
                    continue;
                }
                if (op.parentId() != null && removedIds.contains(op.parentId())) {
                    // 折了非叶子节点才会走到这里：属于实现错误，不静默，交给上层转错误
                    log.warn("节点预算折叠实现异常：保留节点 {} 的父节点 {} 已被折叠，拒绝生成孤儿节点",
                            op.nodeId(), op.parentId());
                    throw new IllegalStateException("节点预算折叠产生孤儿节点：parentId=" + op.parentId()
                            + " 已被折叠，nodeId=" + op.nodeId());
                }
                String repairedAfter = repairAfterNodeId(op, removedIds, addsByParent, addIndex);
                if (!Objects.equals(repairedAfter, op.afterNodeId())) {
                    output.add(new ValidatedOperation(op.type(), op.nodeId(), op.parentId(),
                            repairedAfter, op.node(), op.reason()));
                } else {
                    output.add(op);
                }
            } else if (TYPE_UPDATE_NODE.equals(op.type()) || TYPE_PROPOSE_REMOVE.equals(op.type())) {
                if (removedIds.contains(op.nodeId())) {
                    continue;
                }
                output.add(op);
            } else {
                output.add(op);
            }
        }
        return output;
    }

    private static String repairAfterNodeId(ValidatedOperation op,
                                            Set<String> removedIds,
                                            Map<String, List<ValidatedOperation>> addsByParent,
                                            Map<String, Integer> addIndex) {
        String afterNodeId = op.afterNodeId();
        if (afterNodeId == null || !removedIds.contains(afterNodeId)) {
            return afterNodeId;
        }
        String parentKey = op.parentId() == null ? ROOT_PARENT_KEY : op.parentId();
        List<ValidatedOperation> siblings = addsByParent.getOrDefault(parentKey, List.of());
        Integer index = addIndex.get(op.nodeId());
        if (index == null) {
            return null;
        }
        for (int i = index - 1; i >= 0; i--) {
            String candidate = siblings.get(i).nodeId();
            if (!removedIds.contains(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /** 折叠后仍超限：结构本身太大（标题不可折叠），给可执行提示，不提示重试。 */
    private static ApiException structureTooLarge(int addCount, int cap, int headingCount, String detail) {
        log.warn("节点预算折叠不足：新增 {} 个仍超过上限 {} 个（标题 {} 个不可折叠）；{}",
                addCount, cap, headingCount, detail);
        return ApiException.badRequest("MINDMAP_STRUCTURE_TOO_LARGE",
                "文档结构本身超过单次上限：新增节点 " + addCount + " 个（上限 " + cap
                        + " 个），其中标题 " + headingCount
                        + " 个，标题无法折叠，请拆分文档后再同步");
    }

    private static int countAdds(List<ValidatedOperation> ops) {
        int count = 0;
        for (ValidatedOperation op : ops) {
            if (isAddOp(op)) {
                count++;
            }
        }
        return count;
    }

    private static int countHeadings(List<ValidatedOperation> ops) {
        int count = 0;
        for (ValidatedOperation op : ops) {
            if (isAddOp(op) && op.node() != null && isHeading(op.node())) {
                count++;
            }
        }
        return count;
    }

    private static boolean isAddOp(ValidatedOperation op) {
        return op != null && TYPE_ADD_NODE.equals(op.type());
    }

    private static boolean isCandidate(MindMapNode node, CandidateType type) {
        return switch (type) {
            case BULLET -> isBullet(node);
            case AI_PARAGRAPH -> isAiParagraph(node);
        };
    }

    private static boolean isHeading(MindMapNode node) {
        return "markdown".equals(node.getSourceType())
                && node.getSourceAnchor() != null
                && node.getSourceAnchor().contains("#heading(");
    }

    private static boolean isBullet(MindMapNode node) {
        return "markdown".equals(node.getSourceType())
                && node.getSourceAnchor() != null
                && node.getSourceAnchor().contains("#bullet(");
    }

    private static boolean isAiParagraph(MindMapNode node) {
        return "ai_enhanced".equals(node.getSourceType());
    }

    /**
     * @param operations        折叠后的全部操作（含为已有父节点生成的 update_node）
     * @param keptAdds          折叠后保留的新增节点数
     * @param foldedAdds        被折叠的新增节点数
     * @param foldedParents     涉及的父节点数（有项被折叠的父组数）
     * @param truncatedSummaries 摘要被截断的次数
     */
    public record BudgetResult(List<ValidatedOperation> operations,
                               int keptAdds,
                               int foldedAdds,
                               int foldedParents,
                               int truncatedSummaries) {
    }

    /** 折叠候选类型：列表项优先，AI 段落节点兜底。 */
    private enum CandidateType {
        BULLET,
        AI_PARAGRAPH
    }

    private record FoldPass(List<ValidatedOperation> operations,
                            int foldedAdds,
                            int foldedParents,
                            int truncatedSummaries) {
        static FoldPass noop(List<ValidatedOperation> ops) {
            return new FoldPass(ops, 0, 0, 0);
        }
    }

    private record FoldContext(GraphIndex graphIndex,
                               Map<String, ValidatedOperation> addById,
                               Map<String, MindMapNode> existingUpdateNodes,
                               Map<String, MindMapNode> extraUpdateNodes,
                               int summaryLimit) {
    }
}
