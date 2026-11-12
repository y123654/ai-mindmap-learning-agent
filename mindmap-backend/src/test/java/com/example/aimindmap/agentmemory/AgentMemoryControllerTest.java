package com.example.aimindmap.agentmemory;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.aimindmap.auth.AuthUser;
import com.example.aimindmap.auth.ExternalIdCodec;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AgentMemoryControllerTest {

    private AgentMemoryService service;
    private MockMvc mockMvc;
    private final ExternalIdCodec idCodec = new ExternalIdCodec("controller-test-salt");

    @BeforeEach
    void setUp() {
        service = mock(AgentMemoryService.class);
        AgentMemoryController controller = new AgentMemoryController(service, idCodec);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setCustomArgumentResolvers(new FixedAuthUserResolver())
                .build();
    }

    @Test
    void getListReturnsEncodedMemories() throws Exception {
        when(service.listMemories(8L, "active"))
                .thenReturn(List.of(memory(7L, "active", "preference", "喜欢代码")));

        mockMvc.perform(get("/api/agent/memories").param("status", "active"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(idCodec.encode(7L)))
                .andExpect(jsonPath("$[0].status").value("active"))
                .andExpect(jsonPath("$[0].memoryType").value("preference"))
                .andExpect(jsonPath("$[0].content").value("喜欢代码"));
    }

    @Test
    void postCreatesCandidate() throws Exception {
        when(service.createCandidate(eq(8L), any()))
                .thenReturn(memory(7L, "candidate", "fact", "新事实"));

        mockMvc.perform(post("/api/agent/memories")
                        .contentType("application/json")
                        .content("""
                                {"content":"新事实","memoryType":"fact",
                                "importance":3,"confidence":100}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("candidate"))
                .andExpect(jsonPath("$.id").value(idCodec.encode(7L)));
    }

    @Test
    void putUpdatesMemory() throws Exception {
        when(service.updateMemory(eq(8L), eq(7L), any()))
                .thenReturn(memory(7L, "active", "goal", "目标更新"));

        mockMvc.perform(put("/api/agent/memories/{id}", idCodec.encode(7L))
                        .contentType("application/json")
                        .content("""
                                {"content":"目标更新","memoryType":"goal",
                                "importance":4,"confidence":90}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value("目标更新"));
    }

    @Test
    void deleteReturnsNoContent() throws Exception {
        mockMvc.perform(delete("/api/agent/memories/{id}", idCodec.encode(7L)))
                .andExpect(status().isNoContent());
        verify(service).deleteMemory(8L, 7L);
    }

    @Test
    void confirmActivates() throws Exception {
        when(service.confirmMemory(8L, 7L))
                .thenReturn(memory(7L, "active", "preference", "x"));

        mockMvc.perform(post("/api/agent/memories/{id}/confirm", idCodec.encode(7L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("active"));
    }

    @Test
    void archiveMovesToArchived() throws Exception {
        when(service.archiveMemory(8L, 7L))
                .thenReturn(memory(7L, "archived", "preference", "x"));

        mockMvc.perform(post("/api/agent/memories/{id}/archive", idCodec.encode(7L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("archived"));
    }

    @Test
    void clearIsMappedAsLiteralPathAndOnlyClearsOwnMemories() throws Exception {
        when(service.clearMemories(8L)).thenReturn(2);

        mockMvc.perform(post("/api/agent/memories/clear"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deletedCount").value(2));

        verify(service).clearMemories(8L);
    }

    private static AgentMemoryEntity memory(Long id, String status, String type, String content) {
        AgentMemoryEntity memory = new AgentMemoryEntity();
        setId(memory, id);
        memory.setUserId(8L);
        memory.setStatus(status);
        memory.setMemoryType(type);
        memory.setContent(content);
        memory.setImportance((short) 3);
        memory.setConfidence((short) 100);
        return memory;
    }

    private static void setId(Object target, Long id) {
        try {
            var field = target.getClass().getDeclaredField("id");
            field.setAccessible(true);
            field.set(target, id);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

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
            return new AuthUser(8L, "alice");
        }
    }
}
