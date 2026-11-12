package com.example.aimindmap.vectorindex;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Markdown 切分器（方案 §7.4 document_chunk）。
 * 按标题层级切分并保留标题路径；单片不超过 800 字符，成片目标 500~800 字符，
 * 同节相邻片保留 80~120 字符重叠；不同标题节之间不携带重叠。
 */
public class MarkdownChunker {

    public record Chunk(int chunkIndex, List<String> sourcePath, String text) {
    }

    public static final int MAX_CHARS = 800;
    public static final int TARGET_MIN_CHARS = 500;
    public static final int OVERLAP_MIN = 80;
    public static final int OVERLAP_MAX = 120;
    public static final int OVERLAP_TARGET = 100;

    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.*)$");

    public List<Chunk> chunk(String markdown) {
        List<Chunk> result = new ArrayList<>();
        if (markdown == null || markdown.isBlank()) {
            return result;
        }

        List<Section> sections = splitSections(markdown);
        int chunkIndex = 0;
        for (Section section : sections) {
            String headingText = renderHeadings(section.headingPath);
            int headingLength = headingText.isEmpty() ? 0 : headingText.length() + 1;
            Deque<String> pieces = new ArrayDeque<>(splitToPieces(section.bodyLines, headingLength));
            if (pieces.isEmpty()) {
                continue;
            }

            StringBuilder buffer = new StringBuilder();
            while (!pieces.isEmpty()) {
                String piece = pieces.peekFirst();
                int separator = buffer.length() == 0 ? 0 : 1;
                if (headingLength + separator + buffer.length() + piece.length() <= MAX_CHARS) {
                    if (separator == 1) {
                        buffer.append('\n');
                    }
                    buffer.append(piece);
                    pieces.pollFirst();
                    continue;
                }

                int space = MAX_CHARS - headingLength - separator - buffer.length();
                if (space > 0) {
                    buffer.append(piece, 0, space);
                    String remainder = piece.substring(space);
                    pieces.pollFirst();
                    pieces.addFirst(remainder);
                }

                String body = buffer.toString();
                String text = headingText.isEmpty() ? body : headingText + "\n" + body;
                result.add(new Chunk(chunkIndex++, List.copyOf(section.headingPath), text));

                String carry = overlapTail(body);
                buffer = new StringBuilder(carry);
            }

            if (buffer.length() > 0) {
                String body = buffer.toString();
                String text = headingText.isEmpty() ? body : headingText + "\n" + body;
                result.add(new Chunk(chunkIndex++, List.copyOf(section.headingPath), text));
            }
        }
        return result;
    }

    private static String overlapTail(String body) {
        if (body.length() <= OVERLAP_MIN) {
            return body;
        }
        int upper = Math.min(OVERLAP_MAX, body.length());
        for (int position = upper; position >= OVERLAP_MIN; position--) {
            if (Character.isWhitespace(body.charAt(body.length() - position))) {
                return body.substring(body.length() - position);
            }
        }
        int cut = Math.min(OVERLAP_TARGET, body.length());
        return body.substring(body.length() - cut);
    }

    private List<String> splitToPieces(List<String> bodyLines, int headingLength) {
        int pieceLimit = MAX_CHARS - headingLength;
        List<String> pieces = new ArrayList<>();
        for (String line : bodyLines) {
            if (line.length() <= pieceLimit) {
                pieces.add(line);
            } else {
                for (int start = 0; start < line.length(); start += pieceLimit) {
                    pieces.add(line.substring(start, Math.min(start + pieceLimit, line.length())));
                }
            }
        }
        return pieces;
    }

    static String renderHeadings(List<String> headingPath) {
        StringBuilder builder = new StringBuilder();
        for (int level = 0; level < headingPath.size(); level++) {
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append("#".repeat(level + 1)).append(' ').append(headingPath.get(level));
        }
        return builder.toString();
    }

    private List<Section> splitSections(String markdown) {
        List<Section> sections = new ArrayList<>();
        Section current = new Section(new ArrayList<>(), new ArrayList<>());
        for (String line : markdown.replace("\r\n", "\n").replace("\r", "\n").split("\n", -1)) {
            Matcher matcher = HEADING.matcher(line);
            if (matcher.matches()) {
                if (!current.bodyLines.isEmpty() || !current.headingPath.isEmpty()) {
                    sections.add(current);
                }
                int level = matcher.group(1).length();
                List<String> path = current.headingPath.isEmpty()
                        ? new ArrayList<>()
                        : new ArrayList<>(current.headingPath.subList(
                                0, Math.min(level - 1, current.headingPath.size())));
                path.add(matcher.group(2).trim());
                current = new Section(path, new ArrayList<>());
            } else {
                current.bodyLines.add(line);
            }
        }
        if (!current.bodyLines.isEmpty() || !current.headingPath.isEmpty()) {
            sections.add(current);
        }
        return sections;
    }

    private record Section(List<String> headingPath, List<String> bodyLines) {
    }
}
