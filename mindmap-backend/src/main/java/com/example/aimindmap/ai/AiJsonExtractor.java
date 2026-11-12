package com.example.aimindmap.ai;

/**
 * v1.1 §12.2 第 3-5 步：AI 输出 JSON 提取容错。
 * 1) 去首尾空白；2) 去掉模型偶尔返回的 ```json ... ``` Markdown 代码块；3) 截取最外层花括号。
 * 纯函数，不依赖 HTTP，便于单测。
 */
public final class AiJsonExtractor {

    private AiJsonExtractor() {
    }

    public static String extract(String raw) {
        if (raw == null) {
            throw new AiGatewayException("AI_JSON_EMPTY", "AI 返回为空");
        }
        String text = raw.trim();
        if (text.isEmpty()) {
            throw new AiGatewayException("AI_JSON_EMPTY", "AI 返回为空白");
        }

        // 去掉 Markdown 代码块围栏：```json ... ``` 或 ``` ... ```
        if (text.startsWith("```")) {
            int firstNewline = text.indexOf('\n');
            if (firstNewline > 0) {
                text = text.substring(firstNewline + 1);
            }
            int fenceEnd = text.lastIndexOf("```");
            if (fenceEnd >= 0) {
                text = text.substring(0, fenceEnd);
            }
            text = text.trim();
        }

        // 模型可能在 JSON 前后夹带解释文字，截取第一个 { 到最后一个 }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new AiGatewayException("AI_JSON_PARSE_FAILED",
                    "AI 返回中找不到 JSON 对象");
        }
        return text.substring(start, end + 1);
    }
}