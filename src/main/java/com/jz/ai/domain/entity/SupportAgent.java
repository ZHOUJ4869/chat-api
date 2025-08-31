package com.jz.ai.domain.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Data
@TableName(value = "support_agent", autoResultMap = true)
public class SupportAgent {
    @TableId(type = IdType.AUTO)
    private Long id;

    private String code;
    private String name;
    private String gender;
    private LocalDate birthday;

    private String phone;
    private String email;
    private String avatarUrl;

    private String country;
    private String province;
    private String city;
    private String district;
    private String street;
    private String community;
    private String fullAddress;

    private String bachelorSchool;
    private String bachelorMajor;
    private String masterSchool;
    private String masterMajor;
    private String educationLevel;
    private String storeName;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> hobbies;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> interests;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> tags;

    private String bio;

    @TableField("system_prompt")
    private String systemPrompt;

    private Integer isDefault;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
