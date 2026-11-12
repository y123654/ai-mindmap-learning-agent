package com.example.aimindmap.parser;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class MarkdownParserTest {

    private final MarkdownParser parser = new MarkdownParser();

    @Test
    void parsesHeadingsBulletsParagraphs() {
        String markdown = "# 光合作用\n\n## 原料\n\n- 二氧化碳\n- 水\n\n这是普通段落。\n";
        List<MarkdownBlock> blocks = parser.parse(markdown);

        assertThat(blocks).hasSize(5);
        assertThat(blocks.get(0).type()).isEqualTo(BlockType.HEADING);
        assertThat(blocks.get(0).normalizedText()).isEqualTo("光合作用");
        assertThat(blocks.get(0).headingPath()).isEmpty();

        assertThat(blocks.get(1).type()).isEqualTo(BlockType.HEADING);
        assertThat(blocks.get(1).normalizedText()).isEqualTo("原料");
        assertThat(blocks.get(1).headingPath()).containsExactly("光合作用");
        assertThat(blocks.get(1).anchor()).isEqualTo("光合作用#heading(2)");

        assertThat(blocks.get(2).type()).isEqualTo(BlockType.BULLET_LIST_ITEM);
        assertThat(blocks.get(2).normalizedText()).isEqualTo("二氧化碳");
        assertThat(blocks.get(2).headingPath()).containsExactly("光合作用", "原料");
        assertThat(blocks.get(2).anchor()).isEqualTo("光合作用 > 原料#bullet(二氧化碳)");

        assertThat(blocks.get(3).normalizedText()).isEqualTo("水");
        assertThat(blocks.get(4).type()).isEqualTo(BlockType.PARAGRAPH);
    }


    @Test
    void siblingAndFallbackHeadingsUseParentPathOnly() {
        String markdown = """
                # Agent 智能体基础知识

                ## 一、什么是 Agent

                普通介绍段落。

                ### 1. Agent 的基本能力

                - 感知

                ### 2. Agent 与普通聊天机器人的区别

                - 普通聊天机器人

                ## 二、Agent 的核心组成

                ### 1. 大语言模型

                - 模型
                """;
        List<MarkdownBlock> blocks = parser.parse(markdown);

        MarkdownBlock firstH3 = blocks.stream()
                .filter(b -> b.normalizedText().equals("1. Agent 的基本能力")).findFirst().orElseThrow();
        MarkdownBlock secondH3 = blocks.stream()
                .filter(b -> b.normalizedText().equals("2. Agent 与普通聊天机器人的区别")).findFirst().orElseThrow();
        MarkdownBlock secondH2 = blocks.stream()
                .filter(b -> b.normalizedText().equals("二、Agent 的核心组成")).findFirst().orElseThrow();
        MarkdownBlock thirdH3 = blocks.stream()
                .filter(b -> b.normalizedText().equals("1. 大语言模型")).findFirst().orElseThrow();

        // 两个平级三级标题的父路径都只能包含一级、二级标题，不能包含前一个三级标题
        assertThat(firstH3.headingPath()).containsExactly("Agent 智能体基础知识", "一、什么是 Agent");
        assertThat(secondH3.headingPath()).containsExactly("Agent 智能体基础知识", "一、什么是 Agent");
        // 三级标题后回到二级标题：父路径只剩一级标题
        assertThat(secondH2.headingPath()).containsExactly("Agent 智能体基础知识");
        // 两个平级二级标题基础锚点相同，由去重序号区分（既有 §8.3 机制）
        assertThat(secondH2.anchor()).isEqualTo("Agent 智能体基础知识#heading(2)~2");
        // 新二级标题下的三级标题挂到新的二级标题下
        assertThat(thirdH3.headingPath())
                .containsExactly("Agent 智能体基础知识", "二、Agent 的核心组成");
    }
    @Test
    void sameContentKeepsStableBlockIdAcrossParses() {
        String markdown = "# 光合作用\n\n## 原料\n\n- 二氧化碳\n- 水\n";
        List<MarkdownBlock> first = parser.parse(markdown);
        List<MarkdownBlock> second = parser.parse(markdown);

        assertThat(first.get(2).blockId()).isEqualTo(second.get(2).blockId());
        assertThat(first.get(2).contentHash()).isEqualTo(second.get(2).contentHash());
    }

    @Test
    void insertedListItemDoesNotChangeOtherAnchors() {
        String before = "# 光合作用\n\n## 原料\n\n- 二氧化碳\n- 水\n";
        String after = "# 光合作用\n\n## 原料\n\n- 二氧化碳\n- 阳光\n- 水\n";

        List<MarkdownBlock> oldBlocks = parser.parse(before);
        List<MarkdownBlock> newBlocks = parser.parse(after);

        MarkdownDiff diff = new MarkdownBlockDiffer().diff(oldBlocks, newBlocks);
        assertThat(diff.added()).hasSize(1);
        assertThat(diff.added().get(0).normalizedText()).isEqualTo("阳光");
        assertThat(diff.removed()).isEmpty();
        assertThat(diff.unchanged()).hasSize(4);
    }

    @Test
    void headingRenameDetectedAsChangedViaStableAnchor() {
        String before = "# 光合作用\n\n## 原料\n";
        String after = "# 光合作用\n\n## 原材料\n";

        MarkdownDiff diff = new MarkdownBlockDiffer().diff(parser.parse(before), parser.parse(after));
        assertThat(diff.changed()).hasSize(1);
        assertThat(diff.changed().get(0).newBlock().normalizedText()).isEqualTo("原材料");
    }
}

