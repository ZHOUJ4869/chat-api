package com.jz.ai.domain.dto;

import lombok.Data;

@Data
public class ChatRequest {
    private String userMessage;
    private String modelName; // qwen3-235b-a22b, qwen3-30b-a3b 等
    private String systemPrompt; // 可选，默认提示词
    private String conversationId;
}

