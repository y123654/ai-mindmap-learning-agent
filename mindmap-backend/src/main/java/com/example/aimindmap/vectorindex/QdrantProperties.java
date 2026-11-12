package com.example.aimindmap.vectorindex;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Qdrant 连接、集合与索引 worker 配置；全部可被环境变量覆盖，不硬编码密钥。 */
@ConfigurationProperties(prefix = "app.qdrant")
public class QdrantProperties {

    private String host = "127.0.0.1";
    private int grpcPort = 6334;
    private boolean useTls = false;
    private String collection = "learning_resources_text_embedding_v3_1024";
    private int vectorDimension = 1024;
    private String embeddingModel = "text-embedding-v3";
    private int workerBatchSize = 10;
    private long workerFixedDelayMs = 5000;
    private int maxAttempts = 5;
    private int searchTopK = 8;
    private int searchMaxResults = 5;
    private double minScore = 0.35d;
    private long callTimeoutSeconds = 30;

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public int getGrpcPort() {
        return grpcPort;
    }

    public void setGrpcPort(int grpcPort) {
        this.grpcPort = grpcPort;
    }

    public boolean isUseTls() {
        return useTls;
    }

    public void setUseTls(boolean useTls) {
        this.useTls = useTls;
    }

    public String getCollection() {
        return collection;
    }

    public void setCollection(String collection) {
        this.collection = collection;
    }

    public int getVectorDimension() {
        return vectorDimension;
    }

    public void setVectorDimension(int vectorDimension) {
        this.vectorDimension = vectorDimension;
    }

    public String getEmbeddingModel() {
        return embeddingModel;
    }

    public void setEmbeddingModel(String embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    public int getWorkerBatchSize() {
        return workerBatchSize;
    }

    public void setWorkerBatchSize(int workerBatchSize) {
        this.workerBatchSize = workerBatchSize;
    }

    public long getWorkerFixedDelayMs() {
        return workerFixedDelayMs;
    }

    public void setWorkerFixedDelayMs(long workerFixedDelayMs) {
        this.workerFixedDelayMs = workerFixedDelayMs;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    public int getSearchTopK() {
        return searchTopK;
    }

    public void setSearchTopK(int searchTopK) {
        this.searchTopK = searchTopK;
    }

    public int getSearchMaxResults() {
        return searchMaxResults;
    }

    public void setSearchMaxResults(int searchMaxResults) {
        this.searchMaxResults = searchMaxResults;
    }

    public double getMinScore() {
        return minScore;
    }

    public void setMinScore(double minScore) {
        this.minScore = minScore;
    }

    public long getCallTimeoutSeconds() {
        return callTimeoutSeconds;
    }

    public void setCallTimeoutSeconds(long callTimeoutSeconds) {
        this.callTimeoutSeconds = callTimeoutSeconds;
    }
}
