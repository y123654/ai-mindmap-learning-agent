package com.example.aimindmap.parser;

import java.util.List;

/**
 * v1.1 方案 §8.5 差异比较结果。
 *
 * @param relocated 【v1.1 修订（方案 A·搬家识别）】文字未变、仅因上级标题改名而整体
 *                  更换锚点/路径的块（旧版本块 -> 新版本块）。属于内部差异类别，
 *                  不改变 §8.5 对外 change_summary 的五个键，前端对这类 update_node
 *                  静默处理（不做琥珀高亮）。
 */
public record MarkdownDiff(
        List<MarkdownBlock> added,
        List<ChangedBlock> changed,
        List<MarkdownBlock> removed,
        List<MarkdownBlock> unchanged,
        List<ChangedBlock> uncertain,
        List<ChangedBlock> relocated
) {

    /**
     * 配对成功的两个块（旧版本块 -> 新版本块）。
     */
    public record ChangedBlock(MarkdownBlock oldBlock, MarkdownBlock newBlock) {
    }
}