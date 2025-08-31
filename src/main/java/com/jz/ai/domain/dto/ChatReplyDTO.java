package com.jz.ai.domain.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatReplyDTO {
    /** 回复文本（可能为空；当 silenced=true 时为空） */
    private String text;
    /** 建议延时（毫秒）：前端用于“打字效果/延迟显示” */
    private int delayMs;
    /** 是否因为违规而选择不回复 */
    private boolean silenced;
    // 便捷工厂
    public static ChatReplyDTO silence() {
        return ChatReplyDTO.builder().silenced(true).text("").delayMs(0).build();
    }
    public static ChatReplyDTO reply(String text, int delayMs) {
        return ChatReplyDTO.builder().silenced(false).text(text).delayMs(delayMs).build();
    }
}
