package com.example.aimindmap.agenttracing;

import com.example.aimindmap.agentmemory.AgentMemoryProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 注册 Agent 受控限制配置。 */
@Configuration
@EnableConfigurationProperties({AgentTracingProperties.class, AgentMemoryProperties.class})
public class AgentTracingConfig {
}
