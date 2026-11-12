package com.example.aimindmap.ratelimit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 阶段七-3：把 {@link RateLimitInterceptor} 注册进 MVC 拦截器链（全仓第二个 {@code WebMvcConfigurer}，
 * 另一个是既有的 {@code CorsConfig}）。
 *
 * <p><b>切片测试防护：</b>{@code @WebMvcTest} 会扫描 {@code WebMvcConfigurer} 类型的配置类，
 * 而切片上下文里没有 {@link RateLimitInterceptor}（它由 {@link RateLimitConfiguration} 声明，
 * 该类不是 web 层类型、被切片排除）。因此这里用 {@link ObjectProvider} 延迟解析：
 * Bean 不存在就不注册拦截器，切片照常启动（{@code SecurityWebLayerTest} 零改动仍绿）。
 *
 * <p>拦截器在 Spring Security 过滤器链之后执行（{@code preHandle} 属于 DispatcherServlet 阶段），
 * 所以此时安全上下文已由 {@code JwtAuthenticationFilter} 填好，能直接取到 {@code AuthUser}。
 */
@Configuration(proxyBeanMethods = false)
public class RateLimitWebConfiguration implements WebMvcConfigurer {

    private static final Logger log = LoggerFactory.getLogger(RateLimitWebConfiguration.class);

    private final ObjectProvider<RateLimitInterceptor> interceptorProvider;

    public RateLimitWebConfiguration(ObjectProvider<RateLimitInterceptor> interceptorProvider) {
        this.interceptorProvider = interceptorProvider;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        RateLimitInterceptor interceptor = interceptorProvider.getIfAvailable();
        if (interceptor == null) {
            log.debug("未装配限流拦截器（切片上下文或限流 Bean 缺失），跳过注册");
            return;
        }
        registry.addInterceptor(interceptor);
        log.info("已注册接口限流拦截器（作用于标注 @RateLimited 的三个接口）");
    }
}