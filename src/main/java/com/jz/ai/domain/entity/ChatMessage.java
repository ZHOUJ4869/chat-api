// src/main/java/com/jz/ai/chat/dal/entity/ChatMessage.java
package com.jz.ai.domain.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.*;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("chat_message")
public class ChatMessage {

    @TableId(type = IdType.AUTO)            // 若你想用雪花：IdType.ASSIGN_ID
    private Long id;

    private String chatId;                   // "u:{userId}"
    private Long userId;

    private String role;                     // user/assistant/system/tool
    private String content;

    private String modelName;                // assistant 使用的模型名
    private Integer latencyMs;               // 可选

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    private Long seq;
}
