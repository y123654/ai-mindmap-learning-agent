package com.example.aimindmap.vectorindex;

import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Qdrant gRPC 客户端配置（方案 A：官方 Java 客户端）。 */
@Configuration
@EnableConfigurationProperties(QdrantProperties.class)
public class QdrantConfig {

    @Bean(destroyMethod = "close")
    public QdrantClient qdrantClient(QdrantProperties properties) {
        QdrantGrpcClient grpcClient = QdrantGrpcClient.newBuilder(
                properties.getHost(), properties.getGrpcPort(), properties.isUseTls()).build();
        return new QdrantClient(grpcClient);
    }
}
