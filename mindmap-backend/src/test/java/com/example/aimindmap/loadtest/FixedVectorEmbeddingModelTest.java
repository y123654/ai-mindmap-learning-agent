package com.example.aimindmap.loadtest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

/**
 * 阶段八-3：打桩 EmbeddingModel 行为测试（纯单元，不起 Spring 上下文）。
 * 锁死：维度正确（与 text-embedding-v3 的 1024 维一致）、确定性（可复跑）、
 * 单位向量（Qdrant 余弦检索不失真）、固定延迟（±容差）。
 */
class FixedVectorEmbeddingModelTest {

    @Test
    void embedReturnsUnitVectorOfConfiguredDimensionDeterministically() {
        FixedVectorEmbeddingModel model = new FixedVectorEmbeddingModel(0L, 1024);
        assertThat(model.dimensions()).isEqualTo(1024);

        float[] first = model.embed("数据结构压测文本");
        float[] second = model.embed("数据结构压测文本");
        float[] other = model.embed("另一段完全不同的文本");

        assertThat(first).hasSize(1024);
        assertThat(first).containsExactly(second, org.assertj.core.data.Offset.offset(0f));
        assertThat(first).isNotEqualTo(other);
        assertThat(norm(first)).isCloseTo(1.0d, org.assertj.core.data.Offset.offset(1e-4d));
    }

    @Test
    void callReturnsIndexedEmbeddingsForEveryInputAfterLatency() {
        FixedVectorEmbeddingModel model = new FixedVectorEmbeddingModel(150L, 8);
        long started = System.nanoTime();
        EmbeddingResponse response = model.call(new EmbeddingRequest(
                List.of("文本一", "文本二", "文本三"), null));
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000L;

        List<Embedding> results = response.getResults();
        assertThat(results).hasSize(3);
        for (int i = 0; i < 3; i++) {
            assertThat(results.get(i).getIndex()).isEqualTo(i);
            assertThat(results.get(i).getOutput()).hasSize(8);
        }
        // 固定延迟 150ms，容差 ±15%
        assertThat(elapsedMillis).isBetween(127L, 1000L);
    }

    @Test
    void embedDocumentUsesSameDeterministicVector() {
        FixedVectorEmbeddingModel model = new FixedVectorEmbeddingModel(0L, 16);
        float[] viaDocument = model.embed(new Document("同一段内容"));
        float[] viaString = model.embed("同一段内容");
        assertThat(viaDocument).hasSize(16);
        assertThat(viaDocument).containsExactly(viaString, org.assertj.core.data.Offset.offset(0f));
    }

    @Test
    void nonPositiveDimensionRejectedAtConstruction() {
        assertThatThrownBy(() -> new FixedVectorEmbeddingModel(0L, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static double norm(float[] vector) {
        double sum = 0d;
        for (float value : vector) {
            sum += (double) value * value;
        }
        return Math.sqrt(sum);
    }
}
