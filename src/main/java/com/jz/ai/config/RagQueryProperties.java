package com.jz.ai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "rag.query")
@Data
public class RagQueryProperties {
    private int expandNum = 3;
    private int perQueryTopk = 4;
    private double simThreshold = 0.5;
    private int finalTopk = 6;
}
