package com.example.aimindmap.agentruntime;

/**
 * 单次问答的意图标签（方案 §5 P1-1）：决定后续走哪条编排路径，P1-A 只负责“判”，不负责“路由”。
 *
 * <ul>
 *   <li>{@link #CHITCHAT} 闲聊（问候、自我介绍、致谢、告别）：不查资料；</li>
 *   <li>{@link #DIRECT} 直接回答（短句、无学习关键词）：不值得检索；</li>
 *   <li>{@link #RETRIEVE} 知识类检索：单轮工具检索，宁查勿编的兜底；</li>
 *   <li>{@link #MULTI_HOP} 多跳（对比/区别/为什么/步骤/多步指令）：P1-C 起用 Plan-and-Execute；</li>
 *   <li>{@link #TASK} 任务类（出题、生成导图、导出等会产生产物的动作请求）。</li>
 * </ul>
 *
 * <p>枚举名会原样写进 {@code agent_runs.intent}（P1-B 起），因此<strong>不要改名</strong>：
 * 离线分析“路由准确率”依赖这些字符串。
 */
public enum AgentIntent {

    CHITCHAT,
    DIRECT,
    RETRIEVE,
    MULTI_HOP,
    TASK
}
