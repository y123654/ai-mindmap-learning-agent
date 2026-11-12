package com.example.aimindmap.ratelimit;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 阶段七-3：标注需要限流的 Controller 方法，由 {@link RateLimitInterceptor} 读取。
 *
 * <p><b>只允许标注方案 318 行列出的三个接口</b>（AI 问答 / PDF 上传 / 触发同步）；
 * 方案 319 行「不做」清单不许扩项，出题、再生成、重建等接口一律不加本注解。
 * 未标注的方法拦截器直接放行，因此新增接口默认不受限流影响。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RateLimited {

    /** 限流作用域，决定 Redis 键名与使用哪个阈值配置。 */
    RateLimitScope value();
}