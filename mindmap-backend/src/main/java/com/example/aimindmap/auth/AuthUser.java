package com.example.aimindmap.auth;

/** 登录成功后放入 Spring Security 上下文的当前用户标识。 */
public record AuthUser(Long userId, String username) {
}