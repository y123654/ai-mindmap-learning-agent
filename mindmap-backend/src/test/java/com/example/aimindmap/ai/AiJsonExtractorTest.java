package com.example.aimindmap.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class AiJsonExtractorTest {

    @Test
    void parsesPlainJson() {
        String json = AiJsonExtractor.extract("  {\"operations\":[]}  ");
        assertThat(json).isEqualTo("{\"operations\":[]}");
    }

    @Test
    void stripsMarkdownCodeFence() {
        String raw = "```json\n" + "{\"operations\":[]}\n" + "```";
        assertThat(AiJsonExtractor.extract(raw)).isEqualTo("{\"operations\":[]}");
    }

    @Test
    void stripsPlainCodeFenceAndSurroundingText() {
        String raw = "好的，结果如下：\n```\n{\"a\":1}\n```\n请查收";
        assertThat(AiJsonExtractor.extract(raw)).isEqualTo("{\"a\":1}");
    }

    @Test
    void takesOuterBracesWhenModelAddsNotes() {
        String raw = "结果：{\"operations\":[]} 以上";
        assertThat(AiJsonExtractor.extract(raw)).isEqualTo("{\"operations\":[]}");
    }

    @Test
    void failsOnEmptyOrJsonlessOutput() {
        assertThatThrownBy(() -> AiJsonExtractor.extract("   "))
                .isInstanceOf(AiGatewayException.class);
        assertThatThrownBy(() -> AiJsonExtractor.extract("没有 JSON"))
                .isInstanceOf(AiGatewayException.class)
                .hasMessageContaining("JSON");
    }
}