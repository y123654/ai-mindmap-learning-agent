package com.example.aimindmap.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.aimindmap.assistant.ChatRecentMessageCache.CachedMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * 阶段七-2：Redis 短期记忆缓存纯 Mockito 单测（严禁真实 Redis，真实往返见
 * integration/ChatRecentMessageCacheIntegrationTest）。验证：键拼接（前缀+N）、TTL 用配置值、
 * get 命中/未命中/异常降级/坏 JSON 按未命中、put 序列化与异常吞掉、evictSession 显式删两键
 * 与异常吞掉、hits/misses 计数、快照 JSON 往返保真。
 */
class RedisChatRecentMessageCacheTest {

    private static final long SESSION_ID = 10L;
    private static final String KEY_30 = "aimindmap:chat:10:recent:30";
    private static final String KEY_10 = "aimindmap:chat:10:recent:10";

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOperations;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private RedisChatRecentMessageCache cache;

    @BeforeEach
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mockValueOperations();
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        cache = new RedisChatRecentMessageCache(redisTemplate, objectMapper, "aimindmap:", 60L);
    }

    @Test
    void putWritesSnapshotJsonUnderPrefixedKeyWithConfiguredTtl() throws Exception {
        cache.put(SESSION_ID, 30, List.of(
                new CachedMessage("user", "什么是进程"),
                new CachedMessage("assistant", "进程是资源分配的基本单位")));

        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(eq(KEY_30), json.capture(), eq(Duration.ofSeconds(60)));
        var tree = objectMapper.readTree(json.getValue());
        assertThat(tree).hasSize(2);
        assertThat(tree.get(0).get("role").asText()).isEqualTo("user");
        assertThat(tree.get(0).get("content").asText()).isEqualTo("什么是进程");
        assertThat(tree.get(1).get("role").asText()).isEqualTo("assistant");
        assertThat(tree.get(1).get("content").asText()).isEqualTo("进程是资源分配的基本单位");
    }

    @Test
    void keyAndTtlFollowInjectedConfiguration() {
        RedisChatRecentMessageCache custom = new RedisChatRecentMessageCache(
                redisTemplate, objectMapper, "test-prefix:", 45L);

        custom.put(SESSION_ID, 10, List.of(new CachedMessage("user", "你好")));

        // 键 = {注入前缀}chat:{sessionId}:recent:{N}；TTL = 注入值
        verify(valueOperations).set(eq("test-prefix:chat:10:recent:10"), anyString(),
                eq(Duration.ofSeconds(45)));
    }

    @Test
    void getHitReturnsSnapshotAndCountsHit() {
        when(valueOperations.get(KEY_30)).thenReturn(
                "[{\"role\":\"user\",\"content\":\"什么是进程\"},"
                        + "{\"role\":\"assistant\",\"content\":\"进程是资源分配的基本单位\"}]");

        List<CachedMessage> result = cache.get(SESSION_ID, 30);

        assertThat(result).containsExactly(
                new CachedMessage("user", "什么是进程"),
                new CachedMessage("assistant", "进程是资源分配的基本单位"));
        assertThat(cache.getHits()).isEqualTo(1);
        assertThat(cache.getMisses()).isZero();
    }

    @Test
    void getMissReturnsNullAndCountsMiss() {
        when(valueOperations.get(KEY_10)).thenReturn(null);

        assertThat(cache.get(SESSION_ID, 10)).isNull();
        assertThat(cache.getHits()).isZero();
        assertThat(cache.getMisses()).isEqualTo(1);
    }

    @Test
    void getSwallowsRedisFailureAsMiss() {
        when(valueOperations.get(anyString()))
                .thenThrow(new RedisSystemException("redis down", null));

        assertThatCode(() -> cache.get(SESSION_ID, 30)).doesNotThrowAnyException();
        assertThat(cache.get(SESSION_ID, 30)).isNull();
        assertThat(cache.getHits()).isZero();
        assertThat(cache.getMisses()).isEqualTo(2);
    }

    @Test
    void getTreatsCorruptJsonAsMiss() {
        when(valueOperations.get(KEY_30)).thenReturn("not-a-json");

        assertThat(cache.get(SESSION_ID, 30)).isNull();
        assertThat(cache.getHits()).isZero();
        assertThat(cache.getMisses()).isEqualTo(1);
    }

    @Test
    void putSwallowsRedisFailure() {
        doThrow(new RedisSystemException("redis down", null))
                .when(valueOperations).set(anyString(), anyString(), any(Duration.class));

        assertThatCode(() -> cache.put(SESSION_ID, 30,
                List.of(new CachedMessage("user", "你好")))).doesNotThrowAnyException();
    }

    @Test
    void evictSessionDeletesBothRecentKeysExplicitly() {
        cache.evictSession(SESSION_ID);

        // 显式一次 DEL 两个键（禁 KEYS/SCAN 模式删除）
        ArgumentCaptor<Collection<String>> keys = collectionCaptor();
        verify(redisTemplate).delete(keys.capture());
        assertThat(keys.getValue()).containsExactlyInAnyOrder(KEY_10, KEY_30);
    }

    @Test
    void evictSessionSwallowsRedisFailure() {
        doThrow(new RedisSystemException("redis down", null))
                .when(redisTemplate).delete(anyCollection());

        assertThatCode(() -> cache.evictSession(SESSION_ID)).doesNotThrowAnyException();
    }

    @Test
    void hitsAndMissesAccumulateAcrossCalls() {
        when(valueOperations.get(KEY_30))
                .thenReturn("[{\"role\":\"user\",\"content\":\"a\"}]")
                .thenReturn(null);

        cache.get(SESSION_ID, 30);
        cache.get(SESSION_ID, 30);
        cache.get(SESSION_ID, 30);

        assertThat(cache.getHits()).isEqualTo(1);
        assertThat(cache.getMisses()).isEqualTo(2);
    }

    @Test
    void snapshotRoundTripsLosslessly() {
        List<CachedMessage> original = List.of(
                new CachedMessage("user", "解释\"进程与线程\"\n含换行、中文与引号"),
                new CachedMessage("assistant", "特殊字符：反斜杠\\ 斜杠/ 制表\t结束"));

        cache.put(SESSION_ID, 30, original);
        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(eq(KEY_30), json.capture(), any(Duration.class));
        when(valueOperations.get(KEY_30)).thenReturn(json.getValue());

        assertThat(cache.get(SESSION_ID, 30)).isEqualTo(original);
    }

    @SuppressWarnings("unchecked")
    private static ValueOperations<String, String> mockValueOperations() {
        return mock(ValueOperations.class);
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<Collection<String>> collectionCaptor() {
        return ArgumentCaptor.forClass(Collection.class);
    }
}
