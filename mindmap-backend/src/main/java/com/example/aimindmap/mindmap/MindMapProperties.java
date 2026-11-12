package com.example.aimindmap.mindmap;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 思维导图节点预算配置（《思维导图节点预算与折叠实施方案-v1.0》§4.1 / §4.5）。
 *
 * <p>由 {@code config/AsyncConfig} 的 {@code @EnableConfigurationProperties} 统一注册，
 * 本类不加 {@code @Component}（避免与注册处重复）。
 *
 * <ul>
 *   <li>{@code app.mindmap.max-new-nodes}：单次同步允许的新增节点上限（默认 200）；</li>
 *   <li>{@code app.mindmap.fold-enabled}：节点预算折叠总开关（默认 true，置 false 恢复旧行为）；</li>
 *   <li>{@code app.mindmap.summary-max-chars}：折叠摘要最大字符数（默认 500，且不得超过校验器上限）。</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "app.mindmap")
public class MindMapProperties {

    /** 单次同步允许的新增节点上限，默认 200（§9.4 硬上限，不在本缺陷修复中提高）。 */
    private int maxNewNodes = 200;

    /** 是否启用节点预算折叠；false 时恢复旧行为（超过上限直接失败，由校验器报错）。 */
    private boolean foldEnabled = true;

    /** 折叠摘要的最大字符数，默认 500；实际生效值取它与校验器 MAX_SUMMARY_LENGTH 的较小值。 */
    private int summaryMaxChars = 500;

    public int getMaxNewNodes() {
        return maxNewNodes;
    }

    public void setMaxNewNodes(int maxNewNodes) {
        this.maxNewNodes = maxNewNodes;
    }

    public boolean isFoldEnabled() {
        return foldEnabled;
    }

    public void setFoldEnabled(boolean foldEnabled) {
        this.foldEnabled = foldEnabled;
    }

    public int getSummaryMaxChars() {
        return summaryMaxChars;
    }

    public void setSummaryMaxChars(int summaryMaxChars) {
        this.summaryMaxChars = summaryMaxChars;
    }
}
