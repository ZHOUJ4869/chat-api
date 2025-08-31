package com.jz.ai.domain.dto;

import lombok.*;
import java.util.List;

@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class ChatDayDTO {
    /** 分组展示用的日期标题，例如 2025-08-16（需要“今天/昨天”可以前端处理或后端自定义） */
    private String dateLabel;
    private List<ChatMessageDTO> messages;
}