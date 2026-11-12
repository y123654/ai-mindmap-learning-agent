package com.example.aimindmap.mindmap;

import com.example.aimindmap.ai.AiGatewayException;
import com.example.aimindmap.ai.dto.AiOperationResponse;
import com.example.aimindmap.ai.dto.AiTempFields;
import com.example.aimindmap.ai.dto.AiTempNode;
import com.example.aimindmap.ai.dto.AiTempOperation;
import com.example.aimindmap.mindmap.graph.GraphIndex;
import com.example.aimindmap.mindmap.graph.MindMapGraph;
import com.example.aimindmap.mindmap.graph.MindMapNode;
import com.example.aimindmap.parser.MarkdownBlock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * v1.1 §9.1 / §9.2 / §9.4：把 AI 的临时操作（tmp_ 前缀）转换为后端正式操作。
 *
 * <ul>
 *   <li>正式节点 ID 只能由后端 {@link NodeIdGenerator} 生成；</li>
 *   <li>AI 新节点 sourceType 强制 ai_enhanced，保护标志/状态由后端决定；</li>
 *   <li>sourceBlockId 必须命中本次发送给 AI 的真实块，锚点/路径由后端按真实块补全；</li>
 *   <li>普通段落 AI 节点由后端按真实标题路径平铺挂载，忽略 AI 自建父子关系；</li>
 *   <li>AI 不允许删除/移动/合并，只允许 add_node / update_node / noop；</li>
 *   <li>合并后的整批操作再由 {@link MindMapOperationValidator} 统一校验，任一失败整体不发布。</li>
 * </ul>
 */
@Component
public class AiOperationConverter {

    private static final Set<String> ALLOWED_TYPES = Set.of("add_node", "update_node", "noop");

    public List<ValidatedOperation> convert(AiOperationResponse response,
                                            AiSyncContext context,
                                            NodeIdGenerator idGenerator) {
        List<ValidatedOperation> result = new ArrayList<>();
        if (response == null) {
            return result;
        }

        GraphIndex currentIndex = GraphIndex.of(context.currentGraph());
        Map<String, MindMapNode> ruleAdded = context.ruleAddedNodes();
        Set<String> allowedNewBlockIds = context.allowedNewBlockIds();
        Set<String> allowedUpdateOldBlockIds = context.allowedUpdateOldBlockIds();
        Map<String, MarkdownBlock> newBlockById = context.newBlockById();
        String rootId = resolveRootId(context.currentGraph(), ruleAdded);

        List<AiTempOperation> ops = response.safeOperations();

        // 第 1 步：校验 AI 临时 ID，并计算它在 AI 自建树中的深度。
        // v1.1 2026-09-13 稳定修订：AI 普通段落节点最终必须平铺，临时父子关系不会进入正式图。
        Map<String, Integer> tempDepthById = new HashMap<>();
        Map<AiTempOperation, String> tempIdByAddOp = new IdentityHashMap<>();
        Set<String> seenTempIds = new HashSet<>();
        int addIndex = 0;
        for (AiTempOperation op : ops) {
            if (!"add_node".equals(op.type())) {
                continue;
            }
            String tempId = normalizeTempId(op.clientTempId(), addIndex++);
            if (!seenTempIds.add(tempId)) {
                throw protocol("clientTempId 重复：" + tempId);
            }
            int depth = 0;
            String parentTempId = op.parentClientTempId();
            if (parentTempId != null && !parentTempId.isBlank()) {
                depth = tempDepthById.getOrDefault(parentTempId.trim(), -1) + 1;
            }
            tempDepthById.put(tempId, Math.max(0, depth));
            tempIdByAddOp.put(op, tempId);
        }

        // 第 2 步：一个普通段落最多保留一个 AI 节点。
        // 若 AI 为同一段落返回“分组节点 + 具体要点”，优先保留更深、更具体的节点，丢弃分组壳。
        Map<String, AiTempOperation> representativeByBlock = new LinkedHashMap<>();
        for (AiTempOperation op : ops) {
            if (!"add_node".equals(op.type())) {
                continue;
            }
            String tempId = tempIdByAddOp.get(op);
            String sourceBlockId = requireAllowedSourceBlock(op, allowedNewBlockIds);
            AiTempOperation existing = representativeByBlock.get(sourceBlockId);
            Integer existingDepth = existing == null
                    ? null
                    : tempDepthById.get(tempIdByAddOp.get(existing));
            if (existing == null
                    || tempDepthById.getOrDefault(tempId, 0) > existingDepth) {
                representativeByBlock.put(sourceBlockId, op);
            }
        }

        // 第 3 步：只为保留下来的平铺节点分配正式 ID。
        Map<AiTempOperation, String> formalIdByAddOp = new IdentityHashMap<>();
        for (AiTempOperation op : representativeByBlock.values()) {
            formalIdByAddOp.put(op, idGenerator.next());
        }

        Set<String> reservedHeadingLabels = collectMarkdownHeadingLabels(currentIndex, ruleAdded);
        Map<String, Set<String>> usedLabelsByParent = new HashMap<>();

        // 第 4 步：先逐条处理 update_node / noop / 非法操作，保证旧保护规则和协议校验不丢失。
        for (AiTempOperation op : ops) {
            String type = op.type() == null ? "" : op.type().trim();
            if (!ALLOWED_TYPES.contains(type)) {
                throw protocol("AI 返回了不允许的操作类型：" + type
                        + "（第一版只允许 add_node / update_node / noop）");
            }
            if ("update_node".equals(type)) {
                result.add(toUpdateOperation(op, currentIndex, allowedUpdateOldBlockIds));
            }
        }

        // 第 5 步：只转换保留下来的新增段落节点。
        // AI 给的 parentNodeId / parentClientTempId / afterNodeId 均不作为最终结构，
        // 父节点严格由该段落真实所属的 Markdown 标题路径决定，防止 AI 自建目录形成套娃。
        for (AiTempOperation op : representativeByBlock.values()) {
            result.add(toAddOperation(op, context, formalIdByAddOp, currentIndex, ruleAdded,
                    allowedNewBlockIds, newBlockById, rootId, reservedHeadingLabels, usedLabelsByParent));
        }
        return result;
    }

    private ValidatedOperation toAddOperation(AiTempOperation op,
                                              AiSyncContext context,
                                              Map<AiTempOperation, String> formalIdByAddOp,
                                              GraphIndex currentIndex,
                                              Map<String, MindMapNode> ruleAdded,
                                              Set<String> allowedNewBlockIds,
                                              Map<String, MarkdownBlock> newBlockById,
                                              String rootId,
                                              Set<String> reservedHeadingLabels,
                                              Map<String, Set<String>> usedLabelsByParent) {
        AiTempNode tempNode = op.node();
        if (tempNode == null) {
            throw protocol("add_node 缺少 node 对象");
        }
        String rawLabel = requireLabel(tempNode.label(), op.clientTempId());
        String summary = normalizeSummary(tempNode.summary());

        // AI 新增节点必须能溯源到本次真实新增/不确定普通段落。
        String sourceBlockId = requireAllowedSourceBlock(op, allowedNewBlockIds);
        MarkdownBlock block = newBlockById.get(sourceBlockId);
        if (block == null) {
            throw protocol("node.sourceBlockId 找不到本次新增段落：" + sourceBlockId);
        }
        String sourceAnchor = block.anchor();
        List<String> sourcePath = List.copyOf(block.headingPath());

        // 父节点只按真实 Markdown 标题路径解析，禁止挂到 AI 节点下面形成套娃。
        String parentId = resolveFlatHeadingParent(block, currentIndex, ruleAdded, rootId);

        String label = sanitizeParagraphLabel(rawLabel, block, parentId,
                reservedHeadingLabels, usedLabelsByParent);
        String formalId = formalIdByAddOp.get(op);
        if (formalId == null) {
            throw protocol("add_node 无法匹配临时 ID：" + op.clientTempId());
        }

        MindMapNode node = new MindMapNode(formalId, label, summary, "ai_enhanced",
                sourceBlockId, sourceAnchor, sourcePath, Instant.now());

        return ValidatedOperation.add(formalId, parentId, null, node,
                reason(op.reason(), "AI 归纳普通段落（后端平铺到所属标题）"));
    }
    private ValidatedOperation toUpdateOperation(AiTempOperation op,
                                                 GraphIndex currentIndex,
                                                 Set<String> allowedUpdateOldBlockIds) {
        String nodeId = op.nodeId();
        if (nodeId == null || nodeId.isBlank()) {
            throw protocol("update_node 缺少 nodeId");
        }
        if (nodeId.startsWith("tmp_")) {
            throw protocol("update_node 的 nodeId 不能使用临时 ID：" + nodeId);
        }
        MindMapNode existing = currentIndex.findById(nodeId);
        if (existing == null) {
            throw protocol("update_node 指向的正式节点不存在：" + nodeId);
        }
        if (existing.getSourceBlockId() == null
                || !allowedUpdateOldBlockIds.contains(existing.getSourceBlockId())) {
            throw protocol("AI 只能更新本次修改段落对应的 AI 节点：" + nodeId);
        }
        if ("manual".equals(existing.getSourceType()) || existing.isManualLocked()
                || existing.isManuallyEdited()) {
            // §9.4 第 1-4 条：AI 违反保护规则，本次同步整体失败
            throw new AiGatewayException("AI_VIOLATES_PROTECTION",
                    "AI 试图修改受手动保护的节点：" + nodeId);
        }

        String label;
        String summary;
        AiTempFields fields = op.fields();
        if (fields != null && (fields.label() != null || fields.summary() != null)) {
            label = fields.label() != null ? fields.label() : existing.getLabel();
            summary = fields.summary() != null ? fields.summary() : existing.getSummary();
        } else if (op.node() != null) {
            label = op.node().label() != null ? op.node().label() : existing.getLabel();
            summary = op.node().summary() != null ? op.node().summary() : existing.getSummary();
        } else {
            throw protocol("update_node 缺少 fields/node 内容：" + nodeId);
        }
        label = requireLabel(label, nodeId);
        summary = normalizeSummary(summary);

        MindMapNode copy = existing.deepCopy();
        copy.setLabel(label);
        copy.setSummary(summary);
        copy.setUpdatedAt(Instant.now());
        return ValidatedOperation.update(nodeId, copy, reason(op.reason(), "AI 根据段落修改更新节点"));
    }

    private static String normalizeTempId(String raw, int autoIndex) {
        if (raw == null || raw.isBlank()) {
            return "tmp_auto_" + autoIndex;
        }
        String tempId = raw.trim();
        if (!tempId.startsWith("tmp_")) {
            throw protocol("新增节点的 clientTempId 必须以 tmp_ 开头：" + tempId);
        }
        return tempId;
    }

    private static String requireAllowedSourceBlock(AiTempOperation op, Set<String> allowedNewBlockIds) {
        AiTempNode node = op.node();
        if (node == null || node.sourceBlockId() == null || node.sourceBlockId().isBlank()) {
            throw protocol("AI 新增普通段落节点必须提供 node.sourceBlockId");
        }
        String blockId = node.sourceBlockId().trim();
        if (!allowedNewBlockIds.contains(blockId)) {
            throw protocol("node.sourceBlockId 不是本次新增/不确定段落的块 ID：" + blockId);
        }
        return blockId;
    }

    private static Set<String> collectMarkdownHeadingLabels(GraphIndex currentIndex,
                                                            Map<String, MindMapNode> ruleAdded) {
        Set<String> labels = new HashSet<>();
        for (MindMapNode node : currentIndex.allNodes()) {
            addHeadingLabel(labels, node);
        }
        for (MindMapNode node : ruleAdded.values()) {
            addHeadingLabel(labels, node);
        }
        return labels;
    }

    private static void addHeadingLabel(Set<String> labels, MindMapNode node) {
        if (node == null || node.getSourceAnchor() == null || node.getLabel() == null) {
            return;
        }
        if (node.getSourceAnchor().contains("#heading(")) {
            labels.add(node.getLabel().trim());
            labels.add(normalizeHeadingLike(node.getLabel()));
        }
    }

    private String resolveFlatHeadingParent(MarkdownBlock block,
                                            GraphIndex currentIndex,
                                            Map<String, MindMapNode> ruleAdded,
                                            String rootId) {
        List<String> headingPath = block.headingPath();
        if (headingPath == null || headingPath.isEmpty()) {
            return rootId;
        }
        MindMapNode existing = currentIndex.allNodes().stream()
                .filter(node -> node.getSourceAnchor() != null
                        && node.getSourceAnchor().contains("#heading(")
                        && headingPath.equals(node.getSourcePath()))
                .findFirst()
                .orElse(null);
        if (existing != null) {
            return existing.getId();
        }
        MindMapNode added = ruleAdded.values().stream()
                .filter(node -> node.getSourceAnchor() != null
                        && node.getSourceAnchor().contains("#heading(")
                        && headingPath.equals(node.getSourcePath()))
                .findFirst()
                .orElse(null);
        return added == null ? rootId : added.getId();
    }

    private String sanitizeParagraphLabel(String rawLabel,
                                          MarkdownBlock block,
                                          String parentId,
                                          Set<String> reservedHeadingLabels,
                                          Map<String, Set<String>> usedLabelsByParent) {
        String label = rawLabel.trim();
        String normalized = normalizeHeadingLike(label);
        boolean copiesHeading = reservedHeadingLabels.contains(label)
                || reservedHeadingLabels.contains(normalized);
        if (copiesHeading) {
            label = paragraphExcerpt(block);
        }

        Set<String> used = usedLabelsByParent.computeIfAbsent(parentId, key -> new HashSet<>());
        if (!used.add(label)) {
            label = paragraphExcerpt(block);
            int suffix = 2;
            String base = label;
            while (!used.add(label)) {
                label = base + suffix;
                suffix++;
            }
        }
        if (label.length() > MindMapOperationValidator.MAX_LABEL_LENGTH) {
            label = label.substring(0, MindMapOperationValidator.MAX_LABEL_LENGTH - 1) + "…";
        }
        return label;
    }

    private static String normalizeHeadingLike(String raw) {
        return raw.trim()
                .replaceFirst("^\\d+(?:[.、]\\d+)*[.、]?\\s*", "")
                .replaceFirst("^[一二三四五六七八九十百]+[.、)）]\\s*", "")
                .trim();
    }

    private static String paragraphExcerpt(MarkdownBlock block) {
        String text = block.normalizedText().replaceAll("\\s+", " ").trim();
        int max = MindMapOperationValidator.MAX_LABEL_LENGTH - 2;
        if (text.length() <= max) {
            return text;
        }
        return text.substring(0, max) + "…";
    }
    private String resolveParentId(AiTempOperation op,
                                   GraphIndex currentIndex,
                                   Map<String, MindMapNode> ruleAdded,
                                   Map<String, MindMapNode> aiNodesByFormalId,
                                   Map<String, String> tempToFormal,
                                   Map<String, MarkdownBlock> newBlockById,
                                   String sourceBlockId,
                                   String rootId) {
        // a) 同批次 AI 父节点
        if (op.parentClientTempId() != null && !op.parentClientTempId().isBlank()) {
            String parentFormal = tempToFormal.get(op.parentClientTempId());
            if (parentFormal == null) {
                throw protocol("parentClientTempId 未在本次操作中定义：" + op.parentClientTempId());
            }
            return parentFormal;
        }
        // b) 正式节点 ID：当前图或同批次规则新增
        if (op.parentNodeId() != null && !op.parentNodeId().isBlank()) {
            String parentId = op.parentNodeId().trim();
            if (findNode(parentId, currentIndex, ruleAdded, aiNodesByFormalId) != null) {
                return parentId;
            }
            throw protocol("parentNodeId 指向的节点不存在：" + parentId);
        }
        // c) 按段落所在标题路径推断（规则新增的标题也在候选里）
        if (sourceBlockId != null) {
            MarkdownBlock block = newBlockById.get(sourceBlockId);
            if (block != null && !block.headingPath().isEmpty()) {
                MindMapNode heading = findBySourcePath(block.headingPath(),
                        currentIndex, ruleAdded, aiNodesByFormalId);
                if (heading != null) {
                    return heading.getId();
                }
            }
        }
        // d) 兜底挂根节点（规则阶段必然已生成根）
        if (rootId != null) {
            return rootId;
        }
        throw protocol("AI 新增节点找不到父节点：" + op.clientTempId());
    }

    private String resolveAfterNodeId(String afterNodeId,
                                      GraphIndex currentIndex,
                                      Map<String, MindMapNode> ruleAdded,
                                      Map<String, MindMapNode> aiNodesByFormalId,
                                      Map<String, String> tempToFormal,
                                      String parentId,
                                      MindMapGraph currentGraph) {
        if (afterNodeId == null || afterNodeId.isBlank()) {
            return null;
        }
        String id = afterNodeId.trim();
        if (id.startsWith("tmp_")) {
            id = tempToFormal.get(id);
        }
        if (id == null) {
            return null;
        }
        MindMapNode node = findNode(id, currentIndex, ruleAdded, aiNodesByFormalId);
        if (node == null) {
            // AI 给错位置时退化为追加，不因为排序提示失败整次同步
            return null;
        }
        return id;
    }

    private static MindMapNode findNode(String id,
                                        GraphIndex currentIndex,
                                        Map<String, MindMapNode> ruleAdded,
                                        Map<String, MindMapNode> aiNodesByFormalId) {
        MindMapNode node = currentIndex.findById(id);
        if (node != null) {
            return node;
        }
        node = ruleAdded.get(id);
        if (node != null) {
            return node;
        }
        for (MindMapNode candidate : ruleAdded.values()) {
            if (id.equals(candidate.getId())) {
                return candidate;
            }
        }
        return aiNodesByFormalId.get(id);
    }

    private static MindMapNode findBySourcePath(List<String> path,
                                                GraphIndex currentIndex,
                                                Map<String, MindMapNode> ruleAdded,
                                                Map<String, MindMapNode> aiNodesByFormalId) {
        MindMapNode existing = currentIndex.findBySourcePath(path);
        if (existing != null) {
            return existing;
        }
        for (MindMapNode node : ruleAdded.values()) {
            if (path.equals(node.getSourcePath())) {
                return node;
            }
        }
        for (MindMapNode node : aiNodesByFormalId.values()) {
            if (node != null && path.equals(node.getSourcePath())) {
                return node;
            }
        }
        return null;
    }

    private static String resolveRootId(MindMapGraph currentGraph, Map<String, MindMapNode> ruleAdded) {
        if (currentGraph != null && currentGraph.getRootId() != null) {
            return currentGraph.getRootId();
        }
        for (MindMapNode node : ruleAdded.values()) {
            if (node.getSourcePath() != null && node.getSourcePath().size() <= 1) {
                return node.getId();
            }
        }
        return null;
    }

    private static String pickAutoId(Map<String, String> tempToFormal,
                                     Map<String, MindMapNode> aiNodesByFormalId) {
        for (Map.Entry<String, String> entry : tempToFormal.entrySet()) {
            if (entry.getKey().startsWith("tmp_auto_") && aiNodesByFormalId.get(entry.getValue()) == null) {
                return entry.getKey();
            }
        }
        return null;
    }

    private static String requireLabel(String raw, String identify) {
        if (raw == null || raw.isBlank()) {
            throw protocol("节点标题不能为空：" + identify);
        }
        String label = raw.trim();
        if (label.length() > MindMapOperationValidator.MAX_LABEL_LENGTH) {
            throw protocol("节点标题超过 " + MindMapOperationValidator.MAX_LABEL_LENGTH
                    + " 字符：" + label);
        }
        return label;
    }

    private static String normalizeSummary(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String summary = raw.trim();
        if (summary.length() > MindMapOperationValidator.MAX_SUMMARY_LENGTH) {
            throw protocol("节点摘要超过 " + MindMapOperationValidator.MAX_SUMMARY_LENGTH + " 字符");
        }
        return summary;
    }

    private static List<String> sanitizePath(List<String> rawPath) {
        if (rawPath == null) {
            return List.of();
        }
        List<String> path = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String part : rawPath) {
            if (part == null) {
                continue;
            }
            String trimmed = part.trim();
            if (trimmed.isEmpty() || !seen.add(trimmed)) {
                continue;
            }
            path.add(trimmed);
            if (path.size() >= MindMapOperationValidator.MAX_DEPTH + 1) {
                break;
            }
        }
        return path;
    }

    private static String reason(String aiReason, String fallback) {
        if (aiReason == null || aiReason.isBlank()) {
            return fallback;
        }
        String reason = aiReason.trim();
        return reason.length() > 200 ? reason.substring(0, 200) : reason;
    }

    private static AiGatewayException protocol(String message) {
        return new AiGatewayException("AI_PROTOCOL_VIOLATION", message);
    }
}




