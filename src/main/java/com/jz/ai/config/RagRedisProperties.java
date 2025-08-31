// src/main/java/com/jz/ai/rag/RagRedisProperties.java
package com.jz.ai.config;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "rag.redis")
public class RagRedisProperties {
    private String uri;
    private String index;
    private String prefix;
    private boolean initializeSchema = true;
}
