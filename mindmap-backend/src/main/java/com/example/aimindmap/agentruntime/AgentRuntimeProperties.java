package com.example.aimindmap.agentruntime;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * P1 显式编排配置（方案 §5、执行计划 §“关键决策”）。
 *
 * <p>默认值全部是“关”：<b>{@code app.agent.runtime.enabled=false}、
 * {@code app.agent.runtime.router.llm-enabled=false}</b>。开关关着时，
 * 意图分类器仍然可以被单测直接构造使用，但不会被任何业务流程调用，
 * 也就是“旧路径行为完全不变，可一键回滚”。
 *
 * <p>本类不包含任何密钥。
 */
@ConfigurationProperties(prefix = "app.agent.runtime")
public class AgentRuntimeProperties {

    /** P1 显式编排总开关：关时所有意图都走旧路径（P1-B 起才有作用）。 */
    private boolean enabled = false;
    /** 意图路由相关配置。 */
    private Router router = new Router();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Router getRouter() {
        return router;
    }

    public void setRouter(Router router) {
        this.router = router;
    }

    /**
     * 意图路由配置（{@code app.agent.runtime.router.*}）。
     *
     * <p>{@link #llmEnabled} 关（默认）时分类器<strong>零网络调用</strong>：
     * 规则判完就返回，规则不确定的一律兜底 {@link AgentIntent#RETRIEVE}。
     * 打开后才允许用一次小模型（{@code maxTokens <= 50}）给不确定的消息补判。
     */
    public static class Router {

        /** 是否允许“规则不确定”时调用小模型分类；默认关。 */
        private boolean llmEnabled = false;
        /** 小模型分类单次超时（毫秒）：超时按失败处理，兜底 RETRIEVE。 */
        private int llmTimeoutMs = 2000;
        /** 小模型分类的输出 token 上限（方案 §P1-1 要求 maxTokens <= 50）。 */
        private int llmMaxTokens = 50;

        public boolean isLlmEnabled() {
            return llmEnabled;
        }

        public void setLlmEnabled(boolean llmEnabled) {
            this.llmEnabled = llmEnabled;
        }

        public int getLlmTimeoutMs() {
            return llmTimeoutMs;
        }

        public void setLlmTimeoutMs(int llmTimeoutMs) {
            this.llmTimeoutMs = llmTimeoutMs;
        }

        public int getLlmMaxTokens() {
            return llmMaxTokens;
        }

        public void setLlmMaxTokens(int llmMaxTokens) {
            this.llmMaxTokens = llmMaxTokens;
        }
    }
}
