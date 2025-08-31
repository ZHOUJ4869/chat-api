package com.jz.ai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "chat.moderation.llm")
public class LlmModerationProperties {
    private boolean enabled = true;
    private String model = "qwen-plus";      // 你现有可用模型名
    private long timeoutMs = 1500;           // 超时（由底层HTTP/Client配置保障，作语义提示用）
    private double escalateThreshold = 0.85; // 置信度>=此阈值时采用LLM结果
    private String redisKeyPrefix = "mod:llm:";
    private long cacheTtlSeconds = 86400;    // 结果缓存一天（同文重复命中）
}
