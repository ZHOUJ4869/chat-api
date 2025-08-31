package com.jz.ai.memory;

import lombok.*;

import java.time.Instant;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StoredMessage {
    private String role;                    // user / assistant / system
    private String content;                 // 文本
    @Builder.Default
    private long ts = Instant.now().toEpochMilli();
    private Map<String, Object> meta;       // 可选元数据
}
