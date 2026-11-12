package com.example.aimindmap.vectorindex;

import com.example.aimindmap.agentmemory.MemoryVectorService;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.aimindmap.document.DocumentEntity;
import com.example.aimindmap.document.DocumentRepository;
import com.example.aimindmap.metrics.BusinessMetrics;
import com.example.aimindmap.mindmap.GraphJsonCodec;
import com.example.aimindmap.mindmap.MindMapRepository;
import com.example.aimindmap.question.QuestionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.embedding.EmbeddingModel;

class VectorIndexTaskProcessorTest {

    private static final String DOCUMENT_CONTENT = "# 操作系统\n\n进程是资源分配的基本单位。";

    private final EmbeddingModel embeddingModel = org.mockito.Mockito.mock(EmbeddingModel.class);
    private final QdrantVectorGateway gateway = org.mockito.Mockito.mock(QdrantVectorGateway.class);
    private final VectorIndexEntryRepository entryRepository =
            org.mockito.Mockito.mock(VectorIndexEntryRepository.class);
    private final DocumentRepository documentRepository =
            org.mockito.Mockito.mock(DocumentRepository.class);
    private final MindMapRepository mindMapRepository =
            org.mockito.Mockito.mock(MindMapRepository.class);
    private final QuestionRepository questionRepository =
            org.mockito.Mockito.mock(QuestionRepository.class);
    private final GraphJsonCodec graphJsonCodec = org.mockito.Mockito.mock(GraphJsonCodec.class);
    private final QdrantProperties properties = new QdrantProperties();
    private final MemoryVectorService memoryVectorService =
            Mockito.mock(MemoryVectorService.class);
    /** 阶段八-1：embedding 指标断言用真实 SimpleMeterRegistry（不 mock 指标组件）。 */
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final BusinessMetrics businessMetrics = new BusinessMetrics(meterRegistry);

    private final VectorIndexTaskProcessor processor = new VectorIndexTaskProcessor(
            embeddingModel,
            gateway,
            new VectorContentBuilder(new ObjectMapper()),
            entryRepository,
            documentRepository,
            mindMapRepository,
            questionRepository,
            graphJsonCodec,
            properties,
            memoryVectorService,
            businessMetrics);

    @Test
    void upsertEmbedsAndWritesPointWithFullPayload() {
        DocumentEntity document = new DocumentEntity();
        document.setUserId(1L);
        document.setStatus("active");
        document.setContent(DOCUMENT_CONTENT);
        when(documentRepository.findById(3L)).thenReturn(Optional.of(document));
        when(entryRepository.findByResourceTypeAndResourceIdAndChunkIndex(
                anyString(), anyString(), any(Integer.class))).thenReturn(Optional.empty());
        when(embeddingModel.embed(anyString())).thenReturn(new float[]{0.1f, 0.2f});

        VectorIndexTaskService.TaskLoad load = new VectorIndexTaskService.TaskLoad(
                10L, 1L, VectorResourceType.DOCUMENT_CHUNK, "3", 0, VectorTaskOperation.UPSERT);

        VectorIndexTaskProcessor.ProcessResult result = processor.process(load);

        assertThat(result).isInstanceOf(
                VectorIndexTaskProcessor.ProcessResult.UpsertSucceeded.class);
        VectorIndexTaskProcessor.ProcessResult.UpsertSucceeded succeeded =
                (VectorIndexTaskProcessor.ProcessResult.UpsertSucceeded) result;
        UUID expectedPointId =
                VectorPointIds.pointId(VectorResourceType.DOCUMENT_CHUNK, "3", 0);
        assertThat(succeeded.pointId()).isEqualTo(expectedPointId);
        assertThat(succeeded.contentHash()).isEqualTo(sha256("# 操作系统\n进程是资源分配的基本单位。"));

        ArgumentCaptor<QdrantVectorGateway.VectorPoint> pointCaptor =
                ArgumentCaptor.forClass(QdrantVectorGateway.VectorPoint.class);
        verify(gateway).upsert(anyString(), pointCaptor.capture());
        QdrantVectorGateway.VectorPoint point = pointCaptor.getValue();
        assertThat(point.pointId()).isEqualTo(expectedPointId);
        assertThat(point.vector()).containsExactly(0.1f, 0.2f);
        assertThat(point.data().userId()).isEqualTo(1L);
        assertThat(point.data().resourceType()).isEqualTo("document_chunk");
        assertThat(point.data().sourceId()).isEqualTo("3");
        assertThat(point.data().status()).isEqualTo("active");
        assertThat(point.data().embeddingModel()).isEqualTo("text-embedding-v3");
        assertThat(point.data().sourcePath()).containsExactly("操作系统");
    }

    @Test
    void unchangedContentSkipsEmbeddingAndQdrant() {
        DocumentEntity document = new DocumentEntity();
        document.setUserId(1L);
        document.setStatus("active");
        document.setContent(DOCUMENT_CONTENT);
        when(documentRepository.findById(3L)).thenReturn(Optional.of(document));
        VectorIndexEntryEntity entry = new VectorIndexEntryEntity();
        entry.setStatus("ACTIVE");
        entry.setContentHash(sha256("# 操作系统\n进程是资源分配的基本单位。"));
        when(entryRepository.findByResourceTypeAndResourceIdAndChunkIndex(
                anyString(), anyString(), any(Integer.class))).thenReturn(Optional.of(entry));

        VectorIndexTaskService.TaskLoad load = new VectorIndexTaskService.TaskLoad(
                10L, 1L, VectorResourceType.DOCUMENT_CHUNK, "3", 0, VectorTaskOperation.UPSERT);

        VectorIndexTaskProcessor.ProcessResult result = processor.process(load);

        assertThat(result).isInstanceOf(VectorIndexTaskProcessor.ProcessResult.Skipped.class);
        verify(embeddingModel, never()).embed(anyString());
        verify(gateway, never()).upsert(anyString(), any());
    }

    @Test
    void embeddingFailureRaisesExternalException() {
        DocumentEntity document = new DocumentEntity();
        document.setUserId(1L);
        document.setStatus("active");
        document.setContent(DOCUMENT_CONTENT);
        when(documentRepository.findById(3L)).thenReturn(Optional.of(document));
        when(entryRepository.findByResourceTypeAndResourceIdAndChunkIndex(
                anyString(), anyString(), any(Integer.class))).thenReturn(Optional.empty());
        when(embeddingModel.embed(anyString()))
                .thenThrow(new RuntimeException("embedding upstream 500"));

        VectorIndexTaskService.TaskLoad load = new VectorIndexTaskService.TaskLoad(
                10L, 1L, VectorResourceType.DOCUMENT_CHUNK, "3", 0, VectorTaskOperation.UPSERT);

        assertThatThrownBy(() -> processor.process(load))
                .isInstanceOf(VectorIndexExternalException.class)
                .hasMessageContaining("Embedding 服务调用失败");
        verify(gateway, never()).upsert(anyString(), any());
    }

    @Test
    void missingResourceRaisesPermanentExceptionStillAssignableToExternal() {
        when(documentRepository.findById(3L)).thenReturn(Optional.empty());
        VectorIndexTaskService.TaskLoad load = new VectorIndexTaskService.TaskLoad(
                10L, 1L, VectorResourceType.DOCUMENT_CHUNK, "3", 0, VectorTaskOperation.UPSERT);

        assertThatThrownBy(() -> processor.process(load))
                .isInstanceOf(VectorIndexPermanentException.class)
                .isInstanceOf(VectorIndexExternalException.class)
                .hasMessageContaining("笔记已不存在");
        verify(embeddingModel, never()).embed(anyString());
        verify(gateway, never()).upsert(anyString(), any());
    }

    @Test
    void malformedNodeResourceIdRaisesPermanentException() {
        VectorIndexTaskService.TaskLoad load = new VectorIndexTaskService.TaskLoad(
                10L, 1L, VectorResourceType.MINDMAP_NODE, "bad-id", 0, VectorTaskOperation.UPSERT);

        assertThatThrownBy(() -> processor.process(load))
                .isInstanceOf(VectorIndexPermanentException.class)
                .hasMessageContaining("格式非法");
    }

    @Test
    void deleteRemovesPointByDeterministicId() {
        VectorIndexTaskService.TaskLoad load = new VectorIndexTaskService.TaskLoad(
                11L, 1L, VectorResourceType.MINDMAP_NODE, "4:n8", 0, VectorTaskOperation.DELETE);

        VectorIndexTaskProcessor.ProcessResult result = processor.process(load);

        assertThat(result).isInstanceOf(
                VectorIndexTaskProcessor.ProcessResult.ProcessDeleteSucceeded.class);
        verify(gateway).delete(properties.getCollection(),
                VectorPointIds.pointId(VectorResourceType.MINDMAP_NODE, "4:n8", 0));
    }

    @Test
    void memoryTasksAreDelegatedToMemoryVectorService() {
        VectorIndexTaskProcessor.ProcessResult expected =
                new VectorIndexTaskProcessor.ProcessResult.ProcessDeleteSucceeded();
        VectorIndexTaskService.TaskLoad memoryLoad = new VectorIndexTaskService.TaskLoad(
                12L, 1L, VectorResourceType.MEMORY, "9", 0, VectorTaskOperation.DELETE);
        Mockito.when(memoryVectorService.processTask(memoryLoad)).thenReturn(expected);

        assertThat(processor.process(memoryLoad)).isSameAs(expected);
        verify(gateway, never()).upsert(anyString(), any());
    }

    // ---------- 阶段八-1：embedding 耗时与失败率埋点（325 行第 ④ 项） ----------

    @Test
    void upsertRecordsEmbeddingSuccessMetric() {
        DocumentEntity document = new DocumentEntity();
        document.setUserId(1L);
        document.setStatus("active");
        document.setContent(DOCUMENT_CONTENT);
        when(documentRepository.findById(3L)).thenReturn(Optional.of(document));
        when(entryRepository.findByResourceTypeAndResourceIdAndChunkIndex(
                anyString(), anyString(), any(Integer.class))).thenReturn(Optional.empty());
        when(embeddingModel.embed(anyString())).thenReturn(new float[]{0.1f, 0.2f});
        VectorIndexTaskService.TaskLoad load = new VectorIndexTaskService.TaskLoad(
                10L, 1L, VectorResourceType.DOCUMENT_CHUNK, "3", 0, VectorTaskOperation.UPSERT);

        processor.process(load);

        assertThat(meterRegistry.timer("aimindmap.embedding.call",
                "operation", "index", "model", "text-embedding-v3", "outcome", "success").count())
                .isEqualTo(1L);
    }

    @Test
    void embeddingFailureRecordsFailureMetric() {
        DocumentEntity document = new DocumentEntity();
        document.setUserId(1L);
        document.setStatus("active");
        document.setContent(DOCUMENT_CONTENT);
        when(documentRepository.findById(3L)).thenReturn(Optional.of(document));
        when(entryRepository.findByResourceTypeAndResourceIdAndChunkIndex(
                anyString(), anyString(), any(Integer.class))).thenReturn(Optional.empty());
        when(embeddingModel.embed(anyString()))
                .thenThrow(new RuntimeException("embedding upstream 500"));
        VectorIndexTaskService.TaskLoad load = new VectorIndexTaskService.TaskLoad(
                10L, 1L, VectorResourceType.DOCUMENT_CHUNK, "3", 0, VectorTaskOperation.UPSERT);

        assertThatThrownBy(() -> processor.process(load))
                .isInstanceOf(VectorIndexExternalException.class);

        assertThat(meterRegistry.counter("aimindmap.embedding.call.failure",
                "operation", "index", "model", "text-embedding-v3").count()).isEqualTo(1.0);
    }

    @Test
    void unchangedContentRecordsNoEmbeddingMetric() {
        DocumentEntity document = new DocumentEntity();
        document.setUserId(1L);
        document.setStatus("active");
        document.setContent(DOCUMENT_CONTENT);
        when(documentRepository.findById(3L)).thenReturn(Optional.of(document));
        VectorIndexEntryEntity entry = new VectorIndexEntryEntity();
        entry.setStatus("ACTIVE");
        entry.setContentHash(sha256("# 操作系统\n进程是资源分配的基本单位。"));
        when(entryRepository.findByResourceTypeAndResourceIdAndChunkIndex(
                anyString(), anyString(), any(Integer.class))).thenReturn(Optional.of(entry));
        VectorIndexTaskService.TaskLoad load = new VectorIndexTaskService.TaskLoad(
                10L, 1L, VectorResourceType.DOCUMENT_CHUNK, "3", 0, VectorTaskOperation.UPSERT);

        processor.process(load);

        // Skipped 分支不调 embedding，就不该产生任何 embedding 序列
        assertThat(meterRegistry.find("aimindmap.embedding.call").timer()).isNull();
    }

    private static String sha256(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
