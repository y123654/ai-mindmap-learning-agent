package com.example.aimindmap.agentmemory;

/**
 * 记忆类型推断规则（任务一，写死、确定性、可测）+ 中文标签与向量化文本。
 * 检查顺序固定：preference → profile → weakness → goal → task，全部不命中为 fact。
 */
public final class MemoryTypeRules {

    public static final String PROFILE = "profile";
    public static final String PREFERENCE = "preference";
    public static final String WEAKNESS = "weakness";
    public static final String GOAL = "goal";
    public static final String TASK = "task";
    public static final String FACT = "fact";

    private MemoryTypeRules() {
    }

    /**
     * 关键词映射表（按检查顺序，先命中先生效）：
     * preference：喜欢、偏好、最好用
     * profile：专业、年级、本科
     * weakness：混淆、薄弱、总错、不熟悉
     * goal：目标、期末、考试、想考、考上
     * task：正在复习、这周要、任务、进度
     * fact：以上均不命中
     */
    public static String inferType(String content) {
        if (containsAny(content, "喜欢", "偏好", "最好用")) {
            return PREFERENCE;
        }
        if (containsAny(content, "专业", "年级", "本科")) {
            return PROFILE;
        }
        if (containsAny(content, "混淆", "薄弱", "总错", "不熟悉")) {
            return WEAKNESS;
        }
        if (containsAny(content, "目标", "期末", "考试", "想考", "考上")) {
            return GOAL;
        }
        if (containsAny(content, "正在复习", "这周要", "任务", "进度")) {
            return TASK;
        }
        return FACT;
    }

    /** 注入上下文与向量化文本使用的中文标签。 */
    public static String label(String memoryType) {
        return switch (memoryType) {
            case PROFILE -> "个人信息";
            case PREFERENCE -> "偏好";
            case WEAKNESS -> "薄弱点";
            case GOAL -> "目标";
            case TASK -> "任务";
            default -> "事实";
        };
    }

    /** Qdrant content 字段的向量化文本（任务四）。 */
    public static String vectorContent(String memoryType, String content) {
        return "记忆类型：" + label(memoryType) + "\n记忆内容：" + content;
    }

    private static boolean containsAny(String content, String... keywords) {
        if (content == null) {
            return false;
        }
        for (String keyword : keywords) {
            if (content.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}
