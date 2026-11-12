package com.example.aimindmap.loadtest;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

/**
 * 阶段八-3：压测打桩 EmbeddingModel——固定延迟 + 固定维度确定性向量，零真实 Embedding API 调用。
 *
 * <p><b>仅 loadtest profile 生效，生产零影响</b>：只被 {@link LoadTestStubConfiguration}
 * （@Profile("loadtest")）实例化为 @Primary bean；profile 未激活时自动装配的
 * OpenAiEmbeddingModel（DashScope text-embedding-v3）原样保留。
 *
 * <p>维度与 text-embedding-v3 配置一致（spring.ai.openai.embedding.options.dimensions=1024，
 * 同时也是 app.qdrant.vector-dimension），因此打桩向量可以直接写入本机真实 Qdrant 集合，
 * PDF 导入/文档保存触发的向量索引链路（VectorIndexTaskProcessor → Qdrant upsert）
 * 在压测中保持真实读写，不产生任何费用。
 *
 * <p>向量由文本 hashCode 作随机种子生成并归一化为单位向量：同一文本永远得到同一向量
 * （可复跑、幂等 upsert 稳定），不同文本向量不同（Qdrant 相似度检索行为不失真）。
 * <b>日志红线</b>：只打操作名、条数与延迟毫秒数，绝不打文本内容。
 */
public final class FixedVectorEmbeddingModel implements EmbeddingModel {

    private static final Logger log = LoggerFactory.getLogger(FixedVectorEmbeddingModel.class);

    private final long latencyMs;
    private final int dimension;

    public FixedVectorEmbeddingModel(long latencyMs, int dimension) {
        this.latencyMs = Math.max(0L, latencyMs);
        if (dimension <= 0) {
            throw new IllegalArgumentException("打桩向量维度必须为正数: " + dimension);
        }
        this.dimension = dimension;
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        List<String> inputs = request.getInstructions();
        log.debug("loadtest 打桩 EmbeddingModel 调用 operation=embedding count={} latencyMs={}",
                inputs.size(), latencyMs);
        sleep(latencyMs);
        List<Embedding> embeddings = new ArrayList<>(inputs.size());
        for (int i = 0; i < inputs.size(); i++) {
            embeddings.add(new Embedding(vectorFor(inputs.get(i)), i));
        }
        return new EmbeddingResponse(embeddings);
    }

    @Override
    public float[] embed(Document document) {
        log.debug("loadtest 打桩 EmbeddingModel 调用 operation=embedding_document latencyMs={}", latencyMs);
        sleep(latencyMs);
        return vectorFor(document.getText());
    }

    /** 覆盖默认实现：真实维度就是构造入参，避免默认实现再走一次 embed("Test String")。 */
    @Override
    public int dimensions() {
        return dimension;
    }

    /** 文本 hashCode 作种子的确定性单位向量。 */
    private float[] vectorFor(String text) {
        Random random = new Random(text == null ? 0L : text.hashCode());
        float[] vector = new float[dimension];
        double sumSquares = 0d;
        for (int i = 0; i < dimension; i++) {
            vector[i] = (random.nextFloat() * 2f) - 1f;
            sumSquares += (double) vector[i] * vector[i];
        }
        float norm = (float) Math.sqrt(sumSquares);
        if (norm > 0f) {
            for (int i = 0; i < dimension; i++) {
                vector[i] /= norm;
            }
        }
        return vector;
    }

    private static void sleep(long millis) {
        if (millis <= 0L) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("loadtest 打桩 EmbeddingModel 固定延迟被中断 operation=embedding latencyMs={}", millis);
        }
    }
}
