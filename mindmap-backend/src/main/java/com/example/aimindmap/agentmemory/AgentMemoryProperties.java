package com.example.aimindmap.agentmemory;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 长期记忆配置（不含密钥，可被环境变量覆盖）。
 * dedup-min-score：显式记忆向量去重的相似度阈值（任务六）。
 */
@ConfigurationProperties(prefix = "app.agent.memory")
public class AgentMemoryProperties {

    private double dedupMinScore = 0.80d;

    public double getDedupMinScore() {
        return dedupMinScore;
    }

    public void setDedupMinScore(double dedupMinScore) {
        this.dedupMinScore = dedupMinScore;
    }
}
