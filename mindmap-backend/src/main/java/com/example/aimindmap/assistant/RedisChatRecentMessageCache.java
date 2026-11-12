package com.example.aimindmap.assistant;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 短期记忆缓存 Redis 实现（方案 §阶段七 第 2 条，{@code app.cache.redis-enabled} 缺省 true 时装配）。
 *
 * <p><b>键：</b>严格 {@code {app.cache.key-prefix}chat:{sessionId}:recent:{N}}（N∈{10,30}，
 * 前缀经配置注入、默认 aimindmap:）；<b>值：</b>快照 JSON 数组（复用项目 ObjectMapper）；
 * <b>TTL：</b>{@code app.cache.chat-recent-ttl-seconds}（默认 60s）。连接走既有
 * {@code spring.data.redis.*}（本项目 db2），不新增任何连接配置。
 *
 * <p><b>故障隔离：</b>get/put/evict 的任何 Redis 异常（{@link DataAccessException}）与坏 JSON
 * 一律 catch→warn→按未命中/跳过处理，缓存故障绝不影响聊天主链路；PostgreSQL 是唯一事实源，
 * 缓存丢失可由下一次回源查询重建。日志只打 sessionId/键名/条数/字节数/计数，绝不打消息正文。
 *
 * <p><b>失效语义：</b>{@link #evictSession} 显式 DEL :recent:10 与 :recent:30 两个键，
 * 禁 KEYS/SCAN 全库模式删除（db2 与他人共用容器，模式删除有越界风险且是 O(N) 阻塞命令）。
 *
 * <p><b>命中统计与阶段八衔接：</b>hits/misses 为进程内 {@link AtomicLong} 计数器，
 * 经 {@link #getHits()} / {@link #getMisses()} 只读暴露，日志最多 debug 级、不刷屏。
 * micrometer/actuator 属阶段八「报备后引入」，本步不引入任何指标依赖——这两个计数器就是
 * 方案 317 行「命中率打指标」在本步的落点，阶段八把它们导出为 Prometheus 命中率面板。
 */
@Component
@ConditionalOnProperty(name = "app.cache.redis-enabled", havingValue = "true", matchIfMissing = true)
public class RedisChatRecentMessageCache implements ChatRecentMessageCache {

    /** 参与缓存的两档 N（有摘要 top10 / 无摘要 top30）；evictSession 逐键显式删除。 */
    private static final List<Integer> CACHED_LIMITS = List.of(10, 30);

    private static final TypeReference<List<CachedMessage>> SNAPSHOT_LIST = new TypeReference<>() {
    };

    private static final Logger log = LoggerFactory.getLogger(RedisChatRecentMessageCache.class);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final String keyPrefix;
    private final long ttlSeconds;
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();

    public RedisChatRecentMessageCache(StringRedisTemplate redisTemplate,
                                       ObjectMapper objectMapper,
                                       @Value("${app.cache.key-prefix:aimindmap:}") String keyPrefix,
                                       @Value("${app.cache.chat-recent-ttl-seconds:60}") long ttlSeconds) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.keyPrefix = keyPrefix;
        this.ttlSeconds = ttlSeconds;
    }

    @Override
    public List<CachedMessage> get(Long sessionId, int limit) {
        String key = key(sessionId, limit);
        String json;
        try {
            json = redisTemplate.opsForValue().get(key);
        } catch (DataAccessException e) {
            misses.incrementAndGet();
            log.warn("短期记忆缓存读取失败，按未命中处理 sessionId={} key={}", sessionId, key, e);
            return null;
        }
        if (json == null) {
            misses.incrementAndGet();
            log.debug("短期记忆缓存未命中 sessionId={} key={} misses={}", sessionId, key, misses.get());
            return null;
        }
        try {
            List<CachedMessage> snapshots = objectMapper.readValue(json, SNAPSHOT_LIST);
            hits.incrementAndGet();
            log.debug("短期记忆缓存命中 sessionId={} key={} count={} bytes={} hits={}",
                    sessionId, key, snapshots.size(), json.length(), hits.get());
            return snapshots;
        } catch (JsonProcessingException e) {
            // 值损坏按未命中处理：调用方回源查库，随后的 put 会用正确 JSON 覆盖坏值
            misses.incrementAndGet();
            log.warn("短期记忆缓存值损坏，按未命中处理 sessionId={} key={} bytes={}",
                    sessionId, key, json.length(), e);
            return null;
        }
    }

    @Override
    public void put(Long sessionId, int limit, List<CachedMessage> messages) {
        String key = key(sessionId, limit);
        String json;
        try {
            json = objectMapper.writeValueAsString(messages);
        } catch (JsonProcessingException e) {
            log.warn("短期记忆缓存值序列化失败，跳过回填 sessionId={} key={} count={}",
                    sessionId, key, messages == null ? -1 : messages.size(), e);
            return;
        }
        try {
            redisTemplate.opsForValue().set(key, json, Duration.ofSeconds(ttlSeconds));
        } catch (DataAccessException e) {
            log.warn("短期记忆缓存回填失败，跳过 sessionId={} key={} bytes={} ttlSeconds={}",
                    sessionId, key, json.length(), ttlSeconds, e);
        }
    }

    @Override
    public void evictSession(Long sessionId) {
        List<String> keys = CACHED_LIMITS.stream().map(limit -> key(sessionId, limit)).toList();
        try {
            redisTemplate.delete(keys);
        } catch (DataAccessException e) {
            // 失效失败不阻断写路径：幻影缓存最长存活一个 TTL，且下一轮写会再次失效
            log.warn("短期记忆缓存失效失败（TTL 到期或下轮写会再失效） sessionId={} keys={}",
                    sessionId, keys, e);
        }
    }

    /** 键名：{key-prefix}chat:{sessionId}:recent:{N}。 */
    private String key(Long sessionId, int limit) {
        return keyPrefix + "chat:" + sessionId + ":recent:" + limit;
    }

    /** 命中累计次数（只读）；阶段八作为命中率分子导出 Prometheus。 */
    public long getHits() {
        return hits.get();
    }

    /** 未命中累计次数（只读，含缓存故障/坏 JSON 按未命中）；阶段八作为命中率分母的一部分。 */
    public long getMisses() {
        return misses.get();
    }
}
