package com.example.aimindmap.vectorindex;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class MarkdownChunkerTest {

    private final MarkdownChunker chunker = new MarkdownChunker();

    @Test
    void shortDocumentProducesSingleChunk() {
        String markdown = "# 操作系统\n\n进程是资源分配的基本单位。";

        List<MarkdownChunker.Chunk> chunks = chunker.chunk(markdown);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).chunkIndex()).isZero();
        assertThat(chunks.get(0).text()).contains("操作系统").contains("进程是资源分配");
        assertThat(chunks.get(0).sourcePath()).containsExactly("操作系统");
    }

    @Test
    void longContentRespectsSizeAndOverlap() {
        String unit = "进程是资源分配的基本单位，线程是CPU调度的基本单位。";
        String markdown = "# 操作系统\n\n" + unit.repeat(80);

        List<MarkdownChunker.Chunk> chunks = chunker.chunk(markdown);

        assertThat(chunks.size()).isGreaterThan(2);
        for (MarkdownChunker.Chunk chunk : chunks) {
            assertThat(chunk.text().length()).isLessThanOrEqualTo(MarkdownChunker.MAX_CHARS);
        }
        for (int index = 0; index < chunks.size() - 1; index++) {
            assertThat(chunks.get(index).text().length())
                    .as("非末尾片应达到 500 字符下限")
                    .isGreaterThanOrEqualTo(MarkdownChunker.TARGET_MIN_CHARS);
            int overlap = measureOverlap(chunks.get(index), chunks.get(index + 1));
            assertThat(overlap)
                    .as("相邻片重叠量应在 80~120 字符之间")
                    .isBetween(MarkdownChunker.OVERLAP_MIN, MarkdownChunker.OVERLAP_MAX);
        }

        for (int index = 0; index < chunks.size(); index++) {
            assertThat(chunks.get(index).chunkIndex()).isEqualTo(index);
            assertThat(chunks.get(index).sourcePath()).containsExactly("操作系统");
        }
    }

    @Test
    void keepsMultilevelHeadingPaths() {
        String markdown = """
                # 操作系统
                概述内容
                ## 进程管理
                进程管理内容
                ### 进程调度
                调度内容
                ## 内存管理
                内存内容
                """;

        List<MarkdownChunker.Chunk> chunks = chunker.chunk(markdown);

        assertThat(chunks).hasSize(4);
        assertThat(chunks.get(0).sourcePath()).containsExactly("操作系统");
        assertThat(chunks.get(1).sourcePath()).containsExactly("操作系统", "进程管理");
        assertThat(chunks.get(2).sourcePath())
                .containsExactly("操作系统", "进程管理", "进程调度");
        assertThat(chunks.get(3).sourcePath()).containsExactly("操作系统", "内存管理");
    }

    @Test
    void singleHugeLineIsHardSplitWithinLimit() {
        String line = "ABCDEFGHIJ".repeat(300);

        List<MarkdownChunker.Chunk> chunks = chunker.chunk(line);

        assertThat(chunks.size()).isGreaterThan(2);
        for (MarkdownChunker.Chunk chunk : chunks) {
            assertThat(chunk.text().length()).isLessThanOrEqualTo(MarkdownChunker.MAX_CHARS);
        }
    }

    @Test
    void emptyMarkdownProducesNoChunks() {
        assertThat(chunker.chunk("")).isEmpty();
        assertThat(chunker.chunk(null)).isEmpty();
        assertThat(chunker.chunk("   \n  ")).isEmpty();
    }

    private int measureOverlap(MarkdownChunker.Chunk previous, MarkdownChunker.Chunk next) {
        String body = bodyOf(previous);
        for (int length = MarkdownChunker.OVERLAP_MAX; length >= 1; length--) {
            if (body.length() < length) {
                continue;
            }
            String suffix = body.substring(body.length() - length);
            if (next.text().contains(suffix)) {
                return length;
            }
        }
        return -1;
    }

    private String bodyOf(MarkdownChunker.Chunk chunk) {
        if (chunk.sourcePath().isEmpty()) {
            return chunk.text();
        }
        String heading = MarkdownChunker.renderHeadings(chunk.sourcePath());
        return chunk.text().substring(heading.length() + 1);
    }
}
