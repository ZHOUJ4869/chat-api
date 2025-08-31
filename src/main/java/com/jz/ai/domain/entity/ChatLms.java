package com.jz.ai.domain.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("chat_lms")
public class ChatLms {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long agentId;
    private String chatId;
    private Long windowFromTs;
    private Long windowToTs;
    private Long turnsFromId;
    private Long turnsToId;
    private Integer tokensEst;
    private String summaryJson;
    private String origin;      // ← 新增：RAW 或 MERGED

    private Integer compacted;  // 0=活跃, 1=归档/淘汰
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
