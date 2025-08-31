package com.jz.ai.chat.budget;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class ModelRegistry {
    private final ModelRegistryProperties props;
    private final Map<String, ModelProfile> profiles = new HashMap<>();

    @PostConstruct
    public void init() {
        if (props.getRegistry() != null) {
            props.getRegistry().forEach((k, v) -> {
                v.setName(k);
                profiles.put(k, v);
            });
        }
    }

    public ModelProfile get(String model) {
        ModelProfile p = profiles.get(model);
        if (p == null) {
            // 默认兜底 128K
            p = ModelProfile.builder()
                    .name(model).contextWindow(131072).outputReserveTokens(32768).build();
        }
        return p;
    }
}

