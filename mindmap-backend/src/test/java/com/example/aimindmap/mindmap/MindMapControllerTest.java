package com.example.aimindmap.mindmap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.aimindmap.auth.AuthUser;
import com.example.aimindmap.auth.ExternalIdCodec;
import com.example.aimindmap.auth.OwnershipGuard;
import com.example.aimindmap.common.error.ApiException;
import com.example.aimindmap.common.error.GlobalExceptionHandler;
import com.example.aimindmap.document.DocumentEntity;
import com.example.aimindmap.document.DocumentRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * 缺陷修复（2026-11-04，P3）：已软删除文档的导图接口必须与文档详情接口同口径（404），
 * 不能再出现"文档 404、导图 200"。
 *
 * <p>纯 Mockito + standalone MockMvc，不起 Spring 上下文（不连库、不连 Qdrant）。</p>
 */
class MindMapControllerTest {

    private static final long CURRENT_USER_ID = 8L;
    private static final long DOCUMENT_ID = 3L;
    private static final long MINDMAP_ID = 7L;
    private static final String GRAPH_JSON = """
            {"rootId":"n1","version":2,"tree":{"id":"n1","label":"根节点","children":[]}}
            """;

    private final MindMapRepository mindMapRepository = mock(MindMapRepository.class);
    private final DocumentRepository documentRepository = mock(DocumentRepository.class);
    private final OwnershipGuard ownershipGuard = mock(OwnershipGuard.class);
    private final ExternalIdCodec idCodec = new ExternalIdCodec("mindmap-controller-test-salt");
    private final ObjectMapper objectMapper = new ObjectMapper();

    private MindMapController controller;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        controller = new MindMapController(
                mindMapRepository, documentRepository, objectMapper, ownershipGuard, idCodec);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new FixedAuthUserResolver())
                .build();
    }

    @Test
    void activeDocumentReturnsMindMap() throws Exception {
        when(documentRepository.findById(DOCUMENT_ID)).thenReturn(Optional.of(document("active")));
        when(mindMapRepository.findByDocumentId(DOCUMENT_ID)).thenReturn(Optional.of(mindMap()));

        mockMvc.perform(get("/api/documents/{documentId}/mindmap", idCodec.encode(DOCUMENT_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(idCodec.encode(MINDMAP_ID)))
                .andExpect(jsonPath("$.documentId").value(idCodec.encode(DOCUMENT_ID)))
                .andExpect(jsonPath("$.graphVersion").value(2))
                .andExpect(jsonPath("$.syncStatus").value("idle"))
                .andExpect(jsonPath("$.graph.rootId").value("n1"));

        verify(ownershipGuard).requireMindmapByDocument(DOCUMENT_ID, CURRENT_USER_ID);
    }

    @Test
    void deletedDocumentReturnsNotFound() throws Exception {
        // 关键场景：导图行还在（软删除不删 mindmaps），但文档已 deleted → 必须 404
        when(documentRepository.findById(DOCUMENT_ID)).thenReturn(Optional.of(document("deleted")));
        when(mindMapRepository.findByDocumentId(DOCUMENT_ID)).thenReturn(Optional.of(mindMap()));

        assertThatThrownBy(() -> controller.getMindMap(
                new AuthUser(CURRENT_USER_ID, "alice"), idCodec.encode(DOCUMENT_ID)))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo("NOT_FOUND");
                    assertThat(ex.getStatus().value()).isEqualTo(404);
                });

        mockMvc.perform(get("/api/documents/{documentId}/mindmap", idCodec.encode(DOCUMENT_ID)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void missingDocumentRowReturnsNotFound() throws Exception {
        when(documentRepository.findById(DOCUMENT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.getMindMap(
                new AuthUser(CURRENT_USER_ID, "alice"), idCodec.encode(DOCUMENT_ID)))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo("NOT_FOUND");
                    assertThat(ex.getStatus().value()).isEqualTo(404);
                });

        mockMvc.perform(get("/api/documents/{documentId}/mindmap", idCodec.encode(DOCUMENT_ID)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        // 文档行都不存在时，不应该继续去查导图（避免拿已删/不存在的文档读图）
        verify(mindMapRepository, never()).findByDocumentId(anyLong());
    }

    private static DocumentEntity document(String status) {
        DocumentEntity document = new DocumentEntity();
        document.setUserId(CURRENT_USER_ID);
        document.setTitle("笔记");
        document.setContent("");
        document.setContentVersion(1);
        document.setStatus(status);
        return document;
    }

    private static MindMapEntity mindMap() {
        MindMapEntity mindMap = new MindMapEntity();
        ReflectionTestUtils.setField(mindMap, "id", MINDMAP_ID);
        mindMap.setUserId(CURRENT_USER_ID);
        mindMap.setDocumentId(DOCUMENT_ID);
        mindMap.setGraph(GRAPH_JSON);
        mindMap.setGraphVersion(2);
        mindMap.setSyncStatus("idle");
        return mindMap;
    }

    /** @AuthenticationPrincipal AuthUser 的测试替身：固定注入当前用户。 */
    private static final class FixedAuthUserResolver implements HandlerMethodArgumentResolver {

        @Override
        public boolean supportsParameter(MethodParameter parameter) {
            return parameter.getParameterType().equals(AuthUser.class);
        }

        @Override
        public Object resolveArgument(MethodParameter parameter,
                                      ModelAndViewContainer mavContainer,
                                      NativeWebRequest webRequest,
                                      WebDataBinderFactory binderFactory) {
            return new AuthUser(CURRENT_USER_ID, "alice");
        }
    }
}
