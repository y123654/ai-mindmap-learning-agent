package com.example.aimindmap.auth;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.aimindmap.document.DocumentController;
import com.example.aimindmap.document.DocumentEntity;
import com.example.aimindmap.document.DocumentRepository;
import com.example.aimindmap.document.DocumentService;
import com.example.aimindmap.mindmap.MindMapRepository;
import com.example.aimindmap.mindmap.SyncJobRepository;
import com.example.aimindmap.question.QuestionGenerationJobRepository;
import jakarta.servlet.DispatcherType;
import com.example.aimindmap.question.QuestionRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(DocumentController.class)
@TestPropertySource(properties = "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration")
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtService.class, ExternalIdCodec.class, OwnershipGuard.class})
class SecurityWebLayerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private ExternalIdCodec idCodec;

    @MockBean
    private DocumentService documentService;

    @MockBean
    private DocumentRepository documentRepository;

    @MockBean
    private MindMapRepository mindMapRepository;

    @MockBean
    private SyncJobRepository syncJobRepository;

    @MockBean
    private QuestionRepository questionRepository;

    @MockBean
    private QuestionGenerationJobRepository questionGenerationJobRepository;

    @Test
    void businessApiWithoutTokenReturns401Json() throws Exception {
        mockMvc.perform(get("/api/documents"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void asyncDispatchWithValidJwtCanPassSecurityFilter() throws Exception {
        UserEntity user = new UserEntity();
        user.setUsername("alice");
        try {
            var idField = UserEntity.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(user, 1L);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }

        DocumentEntity document = new DocumentEntity();
        try {
            var idField = DocumentEntity.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(document, 10L);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
        document.setUserId(1L);
        document.setTitle("测试笔记");
        document.setContent("");
        when(documentService.listDocuments(1L)).thenReturn(List.of(document));

        mockMvc.perform(get("/api/documents")
                        .with(request -> {
                            request.setDispatcherType(DispatcherType.ASYNC);
                            return request;
                        })
                        .header("Authorization", "Bearer " + jwtService.issue(user)))
                .andExpect(status().isOk());
    }

    @Test
    void businessApiWithValidJwtCanPassSecurityFilter() throws Exception {
        UserEntity user = new UserEntity();
        user.setUsername("alice");
        try {
            var idField = UserEntity.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(user, 1L);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }

        DocumentEntity document = new DocumentEntity();
        try {
            var idField = DocumentEntity.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(document, 10L);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
        document.setUserId(1L);
        document.setTitle("测试笔记");
        document.setContent("");
        when(documentService.listDocuments(1L)).thenReturn(List.of(document));

        mockMvc.perform(get("/api/documents")
                        .header("Authorization", "Bearer " + jwtService.issue(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(idCodec.encode(10L)))
                .andExpect(jsonPath("$[0].title").value("测试笔记"));
    }
}
