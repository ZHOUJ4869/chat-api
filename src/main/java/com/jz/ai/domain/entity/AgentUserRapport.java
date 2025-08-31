package com.jz.ai.domain.entity;


import com.baomidou.mybatisplus.annotation.*;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@TableName(value = "agent_user_rapport", autoResultMap = true)
public class AgentUserRapport {
    @TableId(type = IdType.AUTO)
    private Long id;

    private Long agentId;
    private Long userId;

    private Integer score;            // 0..100
    private Integer turns;
    private LocalDateTime lastInteractionAt;
    private LocalDateTime lastRecoAt;
    private Integer sinceLastReco;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> meta;
}
