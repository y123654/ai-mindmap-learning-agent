package com.example.aimindmap.agentcontext;

import com.example.aimindmap.ai.AiProperties;
import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.EncodingType;
import org.springframework.stereotype.Component;

/**
 * 综合升级方案 阶段二 A：基于 jtokkit 的 token 保守估算器。
 *
 * <p><b>重要口径说明：</b>cl100k_base 是 OpenAI 的 BPE 分词器，与 DeepSeek 自身分词器
 * 并不相同，因此这里的计数是<b>保守估算</b>（再乘安全系数），<b>不是服务商精确计费值</b>；
 * 仅用于上下文裁剪与动态输出预算，真实用量以平台流式末帧 usage 为准。</p>
 *
 * <p>encoding 在组件构造（应用启动）时初始化一次，避免把分词器加载开销落到每次问答。</p>
 */
@Component
public class TokenEstimator {

    /** ChatML 每条消息的固定开销（role 与分隔标记），与官方 token 计算口径一致。 */
    static final int MESSAGE_OVERHEAD_TOKENS = 4;

    private final Encoding encoding;
    private final double multiplier;

    public TokenEstimator(AiProperties aiProperties) {
        EncodingRegistry registry = Encodings.newDefaultEncodingRegistry();
        this.encoding = registry.getEncoding(EncodingType.CL100K_BASE);
        this.multiplier = aiProperties.getTokenEstimateMultiplier();
    }

    /**
     * 系统提示词（含长期记忆块 / 会话摘要块）只按文本估算，不加每条消息开销。
     *
     * @return ceil(cl100k 原始 token 数 × 安全系数)；空文本返回 0
     */
    public int estimateText(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return (int) Math.ceil(encoding.countTokens(text) * multiplier);
    }

    /**
     * 对话消息估算 = ceil(countTokens(text) × 系数) + 4（ChatML 开销）。
     * 只对消息正文分词，role 开销由固定的 +4 覆盖。
     */
    public int estimateMessage(String role, String content) {
        return estimateText(content == null ? "" : content) + MESSAGE_OVERHEAD_TOKENS;
    }
}
