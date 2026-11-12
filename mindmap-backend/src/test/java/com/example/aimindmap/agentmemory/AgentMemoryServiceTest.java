package com.example.aimindmap.agentmemory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.aimindmap.common.error.ApiException;
import com.example.aimindmap.vectorindex.VectorSearchService;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;

class AgentMemoryServiceTest {

    private AgentMemoryRepository memoryRepository;
    private MemoryVectorService vectorService;
    private AgentMemoryService service;

    @BeforeEach
    void setUp() {
        memoryRepository = mock(AgentMemoryRepository.class);
        vectorService = mock(MemoryVectorService.class);
        service = new AgentMemoryService(memoryRepository, vectorService);
    }

    @Test
    void listWithoutStatusReturnsAll() {
        List<AgentMemoryEntity> memories = List.of(memory(1L, 1L, "active", "preference", "c1"));
        when(memoryRepository.findByUserIdOrderByImportanceDescUpdatedAtDesc(1L)).thenReturn(memories);

        assertThat(service.listMemories(1L, null)).isSameAs(memories);
        assertThat(service.listMemories(1L, "  ")).isSameAs(memories);
    }

    @Test
    void listWithStatusFiltersAndRejectsInvalidStatus() {
        when(memoryRepository.findByUserIdAndStatusOrderByImportanceDescUpdatedAtDesc(1L, "active"))
                .thenReturn(List.of());
        assertThat(service.listMemories(1L, " active ")).isEmpty();

        assertThatThrownBy(() -> service.listMemories(1L, "rejected"))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void createCandidateAlwaysLandsCandidateAndTrimsContent() {
        when(memoryRepository.save(any())).thenAnswer(invocation -> {
            AgentMemoryEntity saved = invocation.getArgument(0);
            setId(saved, 7L);
            return saved;
        });
        var request = new AgentMemoryDtos.CreateMemoryRequest(
                "  可能喜欢代码例子  ", "preference", (short) 4, (short) 80);

        AgentMemoryEntity saved = service.createCandidate(1L, request);

        assertThat(saved.getStatus()).isEqualTo("candidate");
        assertThat(saved.getContent()).isEqualTo("可能喜欢代码例子");
        assertThat(saved.getMemoryType()).isEqualTo("preference");
        assertThat(saved.getImportance()).isEqualTo((short) 4);
        assertThat(saved.getConfidence()).isEqualTo((short) 80);
        verify(vectorService, never()).upsertPoint(any());
    }

    @Test
    void createCandidateRejectsInvalidType() {
        var request = new AgentMemoryDtos.CreateMemoryRequest(
                "x", "unknown", (short) 1, (short) 0);
        assertThatThrownBy(() -> service.createCandidate(1L, request))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void createExplicitActiveMemoryBackfillsSourceSessionAndMessage() {
        when(memoryRepository.save(any())).thenAnswer(invocation -> {
            AgentMemoryEntity saved = invocation.getArgument(0);
            setId(saved, 9L);
            return saved;
        });
        var decision = new MemoryDecision.Create("preference", "我喜欢用代码例子学习");

        AgentMemoryEntity saved =
                service.createExplicitActiveMemory(1L, decision, 10L, 77L);

        assertThat(saved.getStatus()).isEqualTo("active");
        assertThat(saved.getMemoryType()).isEqualTo("preference");
        assertThat(saved.getContent()).isEqualTo("我喜欢用代码例子学习");
        assertThat(saved.getImportance()).isEqualTo((short) 3);
        assertThat(saved.getConfidence()).isEqualTo((short) 100);
        assertThat(saved.getSourceSessionId()).isEqualTo(10L);
        assertThat(saved.getSourceMessageId()).isEqualTo(77L);
        verify(vectorService, never()).upsertPoint(any());
    }

    @Test
    void mergeExplicitMemoryKeepsOriginalSourceFields() {
        AgentMemoryEntity existing = memory(9L, 1L, "active", "preference", "旧内容：喜欢例子");
        existing.setSourceSessionId(10L);
        existing.setSourceMessageId(77L);
        when(memoryRepository.findById(9L)).thenReturn(java.util.Optional.of(existing));
        when(memoryRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        var decision = new MemoryDecision.Merge(9L, "用户喜欢代码例子，不喜欢纯理论");

        AgentMemoryEntity merged = service.mergeExplicitMemory(1L, decision);

        assertThat(merged.getContent()).isEqualTo("用户喜欢代码例子，不喜欢纯理论");
        assertThat(merged.getSourceSessionId()).as("合并保留最初来源会话").isEqualTo(10L);
        assertThat(merged.getSourceMessageId()).as("合并保留最初来源消息").isEqualTo(77L);
    }

    @Test
    void updateActiveMemoryWithChangedContentRebuildsPoint() {
        AgentMemoryEntity existing = memory(7L, 1L, "active", "preference", "旧内容");
        when(memoryRepository.findById(7L)).thenReturn(java.util.Optional.of(existing));
        when(memoryRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        var request = new AgentMemoryDtos.UpdateMemoryRequest(
                "新内容", "preference", (short) 5, (short) 90);

        AgentMemoryEntity updated = service.updateMemory(1L, 7L, request);

        assertThat(updated.getContent()).isEqualTo("新内容");
        assertThat(updated.getImportance()).isEqualTo((short) 5);
        verify(vectorService).upsertPoint(updated);
    }

    @Test
    void updateActiveMemoryTypeChangeRebuildsPoint() {
        AgentMemoryEntity existing = memory(7L, 1L, "active", "fact", "内容");
        when(memoryRepository.findById(7L)).thenReturn(java.util.Optional.of(existing));
        when(memoryRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        var request = new AgentMemoryDtos.UpdateMemoryRequest(
                "内容", "goal", (short) 3, (short) 100);

        service.updateMemory(1L, 7L, request);

        verify(vectorService).upsertPoint(any());
    }

    @Test
    void updateUnchangedActiveMemoryDoesNotTouchPoint() {
        AgentMemoryEntity existing = memory(7L, 1L, "active", "preference", "内容");
        when(memoryRepository.findById(7L)).thenReturn(java.util.Optional.of(existing));
        when(memoryRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        var request = new AgentMemoryDtos.UpdateMemoryRequest(
                "内容", "preference", (short) 2, (short) 50);

        service.updateMemory(1L, 7L, request);

        verify(vectorService, never()).upsertPoint(any());
    }

    @Test
    void updateCandidateMemoryNeverTouchesPoint() {
        AgentMemoryEntity existing = memory(7L, 1L, "candidate", "preference", "旧");
        when(memoryRepository.findById(7L)).thenReturn(java.util.Optional.of(existing));
        when(memoryRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        var request = new AgentMemoryDtos.UpdateMemoryRequest(
                "新", "preference", (short) 3, (short) 100);

        service.updateMemory(1L, 7L, request);

        verify(vectorService, never()).upsertPoint(any());
    }

    @Test
    void updateRejectsUnknownOrOtherUsersMemoryWith404() {
        when(memoryRepository.findById(9L)).thenReturn(java.util.Optional.empty());
        var request = new AgentMemoryDtos.UpdateMemoryRequest(
                "x", "fact", (short) 1, (short) 0);
        assertThatThrownBy(() -> service.updateMemory(1L, 9L, request))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getStatus().value()).isEqualTo(404));
    }

    @Test
    void deleteRemovesRowAndPointAndRejectsOthersWith404() {
        AgentMemoryEntity existing = memory(7L, 1L, "active", "preference", "x");
        when(memoryRepository.findById(7L)).thenReturn(java.util.Optional.of(existing));

        service.deleteMemory(1L, 7L);

        verify(memoryRepository).delete(existing);
        verify(vectorService).deletePoint(7L);

        assertThatThrownBy(() -> service.deleteMemory(1L, 8L))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getStatus().value()).isEqualTo(404));
    }

    @Test
    void confirmCandidateActivatesAndWritesPoint() {
        AgentMemoryEntity candidate = memory(7L, 1L, "candidate", "preference", "x");
        when(memoryRepository.findById(7L)).thenReturn(java.util.Optional.of(candidate));
        when(memoryRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        AgentMemoryEntity confirmed = service.confirmMemory(1L, 7L);

        assertThat(confirmed.getStatus()).isEqualTo("active");
        verify(vectorService).upsertPoint(confirmed);
    }

    @Test
    void confirmArchivedActivatesAndConfirmActiveIsIdempotent() {
        AgentMemoryEntity archived = memory(7L, 1L, "archived", "goal", "x");
        when(memoryRepository.findById(7L)).thenReturn(java.util.Optional.of(archived));
        when(memoryRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        assertThat(service.confirmMemory(1L, 7L).getStatus()).isEqualTo("active");

        AgentMemoryEntity active = memory(8L, 1L, "active", "goal", "y");
        when(memoryRepository.findById(8L)).thenReturn(java.util.Optional.of(active));
        assertThat(service.confirmMemory(1L, 8L)).isSameAs(active);
        verify(vectorService, never()).upsertPoint(active);
    }

    @Test
    void confirmRejectedIs400AndUnknownIs404() {
        AgentMemoryEntity rejected = memory(7L, 1L, "rejected", "fact", "x");
        when(memoryRepository.findById(7L)).thenReturn(java.util.Optional.of(rejected));
        assertThatThrownBy(() -> service.confirmMemory(1L, 7L))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.confirmMemory(1L, 9L))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void archiveActiveRemovesPointAndIsIdempotentOtherwise() {
        AgentMemoryEntity active = memory(7L, 1L, "active", "preference", "x");
        when(memoryRepository.findById(7L)).thenReturn(java.util.Optional.of(active));
        when(memoryRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        assertThat(service.archiveMemory(1L, 7L).getStatus()).isEqualTo("archived");
        verify(vectorService).deletePoint(7L);

        AgentMemoryEntity archived = memory(8L, 1L, "archived", "preference", "y");
        when(memoryRepository.findById(8L)).thenReturn(java.util.Optional.of(archived));
        service.archiveMemory(1L, 8L);
        verify(vectorService, never()).deletePoint(8L);

        AgentMemoryEntity rejected = memory(9L, 1L, "rejected", "fact", "z");
        when(memoryRepository.findById(9L)).thenReturn(java.util.Optional.of(rejected));
        assertThatThrownBy(() -> service.archiveMemory(1L, 9L)).isInstanceOf(ApiException.class);
    }

    @Test
    void clearDeletesOnlyOwnMemoriesAndAllPoints() {
        List<AgentMemoryEntity> memories = List.of(
                memory(7L, 1L, "active", "preference", "x"),
                memory(8L, 1L, "candidate", "fact", "y"));
        when(memoryRepository.findByUserIdOrderByImportanceDescUpdatedAtDesc(1L))
                .thenReturn(memories);

        int count = service.clearMemories(1L);

        assertThat(count).isEqualTo(2);
        verify(memoryRepository).deleteAll(memories);
        verify(vectorService).deletePoint(7L);
        verify(vectorService).deletePoint(8L);
    }

    @Test
    void buildInjectionOnlyUsesActiveAndFormatsLines() {
        List<AgentMemoryEntity> active = List.of(
                memory(7L, 1L, "active", "preference", "喜欢代码例子"));
        when(memoryRepository.findByUserIdAndStatusOrderByImportanceDescUpdatedAtDesc(1L, "active"))
                .thenReturn(active);

        AgentMemoryService.MemoryInjection injection = service.buildInjection(1L);

        assertThat(injection.block()).contains("----- 长期记忆")
                .contains("- [偏好] 喜欢代码例子")
                .contains("----- 长期记忆结束 -----");
        assertThat(injection.memories()).extracting(AgentMemoryEntity::getId).containsExactly(7L);
    }

    @Test
    void buildInjectionRespects1200BudgetSkipsItemAndTruncatesOversize() {
        AgentMemoryEntity fits = memory(7L, 1L, "active", "preference", "好".repeat(1090));
        AgentMemoryEntity small = memory(8L, 1L, "active", "fact", "短".repeat(50));
        when(memoryRepository.findByUserIdAndStatusOrderByImportanceDescUpdatedAtDesc(1L, "active"))
                .thenReturn(List.of(fits, small));

        AgentMemoryService.MemoryInjection injection = service.buildInjection(1L);

        assertThat(injection.block().length()).isLessThanOrEqualTo(1200);
        assertThat(injection.memories()).extracting(AgentMemoryEntity::getId).containsExactly(7L);
        assertThat(injection.block()).doesNotContain("短内容");
    }

    @Test
    void buildInjectionTruncatesSingleOversizeMemory() {
        AgentMemoryEntity huge = memory(7L, 1L, "active", "fact", "长".repeat(1500));
        when(memoryRepository.findByUserIdAndStatusOrderByImportanceDescUpdatedAtDesc(1L, "active"))
                .thenReturn(List.of(huge));

        AgentMemoryService.MemoryInjection injection = service.buildInjection(1L);

        assertThat(injection.block().length()).isLessThanOrEqualTo(1200);
        assertThat(injection.block()).contains("…");
        assertThat(injection.block()).contains("----- 长期记忆结束 -----");
        assertThat(injection.memories()).extracting(AgentMemoryEntity::getId).containsExactly(7L);
    }

    // ---------- P1-6：常驻 + 相关性注入 ----------

    @Test
    void buildInjectionWithBlankQueryIsByteIdenticalToSingleArgVersion() {
        AgentMemoryEntity profile = memory(7L, 1L, "active", "profile", "计算机专业大三");
        AgentMemoryEntity fact = memory(8L, 1L, "active", "fact", "正在复习操作系统");
        when(memoryRepository.findByUserIdAndStatusOrderByImportanceDescUpdatedAtDesc(1L, "active"))
                .thenReturn(List.of(profile, fact));

        String legacy = service.buildInjection(1L).block();
        String nullQuery = service.buildInjection(1L, null).block();
        String blankQuery = service.buildInjection(1L, "   ").block();

        String expected = "----- 长期记忆（系统已了解的该用户信息；请自然运用，不要生硬复述） -----"
                + "\n- [个人信息] 计算机专业大三"
                + "\n- [事实] 正在复习操作系统"
                + "\n----- 长期记忆结束 -----";
        assertThat(legacy).as("单参版本仍是旧的逐字节输出").isEqualTo(expected);
        assertThat(nullQuery).isEqualTo(legacy);
        assertThat(blankQuery).isEqualTo(legacy);
        // 空 query 不进入相关性检索
        verify(vectorService, never()).searchMemories(any(), any(), anyDouble(), anyInt());
    }

    @Test
    void buildInjectionWithQueryKeepsResidentTypesWithinFourHundredChars() {
        String markerProfile = "常驻画像A";
        String markerPreference = "常驻偏好B";
        String markerGoal = "常驻目标C";
        AgentMemoryEntity profile = memory(7L, 1L, "active", "profile",
                markerProfile + "甲".repeat(250), (short) 5);
        AgentMemoryEntity preference = memory(8L, 1L, "active", "preference",
                markerPreference + "乙".repeat(250), (short) 4);
        AgentMemoryEntity goal = memory(9L, 1L, "active", "goal",
                markerGoal + "丙".repeat(250), (short) 3);
        when(memoryRepository.findByUserIdAndStatusOrderByImportanceDescUpdatedAtDesc(1L, "active"))
                .thenReturn(List.of(profile, preference, goal));
        when(vectorService.searchMemories(any(), any(), anyDouble(), anyInt())).thenReturn(List.of());

        AgentMemoryService.MemoryInjection injection = service.buildInjection(1L, "解释进程和线程");

        // 一条 265 字符，两条就超 400 → 第二条起整条跳过，不切半
        assertThat(injection.memories()).extracting(AgentMemoryEntity::getId).containsExactly(7L);
        assertThat(injection.block()).contains(markerProfile)
                .doesNotContain(markerPreference)
                .doesNotContain(markerGoal);
        String residentSection = injection.block().substring(
                AgentMemoryService.BLOCK_HEADER.length(),
                injection.block().length() - AgentMemoryService.BLOCK_FOOTER.length());
        assertThat(residentSection.length())
                .isLessThanOrEqualTo(AgentMemoryService.RESIDENT_MEMORY_MAX_CHARS);
        assertThat(injection.block().length())
                .isLessThanOrEqualTo(AgentMemoryService.MEMORY_BLOCK_MAX_CHARS);
        assertThat(injection.block()).endsWith("----- 长期记忆结束 -----");
    }

    @Test
    void buildInjectionWithQueryOrdersRelevantMemoriesByScoreTimesImportance() {
        AgentMemoryEntity topImportance = memory(1L, 1L, "active", "fact", "命中一", (short) 5);
        AgentMemoryEntity lowImportance = memory(2L, 1L, "active", "weakness", "命中二", (short) 1);
        AgentMemoryEntity midImportance = memory(3L, 1L, "active", "fact", "命中三", (short) 3);
        AgentMemoryEntity taskMemory = memory(4L, 1L, "active", "task", "命中四", (short) 2);
        AgentMemoryEntity highImportance = memory(5L, 1L, "active", "fact", "命中五", (short) 4);
        // 仓库返回的是重要度降序（5,1,3,2,4 是乱序，贴近真实查询顺序无关）
        when(memoryRepository.findByUserIdAndStatusOrderByImportanceDescUpdatedAtDesc(1L, "active"))
                .thenReturn(List.of(lowImportance, topImportance, taskMemory, midImportance, highImportance));
        // 向量检索按相关分降序返回：0.95 / 0.90 / 0.80 / 0.60 / 0.55
        when(vectorService.searchMemories(1L, "进程和线程", 0.5d, 5)).thenReturn(List.of(
                hit("2", 0.95d), hit("1", 0.90d), hit("4", 0.80d), hit("3", 0.60d), hit("5", 0.55d)));

        AgentMemoryService.MemoryInjection injection = service.buildInjection(1L, "进程和线程");

        // 相关分 × 重要度：4.5 / 2.2 / 1.8 / 1.6 / 0.95，与“纯相关分”顺序（2,1,4,3,5）不同
        assertThat(injection.memories()).extracting(AgentMemoryEntity::getId)
                .containsExactly(1L, 5L, 3L, 4L, 2L);
        assertThat(injection.block())
                .contains("- [事实] 命中一")
                .contains("- [事实] 命中五")
                .contains("- [事实] 命中三")
                .contains("- [任务] 命中四")
                .contains("- [薄弱点] 命中二");
        verify(vectorService).searchMemories(1L, "进程和线程", 0.5d, 5);
    }

    @Test
    void buildInjectionWithQueryDeduplicatesSameMemoryIdAcrossSections() {
        AgentMemoryEntity profile = memory(7L, 1L, "active", "profile", "常驻画像标记", (short) 4);
        AgentMemoryEntity fact = memory(8L, 1L, "active", "fact", "相关事实标记", (short) 3);
        when(memoryRepository.findByUserIdAndStatusOrderByImportanceDescUpdatedAtDesc(1L, "active"))
                .thenReturn(List.of(profile, fact));
        // 7 既命中相关又是常驻；8 只命中相关
        when(vectorService.searchMemories(any(), any(), anyDouble(), anyInt())).thenReturn(List.of(
                hit("7", 0.92d), hit("8", 0.88d), hit("7", 0.80d)));

        AgentMemoryService.MemoryInjection injection = service.buildInjection(1L, "进程和线程");

        assertThat(injection.memories()).extracting(AgentMemoryEntity::getId).containsExactly(7L, 8L);
        assertThat(countOccurrences(injection.block(), "常驻画像标记")).isEqualTo(1);
        assertThat(countOccurrences(injection.block(), "相关事实标记")).isEqualTo(1);
        // 常驻在前
        assertThat(injection.block().indexOf("常驻画像标记"))
                .isLessThan(injection.block().indexOf("相关事实标记"));
    }

    @Test
    void buildInjectionWithQueryCapsTotalAt1200CharsAndKeepsFooter() {
        AgentMemoryEntity resident = memory(1L, 1L, "active", "profile",
                "常驻画像" + "甲".repeat(380), (short) 5);
        List<AgentMemoryEntity> active = new ArrayList<>();
        active.add(resident);
        List<VectorSearchService.VectorHit> hits = new ArrayList<>();
        for (int i = 2; i <= 6; i++) {
            active.add(memory((long) i, 1L, "active", "fact",
                    "相关" + i + "乙".repeat(200), (short) 4));
            hits.add(hit(String.valueOf(i), 0.90d - i * 0.01d));
        }
        when(memoryRepository.findByUserIdAndStatusOrderByImportanceDescUpdatedAtDesc(1L, "active"))
                .thenReturn(active);
        when(vectorService.searchMemories(any(), any(), anyDouble(), anyInt())).thenReturn(hits);

        AgentMemoryService.MemoryInjection injection = service.buildInjection(1L, "解释进程和线程");

        assertThat(injection.block().length())
                .isLessThanOrEqualTo(AgentMemoryService.MEMORY_BLOCK_MAX_CHARS);
        assertThat(injection.block()).endsWith("----- 长期记忆结束 -----");
        assertThat(injection.memories().size()).isGreaterThan(1).isLessThan(6);
        assertThat(injection.block()).contains("常驻画像");
    }

    @Test
    void buildInjectionWithQueryFallsBackToLegacyWhenVectorSearchFails() {
        AgentMemoryEntity preference = memory(7L, 1L, "active", "preference", "喜欢代码例子");
        AgentMemoryEntity fact = memory(8L, 1L, "active", "fact", "正在复习操作系统");
        when(memoryRepository.findByUserIdAndStatusOrderByImportanceDescUpdatedAtDesc(1L, "active"))
                .thenReturn(List.of(preference, fact));
        when(vectorService.searchMemories(any(), any(), anyDouble(), anyInt()))
                .thenThrow(new IllegalStateException("qdrant down"));

        AgentMemoryService.MemoryInjection legacy = service.buildInjection(1L);
        ListAppender<ILoggingEvent> appender = attachAppender(AgentMemoryService.class);
        AgentMemoryService.MemoryInjection degraded;
        try {
            // 不抛异常：本轮问答不失败
            degraded = service.buildInjection(1L, "进程和线程");
        } finally {
            detachAppender(AgentMemoryService.class, appender);
        }

        assertThat(degraded.block()).as("回退结果与单参旧逻辑逐字节一致").isEqualTo(legacy.block());
        assertThat(degraded.memories()).extracting(AgentMemoryEntity::getId).containsExactly(7L, 8L);
        List<String> warnings = appender.list.stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
        assertThat(warnings).anySatisfy(message -> assertThat(message)
                .contains("记忆相关性检索失败")
                .contains("userId=1")
                .contains("qdrant down"));
        // 日志不带记忆正文
        assertThat(appender.list)
                .noneMatch(event -> event.getFormattedMessage().contains("喜欢代码例子"));
    }


    @Test
    void touchLastUsedUpdatesAllAndSaves() {
        List<AgentMemoryEntity> memories = List.of(
                memory(7L, 1L, "active", "preference", "x"),
                memory(8L, 1L, "active", "fact", "y"));

        service.touchLastUsed(memories);

        verify(memoryRepository).saveAll(memories);
        assertThat(memories).allSatisfy(memory -> assertThat(memory.getLastUsedAt()).isNotNull());
    }

    @Test
    void syncPointAfterCommitRunsWithoutTransaction() {
        AgentMemoryEntity memory = memory(7L, 1L, "active", "preference", "x");

        service.syncPointAfterCommit(1L, memory);

        verify(vectorService).upsertPoint(memory);
    }

    private static AgentMemoryEntity memory(Long id, Long userId, String status, String type, String content) {
        return memory(id, userId, status, type, content, (short) 3);
    }

    private static AgentMemoryEntity memory(Long id, Long userId, String status, String type,
                                            String content, short importance) {
        AgentMemoryEntity memory = new AgentMemoryEntity();
        setId(memory, id);
        memory.setUserId(userId);
        memory.setStatus(status);
        memory.setMemoryType(type);
        memory.setContent(content);
        memory.setImportance(importance);
        memory.setConfidence((short) 100);
        return memory;
    }

    private static VectorSearchService.VectorHit hit(String sourceId, double score) {
        return new VectorSearchService.VectorHit(
                "memory", sourceId, 0, "title", "content", List.of(), score, null);
    }

    private static int countOccurrences(String text, String marker) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(marker, index)) >= 0) {
            count++;
            index += marker.length();
        }
        return count;
    }

    /** 把 ListAppender 挂到指定类的 logger 上，用于断言日志内容（用完必须摘掉）。 */
    private static ListAppender<ILoggingEvent> attachAppender(Class<?> type) {
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(type);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private static void detachAppender(Class<?> type, ListAppender<ILoggingEvent> appender) {
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(type);
        logger.detachAppender(appender);
    }

    private static void setId(Object target, Long id) {
        try {
            var field = target.getClass().getDeclaredField("id");
            field.setAccessible(true);
            field.set(target, id);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
