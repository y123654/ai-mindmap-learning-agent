package com.example.aimindmap.mindmap;

import com.example.aimindmap.mindmap.graph.MindMapGraph;
import com.example.aimindmap.mindmap.graph.MindMapNode;
import com.example.aimindmap.parser.BlockType;
import com.example.aimindmap.parser.MarkdownBlock;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * v1.1 §11.4（2026-09-13 修订）：把用户手动新增的节点回写到 Markdown。
 *
 * <p>只负责“新增手动节点”这一条稳定路径：根据父节点对应的标题/列表位置，
 * 插入一个无序列表项；不根据整张图重写 Markdown，避免破坏用户原始段落格式。</p>
 */
@Component
public class ManualNodeMarkdownWriter {

    private static final Pattern HEADING_PATTERN = Pattern.compile("^(#{1,6})\\s+(.+?)\\s*$");
    private static final Pattern BULLET_PATTERN = Pattern.compile("^([\\s>*-]*)([-*+])\\s+(.+?)\\s*$");

    public WriteResult insertManualNode(String markdown,
                                        MindMapGraph graph,
                                        String parentId,
                                        String label,
                                        String summary,
                                        List<MarkdownBlock> newBlocks) {
        List<String> lines = splitLines(markdown == null ? "" : markdown);
        List<MindMapNode> chain = findChain(graph, parentId);
        if (chain.isEmpty()) {
            throw new IllegalStateException("父节点不存在：" + parentId);
        }

        AnchorLocation anchor = resolveAnchor(chain);
        int insertAt;
        int indentSpaces;

        if (anchor != null && anchor.node().getSourceBlockId() != null) {
            MarkdownBlock anchorBlock = newBlocks.stream()
                    .filter(block -> block.blockId().equals(anchor.node().getSourceBlockId()))
                    .findFirst()
                    .orElse(null);
            if (anchorBlock != null && anchorBlock.type() == BlockType.HEADING) {
                HeadingLocation location = findHeading(lines, anchor.node().getLabel(), anchorBlock.level());
                int sectionEnd = findHeadingSectionEnd(lines, location.lineIndex() + 1, location.level());
                insertAt = skipTrailingBlankLines(lines, sectionEnd, true);
                indentSpaces = Math.max(0, anchor.edgeDistance()) * 2;
            } else if (anchorBlock != null
                    && (anchorBlock.type() == BlockType.BULLET_LIST_ITEM
                    || anchorBlock.type() == BlockType.ORDERED_LIST_ITEM)) {
                BulletLocation location = findBullet(lines, anchor.node().getLabel());
                int subtreeEnd = findBulletSubtreeEnd(lines, location.lineIndex(), location.indent());
                insertAt = skipTrailingBlankLines(lines, subtreeEnd, false);
                int childLevel = Math.max(1, anchorBlock.level()) + anchor.edgeDistance() + 1;
                indentSpaces = (childLevel - 1) * 2;
            } else {
                Insertion fallback = fallbackAtEnd(lines);
                insertAt = fallback.insertAt();
                indentSpaces = fallback.indentSpaces();
            }
        } else {
            Insertion fallback = fallbackAtEnd(lines);
            insertAt = fallback.insertAt();
            indentSpaces = fallback.indentSpaces();
        }

        List<String> inserted = new ArrayList<>();
        inserted.add(" ".repeat(indentSpaces) + "- " + label);
        if (summary != null && !summary.isBlank()) {
            inserted.add(" ".repeat(indentSpaces + 2) + summary.trim());
        }
        lines.addAll(insertAt, inserted);
        return new WriteResult(String.join("\n", lines), indentSpaces);
    }

    private static List<String> splitLines(String markdown) {
        List<String> lines = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < markdown.length(); i++) {
            char ch = markdown.charAt(i);
            if (ch == '\n') {
                String line = markdown.substring(start, i);
                if (line.endsWith("\r")) {
                    line = line.substring(0, line.length() - 1);
                }
                lines.add(line);
                start = i + 1;
            }
        }
        if (start < markdown.length()) {
            lines.add(markdown.substring(start));
        }
        return lines;
    }

    private List<MindMapNode> findChain(MindMapGraph graph, String parentId) {
        List<MindMapNode> path = new ArrayList<>();
        if (!findChain(graph.getTree(), parentId, path)) {
            return List.of();
        }
        return path;
    }

    private boolean findChain(MindMapNode node, String targetId, List<MindMapNode> path) {
        if (node == null) {
            return false;
        }
        path.add(node);
        if (node.getId().equals(targetId)) {
            return true;
        }
        for (MindMapNode child : node.getChildren()) {
            if (findChain(child, targetId, path)) {
                return true;
            }
        }
        path.remove(path.size() - 1);
        return false;
    }

    private AnchorLocation resolveAnchor(List<MindMapNode> chain) {
        for (int i = chain.size() - 1; i >= 0; i--) {
            MindMapNode node = chain.get(i);
            String anchor = node.getSourceAnchor();
            if (anchor != null && (anchor.contains("#heading(") || anchor.contains("#bullet(")
                    || anchor.contains("#ordered("))) {
                return new AnchorLocation(node, chain.size() - 1 - i);
            }
        }
        return null;
    }

    private HeadingLocation findHeading(List<String> lines, String label, Integer level) {
        for (int i = 0; i < lines.size(); i++) {
            Matcher matcher = HEADING_PATTERN.matcher(lines.get(i));
            if (matcher.matches()
                    && matcher.group(2).trim().equals(label)
                    && (level == null || matcher.group(1).length() == level)) {
                return new HeadingLocation(i, matcher.group(1).length());
            }
        }
        throw new IllegalStateException("Markdown 中找不到父标题：" + label);
    }

    private int findHeadingSectionEnd(List<String> lines, int from, int headingLevel) {
        for (int i = from; i < lines.size(); i++) {
            Matcher matcher = HEADING_PATTERN.matcher(lines.get(i));
            if (matcher.matches() && matcher.group(1).length() <= headingLevel) {
                return i;
            }
        }
        return lines.size();
    }

    private BulletLocation findBullet(List<String> lines, String label) {
        for (int i = 0; i < lines.size(); i++) {
            Matcher matcher = BULLET_PATTERN.matcher(lines.get(i));
            if (matcher.matches() && matcher.group(3).trim().equals(label)) {
                int indent = leadingSpaces(lines.get(i));
                return new BulletLocation(i, indent);
            }
        }
        throw new IllegalStateException("Markdown 中找不到父列表项：" + label);
    }

    private int findBulletSubtreeEnd(List<String> lines, int itemLine, int itemIndent) {
        int end = itemLine + 1;
        while (end < lines.size()) {
            String line = lines.get(end);
            if (line.isBlank()) {
                int nextNonBlank = end + 1;
                while (nextNonBlank < lines.size() && lines.get(nextNonBlank).isBlank()) {
                    nextNonBlank++;
                }
                if (nextNonBlank >= lines.size() || leadingSpaces(lines.get(nextNonBlank)) <= itemIndent) {
                    return end;
                }
                end++;
                continue;
            }
            if (leadingSpaces(line) <= itemIndent) {
                return end;
            }
            end++;
        }
        return end;
    }

    private int skipTrailingBlankLines(List<String> lines, int index, boolean insertBlankBeforeList) {
        int cursor = index;
        while (cursor > 0 && lines.get(cursor - 1).isBlank()) {
            cursor--;
        }
        if (insertBlankBeforeList && cursor > 0 && !lines.get(cursor - 1).isBlank()) {
            lines.add(cursor, "");
            return cursor + 1;
        }
        return cursor;
    }

    private Insertion fallbackAtEnd(List<String> lines) {
        int insertAt = lines.size();
        while (insertAt > 0 && lines.get(insertAt - 1).isBlank()) {
            insertAt--;
        }
        if (insertAt > 0) {
            lines.add(insertAt, "");
            insertAt++;
        }
        return new Insertion(insertAt, 0);
    }

    private static int leadingSpaces(String line) {
        int count = 0;
        while (count < line.length() && line.charAt(count) == ' ') {
            count++;
        }
        return count;
    }

    private record AnchorLocation(MindMapNode node, int edgeDistance) {
    }

    private record HeadingLocation(int lineIndex, int level) {
    }

    private record BulletLocation(int lineIndex, int indent) {
    }

    private record Insertion(int insertAt, int indentSpaces) {
    }

    /**
     * @param markdown 插入后的 Markdown
     * @param indentSpaces 插入列表项的缩进空格数，用于匹配解析结果
     */
    public record WriteResult(String markdown, int indentSpaces) {
    }
}