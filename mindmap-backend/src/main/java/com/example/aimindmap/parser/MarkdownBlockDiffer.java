package com.example.aimindmap.parser;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * v1.1 方案 §8.5 差异比较策略：
 * 1. 优先用 anchor 匹配；
 * 2. 再用“标题路径 + 块类型 + 内容哈希”匹配；
 * 3. 新增列表项结合相邻位置（锚点含文本，天然不受插入影响）；
 * 4. 文本小修改使用相似度比较；
 * 5. 无法确定时放入 uncertain，不擅自覆盖。
 *
 * 【v1.1 修订（方案 A·搬家识别，2026-09-12）】
 * 标题锚点不含自身文字（光合作用#heading(2)），标题自身改名能在第 1 步直接配对；
 * 但它的所有下级块锚点前缀含旧标题文字（光合作用 > 原料#bullet(二氧化碳)），
 * 父标题一改名字就全部失配，旧实现会把它们误判为“新增”造成节点重复。
 * 修订做法：
 * - 先识别改名标题（第 1 步已配对的 + 剩余标题按浅层到深层、同父路径相似度配对），
 *   记录“旧标题全路径 -> 新标题全路径”的迁移关系；
 * - 把剩余旧块的 headingPath 和锚点前缀按迁移关系平移，再做第二轮锚点匹配：
 *   文字相同的归入 relocated（静默迁移），文字也变了归入 changed；
 * - 相似度配对中文字完全相同的跨标题匹配同样归入 relocated
 *   （覆盖嵌套列表父项改名的场景）。
 */
@Component
public class MarkdownBlockDiffer {

    /** 大于等于该相似度判定为修改。 */
    private static final double CHANGED_THRESHOLD = 0.55;
    /** 大于等于该相似度但低于修改阈值，判定为不确定。 */
    private static final double UNCERTAIN_THRESHOLD = 0.30;

    public MarkdownDiff diff(List<MarkdownBlock> oldBlocks, List<MarkdownBlock> newBlocks) {
        List<MarkdownBlock> added = new ArrayList<>();
        List<MarkdownDiff.ChangedBlock> changed = new ArrayList<>();
        List<MarkdownBlock> removed = new ArrayList<>();
        List<MarkdownBlock> unchanged = new ArrayList<>();
        List<MarkdownDiff.ChangedBlock> uncertain = new ArrayList<>();
        List<MarkdownDiff.ChangedBlock> relocated = new ArrayList<>();

        Set<String> matchedOldIds = new HashSet<>();
        Set<String> matchedNewIds = new HashSet<>();

        // 第 1 步：anchor 匹配
        for (MarkdownBlock oldBlock : oldBlocks) {
            MarkdownBlock sameAnchor = findByAnchor(newBlocks, oldBlock.anchor());
            if (sameAnchor == null || matchedNewIds.contains(sameAnchor.blockId())) {
                continue;
            }
            if (oldBlock.contentHash().equals(sameAnchor.contentHash())) {
                unchanged.add(oldBlock);
            } else {
                // 同位置但内容变了：若新集合别处存在完全相同内容，说明只是顺序挪动，交给第 2 步配对
                if (existsSameHashElsewhere(newBlocks, oldBlock, sameAnchor.blockId(), matchedNewIds)) {
                    continue;
                }
                changed.add(new MarkdownDiff.ChangedBlock(oldBlock, sameAnchor));
            }
            matchedOldIds.add(oldBlock.blockId());
            matchedNewIds.add(sameAnchor.blockId());
        }

        List<MarkdownBlock> remainingOld = new ArrayList<>();
        for (MarkdownBlock oldBlock : oldBlocks) {
            if (!matchedOldIds.contains(oldBlock.blockId())) {
                remainingOld.add(oldBlock);
            }
        }
        List<MarkdownBlock> remainingNew = new ArrayList<>();
        for (MarkdownBlock newBlock : newBlocks) {
            if (!matchedNewIds.contains(newBlock.blockId())) {
                remainingNew.add(newBlock);
            }
        }

        // 【方案 A】收集标题改名迁移关系：旧标题全路径 -> 新标题全路径
        List<PathMigration> migrations = new ArrayList<>();

        // (a) 第 1 步已配对的 changed 标题：锚点相同但哈希变化，必是标题自身文字改变
        for (MarkdownDiff.ChangedBlock pair : new ArrayList<>(changed)) {
            if (pair.oldBlock().type() == BlockType.HEADING) {
                addMigration(migrations, pair.oldBlock(), pair.newBlock());
            }
        }

        // (b) 剩余标题按 1->6 级、同父路径（旧路径应用已有迁移）、同级别做相似度贪心配对，
        //     解决“上级标题改名导致下级标题锚点整体变化”的连锁失配
        for (int level = 1; level <= 6; level++) {
            List<HeadingCandidate> candidates = new ArrayList<>();
            for (MarkdownBlock oldBlock : remainingOld) {
                if (matchedOldIds.contains(oldBlock.blockId())
                        || oldBlock.type() != BlockType.HEADING
                        || !levelEquals(oldBlock.level(), level)) {
                    continue;
                }
                List<String> effectiveOldPath = applyMigrations(oldBlock.headingPath(), migrations);
                for (MarkdownBlock newBlock : remainingNew) {
                    if (matchedNewIds.contains(newBlock.blockId())
                            || newBlock.type() != BlockType.HEADING
                            || !levelEquals(newBlock.level(), level)) {
                        continue;
                    }
                    if (!effectiveOldPath.equals(newBlock.headingPath())) {
                        continue;
                    }
                    double score = similarity(oldBlock.normalizedText(), newBlock.normalizedText());
                    if (score >= CHANGED_THRESHOLD) {
                        candidates.add(new HeadingCandidate(oldBlock, newBlock, score));
                    }
                }
            }
            candidates.sort(Comparator.comparingDouble((HeadingCandidate c) -> c.score).reversed());
            for (HeadingCandidate candidate : candidates) {
                if (matchedOldIds.contains(candidate.oldBlock.blockId())
                        || matchedNewIds.contains(candidate.newBlock.blockId())) {
                    continue;
                }
                matchedOldIds.add(candidate.oldBlock.blockId());
                matchedNewIds.add(candidate.newBlock.blockId());
                addMigration(migrations, candidate.oldBlock, candidate.newBlock);
                if (candidate.oldBlock.normalizedText().equals(candidate.newBlock.normalizedText())) {
                    // 自身文字没变，只是跟着上级标题搬家
                    relocated.add(new MarkdownDiff.ChangedBlock(candidate.oldBlock, candidate.newBlock));
                } else {
                    changed.add(new MarkdownDiff.ChangedBlock(candidate.oldBlock, candidate.newBlock));
                }
            }
        }

        // 【方案 A】把剩余旧块按迁移关系平移 headingPath 与锚点（blockId/文字/哈希保持不变，
        // 迁移副本仅用于后续匹配，配对记录仍引用原始旧块
        java.util.Map<String, MarkdownBlock> migratedByOldId = new java.util.HashMap<>();
        List<MarkdownBlock> comparableOld = new ArrayList<>();
        for (MarkdownBlock oldBlock : remainingOld) {
            if (matchedOldIds.contains(oldBlock.blockId())) {
                continue;
            }
            MarkdownBlock migrated = migrateBlock(oldBlock, migrations);
            if (migrated != oldBlock) {
                migratedByOldId.put(oldBlock.blockId(), migrated);
            }
            comparableOld.add(migrated);
        }

        // 【方案 A】第二轮锚点匹配：平移后的旧锚点与新锚点相等
        for (MarkdownBlock oldBlock : new ArrayList<>(comparableOld)) {
            if (matchedOldIds.contains(oldBlock.blockId())) {
                continue;
            }
            MarkdownBlock sameAnchor = findByAnchor(remainingNew, oldBlock.anchor());
            if (sameAnchor == null || matchedNewIds.contains(sameAnchor.blockId())) {
                continue;
            }
            matchedOldIds.add(oldBlock.blockId());
            matchedNewIds.add(sameAnchor.blockId());
            MarkdownBlock original = oldBlock;
            if (oldBlock.normalizedText().equals(sameAnchor.normalizedText())) {
                relocated.add(new MarkdownDiff.ChangedBlock(original, sameAnchor));
            } else {
                changed.add(new MarkdownDiff.ChangedBlock(original, sameAnchor));
            }
        }

        // 第 2/4 步：同标题路径、同类型、同层级，按相似度贪心配对
        List<MarkdownDiff.ChangedBlock> candidates = new ArrayList<>();
        for (MarkdownBlock oldBlock : comparableOld) {
            if (matchedOldIds.contains(oldBlock.blockId())) {
                continue;
            }
            for (MarkdownBlock newBlock : remainingNew) {
                if (matchedNewIds.contains(newBlock.blockId()) || !isComparable(oldBlock, newBlock)) {
                    continue;
                }
                double score = similarity(oldBlock.normalizedText(), newBlock.normalizedText());
                if (score >= UNCERTAIN_THRESHOLD) {
                    candidates.add(new MarkdownDiff.ChangedBlock(oldBlock, newBlock));
                }
            }
        }
        candidates.sort((a, b) -> Double.compare(
                similarity(b.oldBlock().normalizedText(), b.newBlock().normalizedText()),
                similarity(a.oldBlock().normalizedText(), a.newBlock().normalizedText())));

        for (MarkdownDiff.ChangedBlock candidate : candidates) {
            if (matchedOldIds.contains(candidate.oldBlock().blockId())
                    || matchedNewIds.contains(candidate.newBlock().blockId())) {
                continue;
            }
            double score = similarity(candidate.oldBlock().normalizedText(),
                    candidate.newBlock().normalizedText());
            matchedOldIds.add(candidate.oldBlock().blockId());
            matchedNewIds.add(candidate.newBlock().blockId());
            if (score >= CHANGED_THRESHOLD) {
                if (candidate.oldBlock().normalizedText().equals(candidate.newBlock().normalizedText())) {
                    // 文字完全相同（如嵌套列表父项改名后，子项按相似度被找回）：静默迁移
                    relocated.add(candidate);
                } else {
                    changed.add(candidate);
                }
            } else {
                uncertain.add(candidate);
            }
        }

        // 第 3/5 步：未配对的新块为新增，未配对的旧块为删除
        for (MarkdownBlock newBlock : newBlocks) {
            if (!matchedNewIds.contains(newBlock.blockId())) {
                added.add(newBlock);
            }
        }
        for (MarkdownBlock oldBlock : oldBlocks) {
            if (!matchedOldIds.contains(oldBlock.blockId())) {
                removed.add(oldBlock);
            }
        }

        return new MarkdownDiff(added, changed, removed, unchanged, uncertain, relocated);
    }

    private static boolean levelEquals(Integer level, int expected) {
        return level != null && level == expected;
    }

    /** 记录一条标题路径迁移：旧全路径（含被改名标题自身）-> 新全路径。 */
    private static void addMigration(List<PathMigration> migrations,
                                     MarkdownBlock oldHeading,
                                     MarkdownBlock newHeading) {
        List<String> oldPrefix = new ArrayList<>(oldHeading.headingPath());
        oldPrefix.add(oldHeading.normalizedText());
        List<String> newPrefix = new ArrayList<>(newHeading.headingPath());
        newPrefix.add(newHeading.normalizedText());
        if (!oldPrefix.equals(newPrefix)) {
            migrations.add(new PathMigration(List.copyOf(oldPrefix), List.copyOf(newPrefix)));
        }
    }

    /**
     * 按“最长旧前缀命中一次”应用迁移。用最长匹配保证多级标题同时改名时可以级联：
     * 更深的迁移记录的新前缀已经包含上级新标题名。
     */
    static List<String> applyMigrations(List<String> path, List<PathMigration> migrations) {
        PathMigration best = null;
        for (PathMigration migration : migrations) {
            if (startsWith(path, migration.oldPrefix())
                    && (best == null || migration.oldPrefix().size() > best.oldPrefix().size())) {
                best = migration;
            }
        }
        if (best == null) {
            return path;
        }
        List<String> result = new ArrayList<>(best.newPrefix());
        result.addAll(path.subList(best.oldPrefix().size(), path.size()));
        return result;
    }

    /**
     * 平移旧块的 headingPath 和锚点路径前缀；无迁移命中时返回原对象。
     * blockId 是旧锚点的哈希，保持不变（配对记录用它找已发布图里的节点）。
     */
    private static MarkdownBlock migrateBlock(MarkdownBlock oldBlock, List<PathMigration> migrations) {
        List<String> newPath = applyMigrations(oldBlock.headingPath(), migrations);
        if (newPath.equals(oldBlock.headingPath())) {
            return oldBlock;
        }
        String anchor = oldBlock.anchor();
        int hashIndex = anchor.indexOf('#');
        String suffix = hashIndex >= 0 ? anchor.substring(hashIndex) : "";
        String newAnchor = String.join(" > ", newPath) + suffix;
        String newHash = MarkdownNormalizer.sha256(oldBlock.normalizedText(), oldBlock.type(), newPath);
        return new MarkdownBlock(
                oldBlock.blockId(),
                oldBlock.type(),
                oldBlock.text(),
                oldBlock.normalizedText(),
                List.copyOf(newPath),
                oldBlock.level(),
                oldBlock.order(),
                newAnchor,
                newHash);
    }

    private static boolean startsWith(List<String> path, List<String> prefix) {
        if (prefix.size() > path.size()) {
            return false;
        }
        for (int i = 0; i < prefix.size(); i++) {
            if (!path.get(i).equals(prefix.get(i))) {
                return false;
            }
        }
        return true;
    }

    private static MarkdownBlock findByAnchor(List<MarkdownBlock> blocks, String anchor) {
        for (MarkdownBlock block : blocks) {
            if (block.anchor().equals(anchor)) {
                return block;
            }
        }
        return null;
    }

    private static boolean existsSameHashElsewhere(List<MarkdownBlock> newBlocks,
                                                   MarkdownBlock oldBlock,
                                                   String currentAnchorBlockId,
                                                   Set<String> matchedNewIds) {
        for (MarkdownBlock block : newBlocks) {
            if (block.blockId().equals(currentAnchorBlockId) || matchedNewIds.contains(block.blockId())) {
                continue;
            }
            if (block.contentHash().equals(oldBlock.contentHash())
                    && block.type() == oldBlock.type()) {
                return true;
            }
        }
        return false;
    }

    private static boolean isComparable(MarkdownBlock oldBlock, MarkdownBlock newBlock) {
        if (oldBlock.type() != newBlock.type()) {
            return false;
        }
        if (!oldBlock.headingPath().equals(newBlock.headingPath())) {
            return false;
        }
        // 从历史快照重建的块没有 level 列（§10.3 表结构不含 level），null 不阻挡配对
        boolean levelsComparable = oldBlock.level() == null
                || newBlock.level() == null
                || oldBlock.level().equals(newBlock.level());
        return switch (oldBlock.type()) {
            case HEADING, BULLET_LIST_ITEM, ORDERED_LIST_ITEM -> levelsComparable;
            default -> true;
        };
    }

    /**
     * 中文友好的相似度：字符 bigram Jaccard 与单字集合 Jaccard 取较大值。
     */
    static double similarity(String a, String b) {
        if (a.equals(b)) {
            return 1.0;
        }
        if (a.isEmpty() || b.isEmpty()) {
            return 0.0;
        }
        double bigramScore = jaccard(bigrams(a), bigrams(b));
        double unigramScore = jaccard(charSet(a), charSet(b));
        return Math.max(bigramScore, unigramScore);
    }

    private static Set<String> bigrams(String text) {
        Set<String> result = new HashSet<>();
        for (int i = 0; i < text.length() - 1; i++) {
            result.add(text.substring(i, i + 2));
        }
        return result;
    }

    private static Set<Character> charSet(String text) {
        Set<Character> result = new HashSet<>();
        for (char c : text.toCharArray()) {
            if (!Character.isWhitespace(c)) {
                result.add(c);
            }
        }
        return result;
    }

    private static double jaccard(Set<?> a, Set<?> b) {
        if (a.isEmpty() && b.isEmpty()) {
            return 1.0;
        }
        int intersection = 0;
        for (Object item : a) {
            if (b.contains(item)) {
                intersection++;
            }
        }
        int union = a.size() + b.size() - intersection;
        return union == 0 ? 0.0 : (double) intersection / union;
    }

    private record PathMigration(List<String> oldPrefix, List<String> newPrefix) {
    }

    private record HeadingCandidate(MarkdownBlock oldBlock, MarkdownBlock newBlock, double score) {
    }
}