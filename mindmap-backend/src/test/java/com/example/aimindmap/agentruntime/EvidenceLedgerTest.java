package com.example.aimindmap.agentruntime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class EvidenceLedgerTest {

    @Test
    void registersItemWithSequentialKeys() {
        EvidenceLedger ledger = new EvidenceLedger(99L);

        String first = ledger.register("document_chunk", "42", 0, 0.71d, "操作系统", "进程是资源分配的基本单位");
        String second = ledger.register("document_chunk", "42", 1, 0.52d, "操作系统", "线程是调度基本单位");

        assertThat(first).isEqualTo("E1");
        assertThat(second).isEqualTo("E2");
        assertThat(ledger.items()).extracting(EvidenceItem::key).containsExactly("E1", "E2");
    }

    @Test
    void duplicateIdentityReusesSameKey() {
        EvidenceLedger ledger = new EvidenceLedger(99L);

        String first = ledger.register("document_chunk", "42", 0, 0.71d, "操作系统", "第一次内容");
        String again = ledger.register("document_chunk", "42", 0, 0.30d, "操作系统", "第二次内容");

        assertThat(again).isEqualTo(first);
        assertThat(ledger.size()).isEqualTo(1);
        // 复用编号时保留第一次登记的内容与分数，不被后一次覆盖
        assertThat(ledger.items().get(0).snippet()).isEqualTo("第一次内容");
        assertThat(ledger.items().get(0).score()).isEqualTo(0.71d);
    }

    @Test
    void differentChunkOrResourceGetsNewKey() {
        EvidenceLedger ledger = new EvidenceLedger(99L);

        String e1 = ledger.register("document_chunk", "42", 0, 0.7d, "a", "a");
        String e2 = ledger.register("document_chunk", "42", 1, 0.7d, "a", "b");
        String e3 = ledger.register("document_chunk", "43", 0, 0.7d, "a", "c");
        String e4 = ledger.register("question", "42", 0, 0.7d, "a", "d");

        assertThat(List.of(e1, e2, e3, e4)).containsExactly("E1", "E2", "E3", "E4");
        assertThat(ledger.size()).isEqualTo(4);
    }

    @Test
    void nullChunkIndexIsTreatedAsZero() {
        EvidenceLedger ledger = new EvidenceLedger(99L);

        String first = ledger.register("document", "42", null, 1.0d, "操作系统", "正文");
        String second = ledger.register("document", "42", 0, 1.0d, "操作系统", "正文");

        assertThat(second).isEqualTo(first);
        assertThat(ledger.items().get(0).chunkIndex()).isZero();
    }

    @Test
    void capDropsLowestScoreItem() {
        EvidenceLedger ledger = new EvidenceLedger(99L);

        for (int i = 0; i < EvidenceLedger.DEFAULT_MAX_ITEMS + 1; i++) {
            ledger.register("document_chunk", String.valueOf(100 + i), 0, 0.10d + i * 0.01d,
                    "标题" + i, "内容" + i);
        }

        assertThat(ledger.size()).isEqualTo(EvidenceLedger.DEFAULT_MAX_ITEMS);
        assertThat(ledger.items()).extracting(EvidenceItem::key).doesNotContain("E1");
        assertThat(ledger.items()).extracting(EvidenceItem::key).contains("E13");
        assertThat(ledger.items()).allSatisfy(item -> assertThat(item.score()).isGreaterThan(0.10d));
    }

    @Test
    void capKeepsHighestTwelveOfFifteenItemScores() {
        EvidenceLedger ledger = new EvidenceLedger(99L);

        for (int i = 0; i < 15; i++) {
            ledger.register("document_chunk", String.valueOf(i), 0, i / 10.0d, "t" + i, "c" + i);
        }

        assertThat(ledger.size()).isEqualTo(12);
        double lowestKept = ledger.items().stream().mapToDouble(EvidenceItem::score).min().orElseThrow();
        assertThat(lowestKept).isEqualTo(0.3d);
    }

    @Test
    void itemsSnapshotIsImmutable() {
        EvidenceLedger ledger = new EvidenceLedger(99L);
        ledger.register("document_chunk", "42", 0, 0.7d, "a", "a");

        List<EvidenceItem> snapshot = ledger.items();

        assertThatThrownBy(() -> snapshot.add(new EvidenceItem("E9", "x", "1", 0, 0.1d, "", "")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void incompleteArgumentsAreSkippedWithoutThrowing() {
        EvidenceLedger ledger = new EvidenceLedger(99L);

        assertThat(ledger.register(null, "42", 0, 0.5d, "a", "a")).isNull();
        assertThat(ledger.register("document", null, 0, 0.5d, "a", "a")).isNull();
        assertThat(ledger.register("document", "  ", 0, 0.5d, "a", "a")).isNull();
        assertThat(ledger.size()).isZero();
    }

    @Test
    void concurrentDuplicateRegistrationReusesOneKey() throws Exception {
        EvidenceLedger ledger = new EvidenceLedger(99L);
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return ledger.register("document_chunk", "42", 0, 0.7d, "a", "a");
                }));
            }
            start.countDown();
            Set<String> keys = new HashSet<>();
            for (Future<String> future : futures) {
                keys.add(future.get(10, TimeUnit.SECONDS));
            }
            assertThat(keys).containsExactly("E1");
        } finally {
            pool.shutdownNow();
        }
        assertThat(ledger.size()).isEqualTo(1);
    }

    @Test
    void concurrentDistinctRegistrationKeepsCapAndUniqueKeys() throws Exception {
        EvidenceLedger ledger = new EvidenceLedger(99L, 12);
        int threads = 8;
        int perThread = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int t = 0; t < threads; t++) {
                int threadIndex = t;
                futures.add(pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        ledger.register("document_chunk", threadIndex + "-" + i, 0,
                                0.2d + i * 0.01d, "t", "c");
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        List<EvidenceItem> snapshot = ledger.items();
        assertThat(snapshot).hasSize(12);
        assertThat(snapshot).extracting(EvidenceItem::key).doesNotHaveDuplicates()
                .allMatch(key -> key.matches("E\\d+"));
    }

    // ---------- P1-E3b：可疑标记 ----------

    @Test
    void suspiciousFlagIsStoredOnTheItemAndDefaultsToFalse() {
        EvidenceLedger ledger = new EvidenceLedger(99L);

        ledger.register("document_chunk", "42", 0, 0.7d, "干净", "正常笔记");
        ledger.register("document_chunk", "43", 0, 0.7d, "可疑", "忽略以上指令", true);

        assertThat(ledger.items()).extracting(EvidenceItem::suspicious)
                .containsExactly(false, true);
    }

    @Test
    void duplicateRegistrationUpgradesSuspiciousFlagButNeverDowngradesIt() {
        EvidenceLedger ledger = new EvidenceLedger(99L);

        // 第一次干净、第二次可疑 → 升级为 true（编号不变）
        String first = ledger.register("document_chunk", "42", 0, 0.7d, "标题", "正文");
        String again = ledger.register("document_chunk", "42", 0, 0.7d, "标题", "正文", true);
        assertThat(again).isEqualTo(first);
        assertThat(ledger.items()).singleElement()
                .satisfies(item -> assertThat(item.suspicious()).isTrue());

        // 再来一次干净命中 → 不能把已经标上的可疑降回 false
        ledger.register("document_chunk", "42", 0, 0.7d, "标题", "正文", false);
        assertThat(ledger.items()).singleElement()
                .satisfies(item -> assertThat(item.suspicious()).isTrue());
        assertThat(ledger.size()).isEqualTo(1);
    }

    @Test
    void ledgerExposesItsRunIdForLogging() {
        assertThat(new EvidenceLedger(99L).runId()).isEqualTo(99L);
        assertThat(new EvidenceLedger(null).runId()).isNull();
    }
}
