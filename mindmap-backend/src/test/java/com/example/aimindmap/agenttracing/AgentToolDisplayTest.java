package com.example.aimindmap.agenttracing;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * P0-2：工具中文展示映射与安全 detail 白名单。
 * 重点断言 detail 绝不泄露资源内部数字 ID、userId、工具结果正文。
 */
class AgentToolDisplayTest {

    @Test
    void labelMapsAllSixTools() {
        assertThat(AgentToolDisplay.label("search_my_documents")).isEqualTo("检索课程笔记");
        assertThat(AgentToolDisplay.label("read_my_document")).isEqualTo("读取笔记全文");
        assertThat(AgentToolDisplay.label("search_mindmap_nodes")).isEqualTo("检索思维导图");
        assertThat(AgentToolDisplay.label("read_mindmap")).isEqualTo("读取思维导图");
        assertThat(AgentToolDisplay.label("search_my_questions")).isEqualTo("检索习题库");
        assertThat(AgentToolDisplay.label("search_my_memory")).isEqualTo("检索长期记忆");
    }

    @Test
    void unknownToolFallsBackToGenericLabelAndEmptyDetail() {
        assertThat(AgentToolDisplay.label("some_future_tool")).isEqualTo("查询学习资料");
        assertThat(AgentToolDisplay.detail("some_future_tool", "{\"query\":\"进程\"}")).isEmpty();
        assertThat(AgentToolDisplay.label(null)).isEqualTo("查询学习资料");
        assertThat(AgentToolDisplay.detail(null, "{}")).isEmpty();
    }

    @Test
    void queryIsTruncatedToThirtyChars() {
        String longQuery = "进程调度算法".repeat(10); // 60 字
        String detail = AgentToolDisplay.detail("search_my_documents",
                "{\"query\":\"" + longQuery + "\"}");

        assertThat(detail).hasSize(AgentToolDisplay.DETAIL_MAX_CHARS);
        assertThat(detail).isEqualTo(longQuery.substring(0, AgentToolDisplay.DETAIL_MAX_CHARS));
    }

    @Test
    void queryOfExactlyThirtyCharsIsKeptAsIs() {
        String query = "x".repeat(30);
        assertThat(AgentToolDisplay.detail("search_mindmap_nodes",
                "{\"query\":\"" + query + "\"}")).isEqualTo(query);
    }

    @Test
    void readToolsUseFixedDetailAndNeverEchoNumericIds() {
        String documentDetail = AgentToolDisplay.detail("read_my_document",
                "{\"documentId\":\"888901\"}");
        String mindmapDetail = AgentToolDisplay.detail("read_mindmap",
                "{\"mindmapId\":\"765432\"}");

        assertThat(documentDetail).isEqualTo("读取笔记全文").doesNotContain("888901");
        assertThat(mindmapDetail).isEqualTo("读取思维导图完整结构").doesNotContain("765432");
    }

    @Test
    void searchDetailOnlyExposesQueryAndIgnoresIdsAndOtherArgs() {
        String input = "{\"query\":\"时间片轮转\",\"documentId\":\"888901\","
                + "\"userId\":\"77\",\"top\":5,\"body\":\"工具结果正文\"}";
        String detail = AgentToolDisplay.detail("search_my_documents", input);

        assertThat(detail).isEqualTo("时间片轮转");
        assertThat(detail)
                .doesNotContain("888901")
                .doesNotContain("77")
                .doesNotContain("工具结果正文");
    }

    @Test
    void invalidJsonDegradesToEmptyDetail() {
        assertThat(AgentToolDisplay.detail("search_my_documents", "not-a-json")).isEmpty();
        assertThat(AgentToolDisplay.detail("search_my_documents", "{\"query\":")).isEmpty();
        assertThat(AgentToolDisplay.detail("search_my_questions", "[1,2,3]")).isEmpty();
        assertThat(AgentToolDisplay.detail("search_my_memory", null)).isEmpty();
        assertThat(AgentToolDisplay.detail("search_my_memory", "   ")).isEmpty();
    }

    @Test
    void missingOrNonTextualQueryDegradesToEmptyDetail() {
        assertThat(AgentToolDisplay.detail("search_my_documents", "{}")).isEmpty();
        assertThat(AgentToolDisplay.detail("search_my_documents", "{\"query\":123}")).isEmpty();
        assertThat(AgentToolDisplay.detail("search_my_documents", "{\"top\":2}")).isEmpty();
    }

    @Test
    void whitespaceInQueryIsCollapsedBeforeTruncation() {
        String detail = AgentToolDisplay.detail("search_my_questions",
                "{\"query\":\"  进程\\n调度   算法  \"}");

        assertThat(detail).isEqualTo("进程 调度 算法");
    }
}
