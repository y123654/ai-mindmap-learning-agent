package com.example.aimindmap.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.example.aimindmap.assistant.ChatRecentMessageCache.CachedMessage;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 阶段七-2：关闭实现单测——全部 no-op、get 恒 null（调用方永远回源查库），
 * 对应 app.cache.redis-enabled=false 时聊天行为与引入缓存前完全一致。
 */
class DisabledChatRecentMessageCacheTest {

    private final DisabledChatRecentMessageCache cache = new DisabledChatRecentMessageCache();

    @Test
    void getAlwaysReturnsNullForBothLimits() {
        assertThat(cache.get(10L, 30)).isNull();
        assertThat(cache.get(10L, 10)).isNull();
    }

    @Test
    void putAndEvictAreNoOpsAndNeverThrow() {
        assertThatCode(() -> {
            cache.put(10L, 30, List.of(new CachedMessage("user", "你好")));
            cache.evictSession(10L);
        }).doesNotThrowAnyException();

        // put 之后仍恒未命中：关闭实现不保存任何状态
        assertThat(cache.get(10L, 30)).isNull();
    }
}
