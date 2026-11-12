package com.example.aimindmap.agentmemory;

import org.springframework.stereotype.Component;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 显式记忆指令识别器（任务一；方案 §6.4 第一阶段）。
 * 仅当消息开头是"记住"指令时命中：
 * 开头允许空白；"记住"前允许 请/帮我/你/麻烦 任意组合；
 * "记住"后允许 ，,：:、或空白作分隔（也允许无分隔）。
 */
@Component
public final class ExplicitMemoryRecognizer {

    private static final Pattern COMMAND_PATTERN = Pattern.compile(
            "^\\s*(?:请|帮我|你|麻烦)*记住[\\s，,：:、]*(.*)$",
            Pattern.DOTALL);

    public ExplicitMemory recognize(String content) {
        if (content == null) {
            return null;
        }
        Matcher matcher = COMMAND_PATTERN.matcher(content);
        if (!matcher.matches()) {
            return null;
        }
        String body = matcher.group(1).trim();
        if (body.isEmpty()) {
            return null;
        }
        return new ExplicitMemory(MemoryTypeRules.inferType(body), body);
    }

    /** 识别结果：记忆类型与正文（已 trim、非空）。 */
    public record ExplicitMemory(String memoryType, String content) {
    }
}
