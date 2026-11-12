package com.example.aimindmap.parser;

import java.util.List;

/**
 * v1.1 方案 §8.1 MarkdownBlock。
 *
 * @param blockId        稳定块 ID（blk_ 前缀，由稳定锚点派生）
 * @param type           块类型
 * @param text           原始纯文本（不含列表前导符号）
 * @param normalizedText 规范化文本（§8.4）
 * @param headingPath    所属标题路径（不含自身）
 * @param level          HEADING 为标题级别 1-6；列表项为列表嵌套深度（从 1 开始）；其他块为 null
 * @param order          块在文档中的顺序（从 0 开始）
 * @param anchor         稳定锚点（§8.3）
 * @param contentHash    SHA-256(normalizedText + "|" + type + "|" + headingPath)
 */
public record MarkdownBlock(
        String blockId,
        BlockType type,
        String text,
        String normalizedText,
        List<String> headingPath,
        Integer level,
        Integer order,
        String anchor,
        String contentHash
) {
}