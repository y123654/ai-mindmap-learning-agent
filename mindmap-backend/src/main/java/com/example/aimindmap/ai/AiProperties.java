package com.example.aimindmap.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * v1.1 §3.3 AI 接口配置，全部来自环境变量 / application.yml，API Key 不落项目文件。
 *
 * <ul>
 *   <li>AI_PROVIDER：服务商标识（默认 deepseek）</li>
 *   <li>AI_BASE_URL：OpenAI 兼容根地址，调用 {baseUrl}/v1/chat/completions</li>
 *   <li>AI_API_KEY：密钥，只保存在后端，日志脱敏</li>
 *   <li>AI_MODEL：模型名（默认 deepseek-chat）</li>
 *   <li>AI_TIMEOUT_SECONDS：读超时秒数（默认 60，§3.3）</li>
 *   <li>AI_MAX_RETRIES：网络/5xx 重试次数（默认 1，§3.3）</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "app.ai")
public class AiProperties {

    private String provider = "deepseek";
    private String baseUrl = "https://api.deepseek.com";
    private String apiKey = "";
    private String model = "deepseek-chat";
    private int timeoutSeconds = 60;
    private int maxRetries = 1;
    /**
     * 单次回复最大 token 数（§16.4 成本控制）。
     * 综合升级方案 阶段二 B：语义改为“输出封顶值”——动态输出预算永远不超过该值。
     */
    private int maxOutputTokens = 2000;
    /** §12.1 归纳任务使用低随机性。 */
    private double temperature = 0.2d;
    /** §12.2 第 1 条：优先开启服务商 JSON 输出模式；部分本地模型不支持时可用环境变量关闭。 */
    private boolean jsonMode = true;
    /**
     * §16.4：单次请求发送给 AI 的上下文最大字符数。
     *
     * @deprecated 综合升级方案 阶段二 B：知识助手上下文裁剪已改用 token 预算
     *     （app.ai.max-input-tokens），本字段在知识助手路径停止读取，下一个大版本删除；
     *     出题与思维导图两条非阶段二路径仍暂时读取各自的字符上限。
     */
    @Deprecated
    private int maxPromptChars = 12000;

    /** 综合升级方案 阶段二 B：对话模型上下文窗口大小（token）。 */
    private int contextWindowTokens = 65536;
    /** 综合升级方案 阶段二 B：输入预算上限（常规裁剪线，token），默认 24000。 */
    private int maxInputTokens = 24000;
    /** 综合升级方案 阶段二 B：为输出预留的安全余量（token）。 */
    private int outputReserveTokens = 2048;
    /** 综合升级方案 阶段二 B：动态输出低于该值（token）即判定上下文过长。 */
    private int minOutputTokens = 512;
    /** 综合升级方案 阶段二 A：cl100k 估算的安全系数（保守放大）。 */
    private double tokenEstimateMultiplier = 1.10d;

    public boolean enabled() {
        return apiKey != null && !apiKey.isBlank()
                && baseUrl != null && !baseUrl.isBlank();
    }

    /** §16.1：日志只显示密钥前 4 位。 */
    public String maskedApiKey() {
        if (apiKey == null || apiKey.isBlank()) {
            return "(未配置)";
        }
        String head = apiKey.length() <= 4 ? "****" : apiKey.substring(0, 4);
        return head + "****（已隐藏）";
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public void setMaxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
    }

    public int getMaxOutputTokens() {
        return maxOutputTokens;
    }

    public void setMaxOutputTokens(int maxOutputTokens) {
        this.maxOutputTokens = maxOutputTokens;
    }

    public double getTemperature() {
        return temperature;
    }

    public void setTemperature(double temperature) {
        this.temperature = temperature;
    }

    public boolean isJsonMode() {
        return jsonMode;
    }

    public void setJsonMode(boolean jsonMode) {
        this.jsonMode = jsonMode;
    }

    /** @deprecated 知识助手路径已停用，改用 token 预算；仅出题/思维导图路径暂时沿用。 */
    @Deprecated
    public int getMaxPromptChars() {
        return maxPromptChars;
    }

    /** @deprecated 知识助手路径已停用，改用 token 预算；仅出题/思维导图路径暂时沿用。 */
    @Deprecated
    public void setMaxPromptChars(int maxPromptChars) {
        this.maxPromptChars = maxPromptChars;
    }

    public int getContextWindowTokens() {
        return contextWindowTokens;
    }

    public void setContextWindowTokens(int contextWindowTokens) {
        this.contextWindowTokens = contextWindowTokens;
    }

    public int getMaxInputTokens() {
        return maxInputTokens;
    }

    public void setMaxInputTokens(int maxInputTokens) {
        this.maxInputTokens = maxInputTokens;
    }

    public int getOutputReserveTokens() {
        return outputReserveTokens;
    }

    public void setOutputReserveTokens(int outputReserveTokens) {
        this.outputReserveTokens = outputReserveTokens;
    }

    public int getMinOutputTokens() {
        return minOutputTokens;
    }

    public void setMinOutputTokens(int minOutputTokens) {
        this.minOutputTokens = minOutputTokens;
    }

    public double getTokenEstimateMultiplier() {
        return tokenEstimateMultiplier;
    }

    public void setTokenEstimateMultiplier(double tokenEstimateMultiplier) {
        this.tokenEstimateMultiplier = tokenEstimateMultiplier;
    }
}
