package com.example.aimindmap.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.aimindmap.assistant.KnowledgeAssistantController;
import com.example.aimindmap.assistant.KnowledgeAssistantService;
import com.example.aimindmap.assistant.KnowledgeAssistantStreamer;
import com.example.aimindmap.auth.AuthUser;
import com.example.aimindmap.auth.ExternalIdCodec;
import com.example.aimindmap.auth.OwnershipGuard;
import com.example.aimindmap.common.error.GlobalExceptionHandler;
import com.example.aimindmap.document.DocumentController;
import com.example.aimindmap.document.DocumentService;
import com.example.aimindmap.importpdf.PdfImportController;
import com.example.aimindmap.importpdf.PdfImportService;
import com.example.aimindmap.mindmap.SyncJobController;
import com.example.aimindmap.mindmap.SyncJobResponseMapper;
import com.example.aimindmap.mindmap.SyncJobService;
import com.example.aimindmap.progress.ProgressBroadcaster;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * 阶段七-3：限流 Web 层测试（standalone MockMvc + 全局异常体系，风格对齐
 * {@code PdfImportControllerTest} / {@code AgentMemoryControllerTest}，不起 Spring 上下文）。
 *
 * <p>限流器用真实的 {@link InMemorySlidingWindowRateLimiter}（阈值经 {@link RateLimitThresholds} 注入），
 * 因此「未超限正常 / 第 N+1 次 429」是端到端真判定，不是打桩。
 *
 * <p><b>重点覆盖 SSE Accept 头坑：</b>AI 问答 {@code POST /sessions/{id}/stream} 客户端
 * Accept 是 {@code text/event-stream}，且 handler 的 {@code produces} 也登记了该媒体类型；
 * 429 响应必须显式 {@code Content-Type: application/json}，否则默认内容协商会因为
 * 「没有转换器能按 text/event-stream 写 JSON」把响应顶成 406。
 */
class RateLimitWebLayerTest {

    private static final long USER_ID = 8L;
    private static final String REJECT_MESSAGE = "请求过于频繁，请稍后再试";

    private final ExternalIdCodec idCodec = new ExternalIdCodec("ratelimit-web-layer-test-salt");

    private KnowledgeAssistantService assistantService;
    private KnowledgeAssistantStreamer assistantStreamer;
    private PdfImportService pdfImportService;
    private SyncJobService syncJobService;
    private SyncJobResponseMapper syncJobResponseMapper;
    private ProgressBroadcaster progressBroadcaster;
    private OwnershipGuard ownershipGuard;
    private DocumentService documentService;
    private RateLimitInterceptor interceptor;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        assistantService = mock(KnowledgeAssistantService.class);
        assistantStreamer = mock(KnowledgeAssistantStreamer.class);
        pdfImportService = mock(PdfImportService.class);
        syncJobService = mock(SyncJobService.class);
        syncJobResponseMapper = mock(SyncJobResponseMapper.class);
        progressBroadcaster = mock(ProgressBroadcaster.class);
        ownershipGuard = mock(OwnershipGuard.class);
        documentService = mock(DocumentService.class);
        when(documentService.listDocuments(anyLong())).thenReturn(List.of());
        authenticate(USER_ID);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void aiStreamWithinLimitReachesController() throws Exception {
        useThresholds(new RateLimitThresholds(2, 1, 1));

        mockMvc.perform(aiStreamRequest()).andExpect(status().isOk());
        mockMvc.perform(aiStreamRequest()).andExpect(status().isOk());

        verify(assistantService, atLeastOnce()).prepareUserMessage(anyLong(), anyLong(), anyString());
    }

    @Test
    void aiStreamOverLimitReturns429JsonEvenWithEventStreamAccept() throws Exception {
        useThresholds(new RateLimitThresholds(2, 1, 1));

        mockMvc.perform(aiStreamRequest()).andExpect(status().isOk());
        mockMvc.perform(aiStreamRequest()).andExpect(status().isOk());

        // 第 N+1 次：Accept 仍是 text/event-stream，但必须稳定返回 429 + JSON（不是 406）
        mockMvc.perform(aiStreamRequest())
                .andExpect(status().isTooManyRequests())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(jsonPath("$.message").value(REJECT_MESSAGE))
                .andExpect(jsonPath("$.time").exists());
        assertThat(interceptor.getRejections()).isEqualTo(1);
    }

    @Test
    void pdfUploadOverLimitReturns429() throws Exception {
        useThresholds(new RateLimitThresholds(20, 1, 10));

        mockMvc.perform(pdfRequest()).andExpect(status().isOk());
        mockMvc.perform(pdfRequest())
                .andExpect(status().isTooManyRequests())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(jsonPath("$.message").value(REJECT_MESSAGE))
                .andExpect(jsonPath("$.time").exists());
        verify(pdfImportService, atLeastOnce()).uploadPdf(anyLong(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void syncTriggerOverLimitReturns429() throws Exception {
        useThresholds(new RateLimitThresholds(20, 5, 1));

        mockMvc.perform(syncRequest()).andExpect(status().isCreated());
        mockMvc.perform(syncRequest())
                .andExpect(status().isTooManyRequests())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(jsonPath("$.message").value(REJECT_MESSAGE))
                .andExpect(jsonPath("$.time").exists());
        assertThat(interceptor.getRejections()).isEqualTo(1);
    }

    @Test
    void unannotatedEndpointIsNeverRateLimited() throws Exception {
        useThresholds(new RateLimitThresholds(1, 1, 1));

        // GET /api/documents 未标注 @RateLimited：远超任何阈值也照常 200
        for (int i = 0; i < 25; i++) {
            mockMvc.perform(get("/api/documents")).andExpect(status().isOk());
        }
        assertThat(interceptor.getRejections()).isZero();
    }

    @Test
    void differentScopesDoNotShareQuota() throws Exception {
        useThresholds(new RateLimitThresholds(1, 1, 1));

        mockMvc.perform(syncRequest()).andExpect(status().isCreated());
        // 同步名额已用尽，但 PDF 是独立作用域，首次仍然放行
        mockMvc.perform(pdfRequest()).andExpect(status().isOk());
        mockMvc.perform(syncRequest()).andExpect(status().isTooManyRequests());
    }

    @Test
    void unauthenticatedRequestSkipsRateLimiting() throws Exception {
        RateLimiter limiter = mock(RateLimiter.class);
        mockMvc = buildMockMvc(limiter, new RateLimitThresholds(20, 5, 1));
        SecurityContextHolder.clearContext();

        MvcResult result = mockMvc.perform(syncRequest()).andReturn();

        // 限流不做鉴权：没有 principal 就不下判定，绝不 429（生产由 Security 过滤器链先返回 401）
        assertThat(result.getResponse().getStatus()).isNotEqualTo(429);
        verifyNoInteractions(limiter);
    }

    private void useThresholds(RateLimitThresholds thresholds) {
        mockMvc = buildMockMvc(new InMemorySlidingWindowRateLimiter(), thresholds);
    }

    private MockMvc buildMockMvc(RateLimiter limiter, RateLimitThresholds thresholds) {
        interceptor = new RateLimitInterceptor(limiter, thresholds);
        return MockMvcBuilders.standaloneSetup(
                        new KnowledgeAssistantController(assistantService, assistantStreamer, idCodec),
                        new PdfImportController(pdfImportService, idCodec),
                        new SyncJobController(syncJobService, syncJobResponseMapper,
                                progressBroadcaster, ownershipGuard, idCodec),
                        new DocumentController(documentService, ownershipGuard, idCodec))
                .setCustomArgumentResolvers(new SecurityContextAuthUserResolver())
                .addInterceptors(interceptor)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private RequestBuilder aiStreamRequest() {
        return post("/api/ai-assistant/sessions/{sessionId}/stream", idCodec.encode(55L))
                .accept(MediaType.TEXT_EVENT_STREAM)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"什么是进程\"}");
    }

    private RequestBuilder pdfRequest() {
        return multipart("/api/imports/pdf").file(new MockMultipartFile(
                "file", "讲义.pdf", "application/pdf", new byte[]{1, 2, 3}));
    }

    private RequestBuilder syncRequest() {
        return post("/api/documents/{documentId}/sync-jobs", idCodec.encode(10L))
                .accept(MediaType.APPLICATION_JSON)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedContentVersion\":1,\"mode\":\"INCREMENTAL\"}");
    }

    private static void authenticate(long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(new AuthUser(userId, "alice"), null, List.of()));
    }

    /**
     * 与生产同源：{@code @AuthenticationPrincipal AuthUser} 取的就是 SecurityContext 里的
     * {@link AuthUser} principal（{@code JwtAuthenticationFilter} 写入），拦截器读的也是同一处。
     * standalone MockMvc 不挂 Security 过滤器链，所以这里显式补一个解析器。
     */
    private static final class SecurityContextAuthUserResolver implements HandlerMethodArgumentResolver {

        @Override
        public boolean supportsParameter(MethodParameter parameter) {
            return AuthUser.class.equals(parameter.getParameterType());
        }

        @Override
        public Object resolveArgument(MethodParameter parameter,
                                      ModelAndViewContainer mavContainer,
                                      NativeWebRequest webRequest,
                                      WebDataBinderFactory binderFactory) {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication != null && authentication.getPrincipal() instanceof AuthUser authUser) {
                return authUser;
            }
            return null;
        }
    }
}