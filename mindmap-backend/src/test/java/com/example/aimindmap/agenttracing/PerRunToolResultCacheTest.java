package com.example.aimindmap.agenttracing;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class PerRunToolResultCacheTest {

    private PerRunToolResultCache newCache() {
        return new PerRunToolResultCache(new ObjectMapper());
    }

    @Test
    void sameArgumentsHitAndHitsCountIncrementsOnlyOnHit() {
        PerRunToolResultCache cache = newCache();

        assertThat(cache.get("search_my_documents", "{\"query\":\"进程\"}")).isNull();
        cache.put("search_my_documents", "{\"query\":\"进程\"}", "片段内容");

        assertThat(cache.get("search_my_documents", "{\"query\":\"进程\"}")).isEqualTo("片段内容");
        assertThat(cache.hits()).isEqualTo(1);
        assertThat(cache.get("search_my_documents", "{\"query\":\"线程\"}")).isNull();
        assertThat(cache.hits()).isEqualTo(1);
    }

    @Test
    void keyOrderAndWhitespaceDifferencesStillHit() {
        PerRunToolResultCache cache = newCache();

        cache.put("search_my_documents", "{\"query\":\"进程\",\"top\":2}", "结果A");

        assertThat(cache.get("search_my_documents", "{  \"top\": 2, \"query\": \"进程\" }"))
                .isEqualTo("结果A");
        assertThat(cache.hits()).isEqualTo(1);
    }

    @Test
    void invalidJsonFallsBackToExactRawMatch() {
        PerRunToolResultCache cache = newCache();

        cache.put("search_my_documents", "{query 进程}", "结果B");

        assertThat(cache.get("search_my_documents", "{query 进程}")).isEqualTo("结果B");
        assertThat(cache.get("search_my_documents", "{query  进程}")).isNull();
        assertThat(cache.hits()).isEqualTo(1);
    }

    @Test
    void sameArgumentsUnderDifferentToolNamesDoNotCollide() {
        PerRunToolResultCache cache = newCache();

        cache.put("search_my_documents", "{\"query\":\"进程\"}", "文档结果");

        assertThat(cache.get("search_my_memory", "{\"query\":\"进程\"}")).isNull();
        assertThat(cache.get("search_my_documents", "{\"query\":\"进程\"}")).isEqualTo("文档结果");
    }

    @Test
    void nullResultIsNotCachedAndInstancesAreIsolated() {
        PerRunToolResultCache cache = newCache();

        cache.put("search_my_documents", "{}", null);
        assertThat(cache.get("search_my_documents", "{}")).isNull();

        PerRunToolResultCache other = newCache();
        cache.put("read_my_document", "{\"documentId\":\"1\"}", "正文");
        assertThat(other.get("read_my_document", "{\"documentId\":\"1\"}")).isNull();
        assertThat(other.hits()).isZero();
    }
}
