package com.example.aimindmap.parser;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/**
 * v1.1 方案 §8.4 规范化规则与哈希。
 */
public final class MarkdownNormalizer {

    private MarkdownNormalizer() {
    }

    /**
     * 计算块的规范化文本。
     * 规则：统一换行、去行尾空格、连续空行压缩、去行内 Markdown 标记；列表前导符号由解析器负责剥离。
     * 代码块原文保留（只统一换行和行尾空格）。
     */
    public static String normalize(String rawText, BlockType type) {
        if (rawText == null) {
            return "";
        }
        String text = rawText.replace("\r\n", "\n").replace('\r', '\n');

        if (type == BlockType.CODE_BLOCK) {
            // §8.4 第 7 条：保留代码块原文，代码块第一版不生成知识节点
            return stripTrailingSpaces(text).trim();
        }

        text = stripInlineMarkdown(text);
        List<String> keptLines = new ArrayList<>();
        for (String line : text.split("\n", -1)) {
            String trimmed = line.stripTrailing().trim();
            // §8.4 第 3 条：连续空行压缩；块内多行文本用空格连接成一行纯文本
            if (!trimmed.isEmpty()) {
                keptLines.add(trimmed);
            }
        }
        return String.join(" ", keptLines).trim();
    }

    private static String stripTrailingSpaces(String text) {
        String[] lines = text.split("\n", -1);
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                builder.append('\n');
            }
            builder.append(lines[i].stripTrailing());
        }
        return builder.toString();
    }

    /**
     * §8.4 第 6 条：粗体、斜体、行内代码、链接标记在纯文本中移除（保留文字内容）。
     */
    private static String stripInlineMarkdown(String text) {
        String result = text;
        result = result.replace("**", "");
        result = result.replace("__", "");
        result = result.replace("`", "");
        // [文字](url) -> 文字
        result = result.replaceAll("\\[([^]]*)]\\(([^)]*)\\)", "$1");
        // *xxx* -> xxx
        result = result.replaceAll("\\*([^*\n]+)\\*", "$1");
        // _xxx_ -> xxx（两侧不是字母数字时）
        result = result.replaceAll("(?<![A-Za-z0-9])_([^_\n]+)_(?![A-Za-z0-9])", "$1");
        return result;
    }

    /**
     * §8.4：SHA-256(normalizedText + "|" + type + "|" + headingPath)
     */
    public static String sha256(String normalizedText, BlockType type, List<String> headingPath) {
        String pathPart = headingPath == null ? "" : String.join(" > ", headingPath);
        String input = normalizedText + "|" + type.name() + "|" + pathPart;
        return sha256Hex(input);
    }

    /** 对任意输入计算 SHA-256 十六进制（用于由稳定锚点派生稳定块 ID）。 */
    public static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : bytes) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前 JDK 不支持 SHA-256", e);
        }
    }
}