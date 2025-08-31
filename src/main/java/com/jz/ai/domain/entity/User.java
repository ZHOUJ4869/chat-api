package com.jz.ai.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("user")
public class User {
    @TableId(type = IdType.AUTO)
    private Long id;

    private String username;           // 用户名
    private String password;           // 密码（加密存储）
    private Boolean isAdmin;           // 是否为管理员
    private Long lastCustomerId;       // 上一次聊天客服ID
    private LocalDateTime lastChatTime;// 上次聊天时间
}
