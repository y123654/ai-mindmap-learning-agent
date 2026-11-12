package com.example.aimindmap.assistant;

import java.util.List;

/**
 * 阶段七-2：会话「最近 N 条消息」短期记忆缓存（Cache-Aside，方案 §阶段七 第 2 条）。
 *
 * <p>只缓存 {@code KnowledgeAssistantService#buildGatewayBudget} 消费的最近消息查询
 * （有摘要 N=10 / 无摘要 N=30，全仓唯一读点）；<b>严禁缓存</b>摘要链路
 * （{@code SessionSummaryService}）与会话详情（{@code findBySessionIdOrderByIdAsc}）
 * 的全量消息查询（方案 319 行同时禁止向量检索缓存与缓存预热，均未做）。
 *
 * <p>两个实现（镜像阶段七-1 ProgressBroadcaster 双实现模式，开关 {@code app.cache.redis-enabled}）：
 * <ul>
 *   <li>{@link RedisChatRecentMessageCache}：缺省装配；Redis 字符串键 + JSON 快照 + TTL，
 *       任何 Redis 故障 catch→warn→按未命中/跳过降级，绝不影响聊天主链路；</li>
 *   <li>{@link DisabledChatRecentMessageCache}：redis-enabled=false 时装配；全部 no-op，
 *       聊天行为与引入缓存前的现状完全一致。</li>
 * </ul>
 *
 * <p><b>定位（方案 §0 第 3 条 / §1.3）：</b>PostgreSQL 始终是唯一事实源，Redis 只做缓存、
 * 丢失可随时重建；本项目使用 Redis db2，键统一带 {@code app.cache.key-prefix}（默认 aimindmap:）前缀。
 *
 * <p><b>指标安排：</b>本步命中率统计以 Redis 实现内的 AtomicLong 计数器落点
 * （见 {@link RedisChatRecentMessageCache#getHits()} / {@link RedisChatRecentMessageCache#getMisses()}）；
 * micrometer/actuator 属阶段八「报备后引入」，本步严禁引入任何指标依赖，阶段八再把计数器导出 Prometheus。
 */
public interface ChatRecentMessageCache {

    /**
     * 缓存值快照：只保留下游消费的 role/content，列表为时间正序（旧→新）。
     * 不直接序列化 JPA 实体（AiChatMessageEntity 的 id/createdAt 无 setter，往返不保真），
     * 纯字符串字段天然保证往返保真，命中与回源两条路径构造的 candidates 逐字节一致。
     */
    record CachedMessage(String role, String content) {
    }

    /**
     * 读取会话最近 {@code limit} 条消息快照（时间正序）。
     *
     * @param limit 最近条数，仅 {10, 30} 两档（对应有/无摘要的候选口径）
     * @return 命中返回快照列表；未命中、缓存故障或值损坏一律返回 null（调用方回源查库），绝不抛异常
     */
    List<CachedMessage> get(Long sessionId, int limit);

    /**
     * 回填快照（时间正序），TTL 由实现配置决定；序列化或 Redis 故障只 warn 跳过，绝不抛异常。
     */
    void put(Long sessionId, int limit, List<CachedMessage> messages);

    /**
     * 失效该会话全部最近消息缓存键：显式删除 :recent:10 与 :recent:30 两个键
     * （禁 KEYS/SCAN 模式删除）；Redis 故障只 warn，绝不抛异常。
     * 调用点：用户消息 save 后、助手消息 save 后、删除会话后（写路径主动失效）。
     */
    void evictSession(Long sessionId);
}
