package com.example.aimindmap.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.aimindmap.auth.AuthUser;
import com.example.aimindmap.common.error.RateLimitExceededException;
import jakarta.servlet.DispatcherType;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.method.HandlerMethod;

/**
 * 阶段七-3：限流拦截器单测（纯 Mockito + Mock Servlet，不起 Spring 上下文）。
 *
 * <p>覆盖：非 HandlerMethod 放行、无 {@code @RateLimited} 注解放行、无登录态/匿名 principal 跳过
 * （交给既有 401 链路，不许 NPE）、ASYNC 转发不重复计数、放行与拒绝路径、
 * 阈值按作用域从配置取、拒绝计数累加。
 */
class RateLimitInterceptorTest {

    private static final long USER_ID = 8L;

    private RateLimiter rateLimiter;
    private RateLimitInterceptor interceptor;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        rateLimiter = mock(RateLimiter.class);
        interceptor = new RateLimitInterceptor(rateLimiter, new RateLimitThresholds(20, 5, 10));
        request = new MockHttpServletRequest("POST", "/api/imports/pdf");
        response = new MockHttpServletResponse();
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void allowsWhenLimiterGrants() throws Exception {
        authenticate(USER_ID);
        when(rateLimiter.tryAcquire(RateLimitScope.AI_CHAT, USER_ID, 20)).thenReturn(true);

        assertThat(interceptor.preHandle(request, response, handler("aiChat"))).isTrue();
    }

    @Test
    void passesConfiguredThresholdForEachScope() throws Exception {
        authenticate(USER_ID);
        when(rateLimiter.tryAcquire(org.mockito.ArgumentMatchers.any(), anyLong(), anyInt()))
                .thenReturn(true);

        interceptor.preHandle(request, response, handler("aiChat"));
        interceptor.preHandle(request, response, handler("pdfUpload"));
        interceptor.preHandle(request, response, handler("syncTrigger"));

        verify(rateLimiter).tryAcquire(RateLimitScope.AI_CHAT, USER_ID, 20);
        verify(rateLimiter).tryAcquire(RateLimitScope.PDF_UPLOAD, USER_ID, 5);
        verify(rateLimiter).tryAcquire(RateLimitScope.SYNC_TRIGGER, USER_ID, 10);
    }

    @Test
    void throws429ExceptionWhenLimiterDenies() throws Exception {
        authenticate(USER_ID);
        when(rateLimiter.tryAcquire(RateLimitScope.PDF_UPLOAD, USER_ID, 5)).thenReturn(false);

        assertThatThrownBy(() -> interceptor.preHandle(request, response, handler("pdfUpload")))
                .isInstanceOf(RateLimitExceededException.class)
                .hasMessage("请求过于频繁，请稍后再试")
                .satisfies(ex -> assertThat(((RateLimitExceededException) ex).getScope())
                        .isEqualTo("pdf-upload"));
    }

    @Test
    void rejectionCounterAccumulates() throws Exception {
        authenticate(USER_ID);
        when(rateLimiter.tryAcquire(org.mockito.ArgumentMatchers.any(), anyLong(), anyInt()))
                .thenReturn(false);

        assertThat(interceptor.getRejections()).isZero();
        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> interceptor.preHandle(request, response, handler("syncTrigger")))
                    .isInstanceOf(RateLimitExceededException.class);
        }
        assertThat(interceptor.getRejections()).isEqualTo(3);
    }

    @Test
    void skipsWhenNoAuthenticationSoExisting401ChainHandlesIt() throws Exception {
        assertThat(interceptor.preHandle(request, response, handler("aiChat"))).isTrue();

        verifyNoInteractions(rateLimiter);
        assertThat(interceptor.getRejections()).isZero();
    }

    @Test
    void skipsWhenPrincipalIsAnonymousString() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("anonymousUser", null, List.of()));

        assertThat(interceptor.preHandle(request, response, handler("aiChat"))).isTrue();

        verifyNoInteractions(rateLimiter);
    }

    @Test
    void skipsWhenAuthenticationCarriesNullPrincipal() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(new AuthUser(null, "ghost"), null, List.of()));

        assertThat(interceptor.preHandle(request, response, handler("aiChat"))).isTrue();

        // userId 为空时不下判定（限流粒度是「作用域 × 用户」，没有用户就没有窗口键）
        verifyNoInteractions(rateLimiter);
    }

    @Test
    void skipsHandlersWithoutRateLimitedAnnotation() throws Exception {
        authenticate(USER_ID);

        assertThat(interceptor.preHandle(request, response, handler("unlimited"))).isTrue();

        verifyNoInteractions(rateLimiter);
    }

    @Test
    void skipsNonHandlerMethodHandlers() throws Exception {
        authenticate(USER_ID);

        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
        assertThat(interceptor.preHandle(request, response, null)).isTrue();

        verifyNoInteractions(rateLimiter);
    }

    @Test
    void asyncDispatchDoesNotConsumeSecondQuotaForOneSseAnswer() throws Exception {
        authenticate(USER_ID);
        request.setDispatcherType(DispatcherType.ASYNC);

        assertThat(interceptor.preHandle(request, response, handler("aiChat"))).isTrue();

        verifyNoInteractions(rateLimiter);
    }

    private void authenticate(long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(new AuthUser(userId, "alice"), null, List.of()));
    }

    private static HandlerMethod handler(String methodName) throws NoSuchMethodException {
        return new HandlerMethod(new SampleController(),
                SampleController.class.getDeclaredMethod(methodName));
    }

    /** 只用于构造 HandlerMethod 的样例控制器（不注册到任何上下文）。 */
    static class SampleController {

        @RateLimited(RateLimitScope.AI_CHAT)
        void aiChat() {
        }

        @RateLimited(RateLimitScope.PDF_UPLOAD)
        void pdfUpload() {
        }

        @RateLimited(RateLimitScope.SYNC_TRIGGER)
        void syncTrigger() {
        }

        void unlimited() {
        }
    }
}