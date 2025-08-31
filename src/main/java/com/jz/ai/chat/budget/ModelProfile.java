package com.jz.ai.chat;


import lombok.*;

@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class ModelProfile {
    private String name;
    private int contextWindow;         // 模型上下文窗口
    private int outputReserveTokens;   // 为输出预留
}

