package com.jz.ai.domain.entity;


import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("user_moderation_event")
public class UserModerationEvent {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;
    private String chatId;

    /** LIGHT/MID/HEAVY */
    private String level;

    /** SILENCE/BOUNDARY_REPLY */
    private String action;

    private Integer scoreDelta;

    private String categories;
    private BigDecimal confidence;
    /** 仅存前 256 字截断文本 */
    private String messageExcerpt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
