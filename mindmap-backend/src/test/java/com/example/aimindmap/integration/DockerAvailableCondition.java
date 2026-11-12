package com.example.aimindmap.integration;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.testcontainers.DockerClientFactory;

/**
 * 用 Testcontainers 自带的 Docker 探测判断门控；不可用时整类 disabled，
 * reason 固定写明"本机无 Docker，跳过 MQ 集成测试"。
 */
public class DockerAvailableCondition implements ExecutionCondition {

    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        try {
            if (DockerClientFactory.instance().isDockerAvailable()) {
                return ConditionEvaluationResult.enabled("Docker 可用，运行 MQ 集成测试");
            }
            return ConditionEvaluationResult.disabled("本机无 Docker，跳过 MQ 集成测试");
        } catch (Throwable t) {
            return ConditionEvaluationResult.disabled(
                    "本机无 Docker，跳过 MQ 集成测试（" + t.getClass().getSimpleName()
                            + ": " + t.getMessage() + "）");
        }
    }
}