package com.example.aimindmap.agentmemory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.aimindmap.ai.AiProperties;
import com.example.aimindmap.metrics.BusinessMetrics;
import com.example.aimindmap.vectorindex.QdrantProperties;
import com.example.aimindmap.vectorindex.QdrantVectorGateway;
import com.example.aimindmap.vectorindex.VectorIndexExternalException;
import com.example.aimindmap.vectorindex.VectorIndexTaskProcessor;
import com.example.aimindmap.vectorindex.VectorIndexTaskService;
import com.example.aimindmap.vectorindex.VectorResourceType;
import com.example.aimindmap.vectorindex.VectorSearchService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.embedding.EmbeddingModel;

class MemoryVectorServiceTest {

    private AgentMemoryRepository memoryRepository;
    private EmbeddingModel embeddingModel;
    private QdrantVectorGateway gateway;
    private ChatModel chatModel;
    private VectorSearchService vectorSearchService;
    private final QdrantProperties qdrantProperties = new QdrantProperties();
    private final AgentMemoryProperties memoryProperties = new AgentMemoryProperties();
    /** 阶段八-1：embedding 指标断言用真实 SimpleMeterRegistry（不 mock 指标组件）。 */
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final BusinessMetrics businessMetrics = new BusinessMetrics(meterRegistry);
    private MemoryVectorService service;

    @BeforeEach
    void setUp() {
        memoryRepository = mock(AgentMemoryRepository.class);
        embeddingModel = mock(EmbeddingModel.class);
        gateway = mock(QdrantVectorGateway.class);
        chatModel = mock(ChatModel.class);
        vectorSearchService = mock(VectorSearchService.class);
        when(embeddingModel.embed(anyString())).thenReturn(new float[1024]);
        service = new MemoryVectorService(
                memoryRepository, embeddingModel, gateway, qdrantProperties,
                memoryProperties, new AiProperties(), chatModel, vectorSearchService,
                businessMetrics);
    }

    @Test
    void decideReturnsMergeWhenSimilarMemoryFound() {
        when(vectorSearchService.searchTopMemory(1L, "我更喜欢代码例子", 0.80d))
                .thenReturn(Optional.of(new VectorSearchService.VectorHit(
                        "memory", "7", 0, "title",
                        "记忆类型：偏好\n记忆内容：喜欢用例子", List.of(), 0.91d, null)));
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(List.of(new Generation(
                        new AssistantMessage("用户喜欢用代码例子，不喜欢纯理论")))));

        MemoryDecision decision = service.decideExplicitMemory(1L, "preference", "我更喜欢代码例子");

        assertThat(decision).isInstanceOfSatisfying(MemoryDecision.Merge.class, merge -> {
            assertThat(merge.memoryId()).isEqualTo(7L);
            assertThat(merge.mergedContent()).isEqualTo("用户喜欢用代码例子，不喜欢纯理论");
        });
        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(promptCaptor.capture());
        String sent = promptCaptor.getValue().getInstructions().get(1).getText();
        assertThat(sent).contains("喜欢用例子").contains("我更喜欢代码例子");
    }

    @Test
    void decideReturnsCreateWhenNoSimilarMemory() {
        when(vectorSearchService.searchTopMemory(any(), anyString(), any(Double.class)))
                .thenReturn(Optional.empty());

        MemoryDecision decision = service.decideExplicitMemory(1L, "fact", "系统下午两点开门");

        assertThat(decision).isInstanceOfSatisfying(MemoryDecision.Create.class,
                create -> {
                    assertThat(create.memoryType()).isEqualTo("fact");
                    assertThat(create.content()).isEqualTo("系统下午两点开门");
                });
        verify(chatModel, never()).call(any(Prompt.class));
    }

    @Test
    void decideDegradesToCreateWhenDedupSearchFails() {
        when(vectorSearchService.searchTopMemory(any(), anyString(), any(Double.class)))
                .thenThrow(new VectorIndexExternalException("Qdrant 检索失败", null));

        MemoryDecision decision = service.decideExplicitMemory(1L, "preference", "喜欢代码");

        assertThat(decision).isInstanceOf(MemoryDecision.Create.class);
    }

    @Test
    void decideFallsBackToNewContentWhenMergeLlmFails() {
        when(vectorSearchService.searchTopMemory(any(), anyString(), any(Double.class)))
                .thenReturn(Optional.of(new VectorSearchService.VectorHit(
                        "memory", "7", 0, "t",
                        "记忆类型：偏好\n记忆内容：旧", List.of(), 0.9d, null)));
        when(chatModel.call(any(Prompt.class)))
                .thenThrow(new RuntimeException("llm down"));

        MemoryDecision decision = service.decideExplicitMemory(1L, "preference", "新内容");

        assertThat(decision).isInstanceOfSatisfying(MemoryDecision.Merge.class,
                merge -> {
                    assertThat(merge.memoryId()).isEqualTo(7L);
                    assertThat(merge.mergedContent()).isEqualTo("新内容");
                });
    }

    @Test
    void upsertPointWritesWithIncrementedVersionAndFullPayload() {
        when(gateway.readPayload(anyString(), any()))
                .thenReturn(Optional.of(Map.of("content_version", 1L)));
        AgentMemoryEntity memory = memory(7L, 1L, "active", "preference", "喜欢代码例子");

        service.upsertPoint(memory);

        ArgumentCaptor<QdrantVectorGateway.VectorPoint> captor =
                ArgumentCaptor.forClass(QdrantVectorGateway.VectorPoint.class);
        verify(gateway).upsert(anyString(), captor.capture());
        var data = captor.getValue().data();
        assertThat(data.userId()).isEqualTo(1L);
        assertThat(data.resourceType()).isEqualTo("memory");
        assertThat(data.sourceId()).isEqualTo("7");
        assertThat(data.status()).isEqualTo("active");
        assertThat(data.contentVersion()).isEqualTo(2);
        assertThat(data.content()).contains("记忆类型：偏好").contains("喜欢代码例子");
        assertThat(data.title()).isEqualTo("喜欢代码例子");
        assertThat(data.embeddingModel()).isEqualTo("text-embedding-v3");
        assertThat(data.sourcePath()).isEmpty();
    }

    @Test
    void upsertPointUsesVersion1WhenPointMissing() {
        when(gateway.readPayload(anyString(), any())).thenReturn(Optional.empty());

        service.upsertPoint(memory(7L, 1L, "active", "fact", "新事实"));

        ArgumentCaptor<QdrantVectorGateway.VectorPoint> captor =
                ArgumentCaptor.forClass(QdrantVectorGateway.VectorPoint.class);
        verify(gateway).upsert(anyString(), captor.capture());
        assertThat(captor.getValue().data().contentVersion()).isEqualTo(1);
    }

    @Test
    void upsertPointWrapsEmbeddingFailure() {
        when(embeddingModel.embed(anyString()))
                .thenThrow(new RuntimeException("embedding down"));

        assertThatThrownBy(() -> service.upsertPoint(memory(7L, 1L, "active", "fact", "x")))
                .isInstanceOf(VectorIndexExternalException.class);
        verify(gateway, never()).upsert(anyString(), any());
    }

    // ---------- 阶段八-1：embedding 耗时与失败率埋点（325 行第 ④ 项） ----------

    @Test
    void upsertPointRecordsEmbeddingSuccessMetric() {
        when(gateway.readPayload(anyString(), any())).thenReturn(Optional.empty());

        service.upsertPoint(memory(7L, 1L, "active", "fact", "新事实"));

        assertThat(meterRegistry.timer("aimindmap.embedding.call",
                "operation", "memory_upsert", "model", "text-embedding-v3", "outcome", "success").count())
                .isEqualTo(1L);
    }

    @Test
    void upsertPointEmbeddingFailureRecordsFailureMetric() {
        when(embeddingModel.embed(anyString()))
                .thenThrow(new RuntimeException("embedding down"));

        assertThatThrownBy(() -> service.upsertPoint(memory(7L, 1L, "active", "fact", "x")))
                .isInstanceOf(VectorIndexExternalException.class);

        assertThat(meterRegistry.counter("aimindmap.embedding.call.failure",
                "operation", "memory_upsert", "model", "text-embedding-v3").count()).isEqualTo(1.0);
        assertThat(meterRegistry.find("aimindmap.embedding.call").tag("outcome", "success").timer())
                .isNull();
    }

    @Test
    void deletePointCallsGateway() {
        service.deletePoint(7L);
        verify(gateway).delete(anyString(), any(UUID.class));
    }

    @Test
    void cleanupOrphanPointsDeletesOnlyUnexpectedPoints() {
        UUID orphan = UUID.randomUUID();
        when(gateway.listPointIds(anyString(), any()))
                .thenReturn(List.of(MemoryVectorService.memoryPointId(7L), orphan));

        service.cleanupOrphanPoints(1L, List.of(7L));

        verify(gateway).delete(anyString(), org.mockito.ArgumentMatchers.eq(orphan));
        verify(gateway, never()).delete(anyString(),
                org.mockito.ArgumentMatchers.eq(MemoryVectorService.memoryPointId(7L)));
    }

    @Test
    void cleanupOrphanPointsSkipsWhenEnumerationFails() {
        when(gateway.listPointIds(anyString(), any()))
                .thenThrow(new RuntimeException("qdrant down"));
        service.cleanupOrphanPoints(1L, List.of(7L));
        verify(gateway, never()).delete(anyString(), any(UUID.class));
    }

    @Test
    void processTaskUpsertRebuildsActiveMemory() {
        when(memoryRepository.findById(7L))
                .thenReturn(Optional.of(memory(7L, 1L, "active", "preference", "喜欢代码")));
        when(gateway.readPayload(anyString(), any())).thenReturn(Optional.empty());
        var task = new VectorIndexTaskService.TaskLoad(
                11L, 1L, VectorResourceType.MEMORY, "7", 0,
                com.example.aimindmap.vectorindex.VectorTaskOperation.UPSERT);

        VectorIndexTaskProcessor.ProcessResult result = service.processTask(task);

        assertThat(result).isInstanceOf(
                VectorIndexTaskProcessor.ProcessResult.UpsertSucceeded.class);
        verify(gateway).upsert(anyString(), any());
    }

    @Test
    void processTaskUpsertRejectsNonActiveMemory() {
        when(memoryRepository.findById(7L))
                .thenReturn(Optional.of(memory(7L, 1L, "archived", "preference", "x")));
        var task = new VectorIndexTaskService.TaskLoad(
                11L, 1L, VectorResourceType.MEMORY, "7", 0,
                com.example.aimindmap.vectorindex.VectorTaskOperation.UPSERT);

        assertThatThrownBy(() -> service.processTask(task))
                .isInstanceOf(VectorIndexExternalException.class);
    }

    @Test
    void processTaskDeleteRemovesPoint() {
        var task = new VectorIndexTaskService.TaskLoad(
                11L, 1L, VectorResourceType.MEMORY, "7", 0,
                com.example.aimindmap.vectorindex.VectorTaskOperation.DELETE);

        VectorIndexTaskProcessor.ProcessResult result = service.processTask(task);

        assertThat(result).isInstanceOf(
                VectorIndexTaskProcessor.ProcessResult.ProcessDeleteSucceeded.class);
        verify(gateway).delete(anyString(), any(UUID.class));
    }

    @Test
    void searchMemoriesDelegatesToVectorSearchWithCallerThresholdAndLimit() {
        VectorSearchService.VectorHit hit = new VectorSearchService.VectorHit(
                "memory", "7", 0, "t", "c", List.of(), 0.91d, null);
        when(vectorSearchService.searchMemories(1L, "进程和线程", 0.5d, 5))
                .thenReturn(List.of(hit));

        assertThat(service.searchMemories(1L, "进程和线程", 0.5d, 5)).containsExactly(hit);
        verify(vectorSearchService).searchMemories(1L, "进程和线程", 0.5d, 5);
    }

    private static AgentMemoryEntity memory(Long id, Long userId, String status, String type, String content) {
        AgentMemoryEntity memory = new AgentMemoryEntity();
        setId(memory, id);
        memory.setUserId(userId);
        memory.setStatus(status);
        memory.setMemoryType(type);
        memory.setContent(content);
        memory.setImportance((short) 3);
        memory.setConfidence((short) 100);
        return memory;
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
