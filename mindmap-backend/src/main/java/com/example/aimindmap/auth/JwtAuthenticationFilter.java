package com.example.aimindmap.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** 从 Authorization 头（或 SSE 的 access_token 查询参数）解析 JWT 并写入安全上下文。 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;

    public JwtAuthenticationFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    /**
     * SSE / StreamingResponseBody 完成时，Tomcat 会做一次 ASYNC 内部转发。
     * 这次转发仍会经过 Spring Security 授权检查，所以过滤器要重新解析 Token，
     * 否则 AI 流式回答结束时可能抛 AccessDeniedException。
     */
    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String token = resolveToken(request);
        if (token != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            AuthUser authUser = jwtService.parse(token);
            if (authUser != null) {
                UsernamePasswordAuthenticationToken authentication =
                        new UsernamePasswordAuthenticationToken(authUser, null, java.util.List.of());
                SecurityContextHolder.getContext().setAuthentication(authentication);
            }
        }
        filterChain.doFilter(request, response);
    }

    private String resolveToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            return header.substring(7).trim();
        }
        // EventSource 无法自定义请求头；只允许 SSE 端点用查询参数传令牌，避免令牌出现在普通接口日志中。
        if ("GET".equals(request.getMethod())
                && request.getServletPath().matches("^/api/sync-jobs/[A-Za-z0-9]+/events$")) {
            String queryToken = request.getParameter("access_token");
            return queryToken == null || queryToken.isBlank() ? null : queryToken.trim();
        }
        return null;
    }
}
