package com.example.aimindmap.importpdf;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.aimindmap.auth.AuthUser;
import com.example.aimindmap.auth.ExternalIdCodec;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * 阶段五-B（方案 A）：任务查询映射到 GET /api/imports/pdf/{jobId}，
 * 旧路径 GET /api/imports/{jobId} 不再有映射。standalone MockMvc + 纯 Mockito，
 * 风格与 AgentMemoryControllerTest 一致；归属校验 404 由 PdfImportServiceTest 覆盖，不重复。
 */
class PdfImportControllerTest {

    private PdfImportService service;
    private MockMvc mockMvc;
    private final ExternalIdCodec idCodec = new ExternalIdCodec("pdf-controller-test-salt");

    @BeforeEach
    void setUp() {
        service = mock(PdfImportService.class);
        PdfImportController controller = new PdfImportController(service, idCodec);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setCustomArgumentResolvers(new FixedAuthUserResolver())
                .build();
    }

    @Test
    void getJobOnPdfPathIsVisibleToOwnerEvenWhilePending() throws Exception {
        String externalId = idCodec.encode(77L);
        when(service.getJob(8L, 77L)).thenReturn(new PdfImportDtos.JobResponse(
                externalId, "PENDING", "课件.pdf", null, null, false,
                null, null, null, null, Instant.parse("2026-10-17T01:02:03Z"), null));

        mockMvc.perform(get("/api/imports/pdf/{jobId}", externalId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(externalId))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.originalFileName").value("课件.pdf"));

        verify(service).getJob(8L, 77L);
    }

    @Test
    void legacyGetJobPathWithoutPdfSegmentIsNoLongerMapped() throws Exception {
        mockMvc.perform(get("/api/imports/{jobId}", idCodec.encode(77L)))
                .andExpect(status().isNotFound());
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
