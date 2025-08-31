package com.jz.ai.chat.budget;


import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

@Data
@ConfigurationProperties(prefix = "chat.models")
public class ModelRegistryProperties {
    private Map<String, ModelProfile> registry;
}

