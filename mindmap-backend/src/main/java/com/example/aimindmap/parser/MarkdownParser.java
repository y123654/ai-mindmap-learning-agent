package com.example.aimindmap.parser;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.commonmark.Extension;
import org.commonmark.ext.gfm.tables.TableBlock;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.BlockQuote;
import org.commonmark.node.BulletList;
import org.commonmark.node.Code;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.HardLineBreak;
import org.commonmark.node.Heading;
import org.commonmark.node.HtmlBlock;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.node.ListItem;
import org.commonmark.node.Node;
import org.commonmark.node.OrderedList;
import org.commonmark.node.Paragraph;
import org.commonmark.node.SoftLineBreak;
import org.commonmark.node.Text;
import org.commonmark.node.ThematicBreak;
import org.commonmark.parser.Parser;
import org.springframework.stereotype.Component;

/**
 * v1.1 方案 §8：把 Markdown 解析为带稳定锚点和哈希的内容块。
 * 阶段 2 只识别结构；是否生成思维导图节点由 RuleOperationBuilder 决定。
 */
@Component
public class MarkdownParser {

    private final Parser parser;

    public MarkdownParser() {
        List<Extension> extensions = List.of(TablesExtension.create());
        this.parser = Parser.builder().extensions(extensions).build();
    }

    public List<MarkdownBlock> parse(String markdown) {
        Node document = parser.parse(markdown == null ? "" : markdown);
        List<MarkdownBlock> blocks = new ArrayList<>();
        BuilderContext context = new BuilderContext();
        processTopLevel(document, new ArrayList<>(), blocks, context);
        return blocks;
    }

    private void processTopLevel(Node parent,
                                 List<HeadingInfo> headingStack,
                                 List<MarkdownBlock> blocks,
                                 BuilderContext context) {
        for (Node child = parent.getFirstChild(); child != null; child = child.getNext()) {
            if (child instanceof Heading heading) {
                String text = collectText(heading).trim();
                if (text.isEmpty()) {
                    continue;
                }
                // 必须先按新标题级别弹出旧标题，再计算父级路径。
                // 否则遇到第二个同级标题（如第二个 ###）或级别回退（### 后出现 ##）时，
                // 路径会错误地包含前一个同级/下级标题，导致增量同步挂错父节点。
                while (!headingStack.isEmpty()
                        && headingStack.get(headingStack.size() - 1).level() >= heading.getLevel()) {
                    headingStack.remove(headingStack.size() - 1);
                }
                List<String> path = currentPath(headingStack);
                String anchor = headingAnchor(path, heading.getLevel());
                emit(blocks, context, BlockType.HEADING, text, path, heading.getLevel(), anchor);
                headingStack.add(new HeadingInfo(heading.getLevel(), text));
            } else if (child instanceof BulletList bulletList) {
                processList(bulletList, true, 1, new ArrayList<>(), headingStack, blocks, context);
            } else if (child instanceof OrderedList orderedList) {
                processList(orderedList, false, 1, new ArrayList<>(), headingStack, blocks, context);
            } else if (child instanceof Paragraph paragraph) {
                String text = collectText(paragraph).trim();
                if (!text.isEmpty()) {
                    List<String> path = currentPath(headingStack);
                    emit(blocks, context, BlockType.PARAGRAPH, text, path, null,
                            orderedAnchor(path, "paragraph", context));
                }
            } else if (child instanceof BlockQuote quote) {
                String text = collectText(quote).trim();
                if (!text.isEmpty()) {
                    List<String> path = currentPath(headingStack);
                    emit(blocks, context, BlockType.QUOTE, text, path, null,
                            orderedAnchor(path, "quote", context));
                }
            } else if (child instanceof FencedCodeBlock fenced) {
                emitCode(blocks, context, fenced.getLiteral(), headingStack);
            } else if (child instanceof IndentedCodeBlock indented) {
                emitCode(blocks, context, indented.getLiteral(), headingStack);
            } else if (child instanceof TableBlock table) {
                String text = collectText(table).trim();
                if (!text.isEmpty()) {
                    List<String> path = currentPath(headingStack);
                    emit(blocks, context, BlockType.TABLE, text, path, null,
                            orderedAnchor(path, "table", context));
                }
            } else if (child instanceof ThematicBreak) {
                // 分隔线没有文本内容，不产生块
            } else if (child instanceof HtmlBlock htmlBlock) {
                String text = htmlBlock.getLiteral();
                if (text != null && !text.isBlank()) {
                    List<String> path = currentPath(headingStack);
                    emit(blocks, context, BlockType.OTHER, text, path, null,
                            orderedAnchor(path, "other", context));
                }
            }
        }
    }

    private void processList(Node listNode,
                             boolean bullet,
                             int depth,
                             List<String> ancestorItemTexts,
                             List<HeadingInfo> headingStack,
                             List<MarkdownBlock> blocks,
                             BuilderContext context) {
        for (Node child = listNode.getFirstChild(); child != null; child = child.getNext()) {
            if (!(child instanceof ListItem listItem)) {
                continue;
            }
            String directText = collectListItemText(listItem).trim();
            if (!directText.isEmpty()) {
                List<String> path = currentPath(headingStack);
                String anchor = listAnchor(path, bullet, directText, ancestorItemTexts);
                emit(blocks, context,
                        bullet ? BlockType.BULLET_LIST_ITEM : BlockType.ORDERED_LIST_ITEM,
                        directText, path, depth, anchor);
            }
            for (Node inner = listItem.getFirstChild(); inner != null; inner = inner.getNext()) {
                if (inner instanceof BulletList nestedList) {
                    List<String> nextAncestors = new ArrayList<>(ancestorItemTexts);
                    if (!directText.isEmpty()) {
                        nextAncestors.add(directText);
                    }
                    processList(nestedList, true, depth + 1,
                            nextAncestors, headingStack, blocks, context);
                } else if (inner instanceof OrderedList nestedList) {
                    List<String> nextAncestors = new ArrayList<>(ancestorItemTexts);
                    if (!directText.isEmpty()) {
                        nextAncestors.add(directText);
                    }
                    processList(nestedList, false, depth + 1,
                            nextAncestors, headingStack, blocks, context);
                }
            }
        }
    }

    private void emitCode(List<MarkdownBlock> blocks,
                          BuilderContext context,
                          String literal,
                          List<HeadingInfo> headingStack) {
        if (literal == null || literal.isBlank()) {
            return;
        }
        List<String> path = currentPath(headingStack);
        emit(blocks, context, BlockType.CODE_BLOCK, literal, path, null,
                orderedAnchor(path, "code", context));
    }

    private void emit(List<MarkdownBlock> blocks,
                      BuilderContext context,
                      BlockType type,
                      String rawText,
                      List<String> headingPath,
                      Integer level,
                      String anchor) {
        String normalized = MarkdownNormalizer.normalize(rawText, type);
        if (normalized.isEmpty() && type != BlockType.CODE_BLOCK) {
            return;
        }
        String uniqueAnchor = context.deduplicateAnchor(anchor);
        String blockId = "blk_" + MarkdownNormalizer.sha256Hex(uniqueAnchor).substring(0, 16);
        String contentHash = MarkdownNormalizer.sha256(normalized, type, headingPath);
        blocks.add(new MarkdownBlock(
                blockId, type, rawText.trim().replace('\n', ' '), normalized,
                List.copyOf(headingPath), level, blocks.size(), uniqueAnchor, contentHash));
    }

    private static List<String> currentPath(List<HeadingInfo> headingStack) {
        return headingStack.stream().map(HeadingInfo::text).toList();
    }

    /** §8.3 示例：光合作用 > 原料#heading(2)。锚点不含标题自身文字，标题改名时锚点不变、哈希变化。 */
    private static String headingAnchor(List<String> path, int level) {
        String prefix = path.isEmpty() ? "" : String.join(" > ", path);
        return prefix + "#heading(" + level + ")";
    }

    /** §8.3 示例：光合作用 > 原料#bullet(二氧化碳)；嵌套列表追加祖先项。 */
    private static String listAnchor(List<String> path, boolean bullet, String text,
                                     List<String> ancestorItemTexts) {
        String prefix = path.isEmpty() ? "" : String.join(" > ", path);
        StringBuilder builder = new StringBuilder(prefix);
        String kind = bullet ? "bullet" : "ordered";
        for (String ancestor : ancestorItemTexts) {
            builder.append('#').append(kind).append('(').append(ancestor).append(')');
        }
        builder.append('#').append(kind).append('(').append(text).append(')');
        return builder.toString();
    }

    /** §8.3 示例：光合作用#paragraph(order=5)。 */
    private static String orderedAnchor(List<String> path, String kind, BuilderContext context) {
        String prefix = path.isEmpty() ? "" : String.join(" > ", path);
        int orderOfKind = context.nextKindOrder(kind, path);
        return prefix + "#" + kind + "(order=" + orderOfKind + ")";
    }

    private static String collectText(Node node) {
        StringBuilder builder = new StringBuilder();
        node.accept(new AbstractVisitor() {
            @Override
            public void visit(Text text) {
                builder.append(text.getLiteral());
            }

            @Override
            public void visit(Code code) {
                builder.append(code.getLiteral());
            }

            @Override
            public void visit(SoftLineBreak softLineBreak) {
                builder.append(' ');
            }

            @Override
            public void visit(HardLineBreak hardLineBreak) {
                builder.append('\n');
            }
        });
        return builder.toString();
    }

    /** 收集列表项直属内容的文本，不包含嵌套列表里的内容。 */
    private static String collectListItemText(ListItem listItem) {
        StringBuilder builder = new StringBuilder();
        for (Node child = listItem.getFirstChild(); child != null; child = child.getNext()) {
            if (child instanceof BulletList || child instanceof OrderedList) {
                continue;
            }
            String text = collectText(child).trim();
            if (!text.isEmpty()) {
                if (!builder.isEmpty()) {
                    builder.append(' ');
                }
                builder.append(text);
            }
        }
        return builder.toString();
    }

    private record HeadingInfo(int level, String text) {
    }

    private static final class BuilderContext {
        private final Map<String, Integer> anchorCounts = new HashMap<>();
        private final Map<String, Integer> kindOrders = new HashMap<>();

        private String deduplicateAnchor(String anchor) {
            int count = anchorCounts.merge(anchor, 1, Integer::sum);
            return count == 1 ? anchor : anchor + "~" + count;
        }

        private int nextKindOrder(String kind, List<String> path) {
            String key = kind + "|" + String.join(" > ", path);
            int current = kindOrders.merge(key, 1, Integer::sum);
            return current - 1;
        }
    }
}
