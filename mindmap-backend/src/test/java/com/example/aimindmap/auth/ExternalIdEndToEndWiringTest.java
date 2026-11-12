package com.example.aimindmap.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.aimindmap.ai.AiGateway;
import com.example.aimindmap.assistant.KnowledgeAssistantController;
import com.example.aimindmap.assistant.KnowledgeAssistantService;
import com.example.aimindmap.document.DocumentController;
import com.example.aimindmap.importpdf.PdfImportController;
import com.example.aimindmap.importpdf.PdfImportService;
import com.example.aimindmap.mindmap.MindMapController;
import com.example.aimindmap.mindmap.MindMapNodeController;
import com.example.aimindmap.mindmap.SyncJobController;
import com.example.aimindmap.mindmap.SyncJobResponseMapper;
import com.example.aimindmap.mindmap.SyncJobService;
import com.example.aimindmap.question.QuestionController;
import com.example.aimindmap.question.QuestionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ApplicationContext;

/**
 * 修订第 44 条：真实 Spring 容器（含数据库）装配冒烟，
 * 确认 ExternalIdCodec 注入到所有 Controller/Service 后上下文仍能正常启动。
 * AI 网关 mock 掉（本机命令行沙盒不允许 JDK NIO 回环通道，与业务无关）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class ExternalIdEndToEndWiringTest {

    @Autowired
    private ApplicationContext context;

    @MockBean
    private AiGateway aiGateway;

    @Test
    void allBeansWireWithExternalIdCodec() {
        assertThat(context.getBean(ExternalIdCodec.class)).isNotNull();
        assertThat(context.getBean(JwtService.class)).isNotNull();
        assertThat(context.getBean(DocumentController.class)).isNotNull();
        assertThat(context.getBean(MindMapController.class)).isNotNull();
        assertThat(context.getBean(MindMapNodeController.class)).isNotNull();
        assertThat(context.getBean(SyncJobController.class)).isNotNull();
        assertThat(context.getBean(SyncJobService.class)).isNotNull();
        assertThat(context.getBean(SyncJobResponseMapper.class)).isNotNull();
        assertThat(context.getBean(QuestionController.class)).isNotNull();
        assertThat(context.getBean(QuestionService.class)).isNotNull();
        assertThat(context.getBean(PdfImportController.class)).isNotNull();
        assertThat(context.getBean(PdfImportService.class)).isNotNull();
        assertThat(context.getBean(KnowledgeAssistantController.class)).isNotNull();
        assertThat(context.getBean(KnowledgeAssistantService.class)).isNotNull();
    }
}