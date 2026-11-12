package com.example.aimindmap.agentruntime;

/**
 * 单条证据（方案 §P0-3）：一次 run 内被检索/读取工具登记到 {@link EvidenceLedger} 的资料片段。
 *
 * <p>{@code key} 形如 {@code E1}，会写进工具输出正文，模型据此在回答里标注 {@code [E1]}；
 * 其余字段用于落库 {@code agent_evidence} 与后续来源卡片。
 *
 * <p>{@code resourceId} 使用 {@code String}（所有者 2026-10-26 批准，方案 C）：
 * 文档/题目/记忆命中存数字字符串（如 {@code "888901"}），导图节点存真实 {@code nodeId}
 * （如 {@code "node-1"}）。这样 {@code agent_evidence.resource_id} 始终能反查真实来源，
 * 不需要把 nodeId 哈希成数字。
 *
 * @param key          证据编号（E1、E2…），单次 run 内唯一
 * @param resourceType 资料类型（document / document_chunk / mindmap / mindmap_node / question / memory）
 * @param resourceId   资料 ID（数字字符串或导图节点 nodeId）
 * @param chunkIndex   片段序号；整篇读取（read_my_document / read_mindmap）固定 0
 * @param score        相似度；没有相似度的读取类固定 1.0
 * @param title        标题
 * @param snippet      片段正文
 * @param suspicious   P1-E3b：{@code EvidenceSanitizer} 是否在这条资料的标题/正文里扫到
 *                     “疑似指令文本”。只用于落库 {@code agent_evidence.suspicious} 与排障，
 *                     <b>不</b>表示模型执行或没执行该指令（那要真调模型才能谈，留 P1-F）。
 */
public record EvidenceItem(
        String key,
        String resourceType,
        String resourceId,
        Integer chunkIndex,
        double score,
        String title,
        String snippet,
        boolean suspicious) {

    /**
     * 兼容构造器（P1-E3b 之前就有的 7 参写法）：{@code suspicious=false}。
     *
     * <p>之所以保留：{@code AgentEvalRunner} 与既有单测都按 7 参构造成“干净证据”，
     * 没必要为了让它们编译过去而改一堆与防护无关的代码。
     */
    public EvidenceItem(String key,
                        String resourceType,
                        String resourceId,
                        Integer chunkIndex,
                        double score,
                        String title,
                        String snippet) {
        this(key, resourceType, resourceId, chunkIndex, score, title, snippet, false);
    }
}
