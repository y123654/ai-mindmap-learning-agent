package com.example.aimindmap.mqsupport.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

/** 仓储关键 SQL 的轻量防御性断言：真实并发行为归 4-4 Testcontainers（H1-2/H1-5）。 */
class OutboxRepositoryContractTest {

    @Test
    void claimPendingUsesSkipLockedWithLimit() throws Exception {
        Method method = OutboxEventRepository.class.getMethod("claimPending", int.class);
        Query query = method.getAnnotation(Query.class);

        assertThat(query).isNotNull();
        assertThat(query.nativeQuery()).isTrue();
        String sql = query.value().toUpperCase();
        assertThat(sql).contains("FOR UPDATE SKIP LOCKED");
        assertThat(sql).contains("LIMIT :LIMIT");
        assertThat(sql).contains("STATUS = 'PENDING'");
    }

    @Test
    void deleteSentBeforeIsModifyingQuery() throws Exception {
        Method method = OutboxEventRepository.class.getMethod("deleteSentBefore", Instant.class);

        assertThat(method.getAnnotation(Modifying.class)).isNotNull();
        Query query = method.getAnnotation(Query.class);
        assertThat(query).isNotNull();
        assertThat(query.value()).containsIgnoringCase("DELETE");
    }
}
