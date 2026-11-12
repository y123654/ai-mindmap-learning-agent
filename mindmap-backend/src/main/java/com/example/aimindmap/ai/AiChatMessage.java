package com.example.aimindmap.ai;

/**
 * 普通多轮聊天消息。系统提示词由后端服务构造，业务请求不允许直接传 system。
 */
public record AiChatMessage(String role, String content) {
}
