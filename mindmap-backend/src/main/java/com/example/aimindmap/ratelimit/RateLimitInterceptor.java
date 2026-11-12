package com.example.aimindmap.ratelimit;

import com.example.aimindmap.auth.AuthUser;
import com.example.aimindmap.common.error.RateLimitExceededException;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 阶段七-3：限流拦截器。读 {@link RateLimited} 注解 → 取登录用户 → 调 {@link RateLimiter} 判定 →
 * 超限抛 {@link RateLimitExceededException}，由 {@code GlobalExceptionHandler} 转 429。
 *
 * <p><b>接入方式（最小侵入）：</b>三个目标 Controller 方法各加一个 {@code @RateLimited(...)} 注解，
 * 方法签名、业务逻辑、既有测试全部不动；由 {@link RateLimitWebConfiguration} 注册到 MVC 拦截器链。
 *
 * <p><b>只处理首次 REQUEST 派发：</b>AI 问答 {@code POST /stream} 返回
 * {@code StreamingResponseBody}，流结束时 Tomcat 会做一次 ASYNC 内部转发并再次经过
 * {@code preHandle}（{@code JwtAuthenticationFilter#shouldNotFilterAsyncDispatch()} 为此返回 false）。
 * 若不按 {@link DispatcherType} 过滤，一次问答会吃掉两个名额，与「一次问答一轮 POST」的口径不符。
 *
 * <p><b>匿名请求一律跳过：</b>没有 {@link AuthUser} principal（未登录、匿名令牌、令牌过期）时直接放行，
 * 交给 Spring Security 既有的 401 链路处理——限流不做鉴权、也不许在这里 NPE。
 * 限流粒度是「作用域 × 登录用户」，因此不做 IP 维度限流（方案未要求）。
 *
 * <p><b>拒绝计数：</b>{@link #getRejections()} 是进程内 {@link AtomicLong}，与阶段七-2 缓存命中率
 * 计数器同口径；micrometer/actuator 属阶段八「报备后引入」，本步严禁引入任何指标依赖，
 * 阶段八再把它导出为 Prometheus「限流拒绝数」面板。
 *
 * <p><b>日志红线：</b>只打 scope / userId / 阈值 / 计数，不打 URI、请求体、令牌或密钥。
 */
public class RateLimitInterceptor implements HandlerInterceptor {

    /** 三个作用域统一的中文提示（方案 318 行要求 429 + RATE_LIMITED + 中文提示）。 */
    static final String REJECT_MESSAGE = "请求过于频繁，请稍后再试";

    private static final Logger log = LoggerFactory.getLogger(RateLimitInterceptor.class);

    private final RateLimiter rateLimiter;
    private final RateLimitThresholds thresholds;
    private final AtomicLong rejections = new AtomicLong();

    public RateLimitInterceptor(RateLimiter rateLimiter, RateLimitThresholds thresholds) {
        this.rateLimiter = rateLimiter;
        this.thresholds = thresholds;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (request.getDispatcherType() != DispatcherType.REQUEST) {
            // ASYNC / ERROR / FORWARD 转发不重复计数（SSE 流结束时会有一次 ASYNC 转发）
            return true;
        }
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }
        RateLimited rateLimited = handlerMethod.getMethodAnnotation(RateLimited.class);
        if (rateLimited == null) {
            return true;
        }
        RateLimitScope scope = rateLimited.value();
        Long userId = currentUserId();
        if (userId == null) {
            log.debug("限流跳过：无登录态 principal，交由既有 401 链路处理 scope={}", scope.key());
            return true;
        }
        int limitPerMinute = thresholds.limitOf(scope);
        if (rateLimiter.tryAcquire(scope, userId, limitPerMinute)) {
            return true;
        }
        long total = rejections.incrementAndGet();
        log.warn("接口限流拒绝 scope={} userId={} limitPerMin={} rejections={}",
                scope.key(), userId, limitPerMinute, total);
        throw new RateLimitExceededException(scope.key(), REJECT_MESSAGE);
    }

    /** 从安全上下文取登录用户 ID；与 Controller 的 {@code @AuthenticationPrincipal AuthUser} 同源。 */
    private Long currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return null;
        }
        if (authentication.getPrincipal() instanceof AuthUser authUser) {
            return authUser.userId();
        }
        return null;
    }

    /** 累计拒绝次数（只读）；阶段八作为 Prometheus「限流拒绝数」的数据来源。 */
    public long getRejections() {
        return rejections.get();
    }
}