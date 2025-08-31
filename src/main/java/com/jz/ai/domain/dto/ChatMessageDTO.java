package com.jz.ai.domain.dto;

import lombok.*;

@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class ChatMessageDTO {
    /** user / ai / system */
    private String from;
    private String text;
    /** 毫秒时间戳 */
    private long ts;
}