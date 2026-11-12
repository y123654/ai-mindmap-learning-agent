package com.example.aimindmap.integration;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * 阶段四 4-4：仅在本机 Docker 可用时运行 MQ 集成测试（方案 §3.2：
 * 无 Docker 跳过并说明，不得依赖开发机正好开着的 broker 容器）。
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@ExtendWith(DockerAvailableCondition.class)
public @interface EnabledIfDockerAvailable {
}