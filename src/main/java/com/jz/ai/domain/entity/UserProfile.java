package com.jz.ai.domain.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Data
@TableName(value = "user_profile", autoResultMap = true)
public class UserProfile {
    @TableId(type = IdType.INPUT)
    private Long userId;

    // 基础身份
    private String name;
    private String gender;       // male/female/other/unknown
    private LocalDate birthday;
    private Integer ageYears;    // 当只有“年龄”表述时可先存这里

    // 联系与地址（谨慎注入到模型）
    private String phone;
    private String email;
    private String city;
    private String district;
    private String address;

    // 偏好/忌避
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> preferences;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> dislikes;

    private String budgetRange;

    // 家庭/生活
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> familyInfo; // {"人数":3,"是否有儿童":true,"是否有老人":false}
    private String maritalStatus;     // single/married/divorced/widowed/unknown
    private Boolean hasChildren;
    private Integer childrenCount;

    // 教育/职业/收入/居住
    private String occupation;        // 岗位
    private String industry;          // 行业
    private String employer;          // 雇主
    private String educationLevel;    // 高中/大专/本科/研究生/博士/其他
    private String incomeRange;       // 5k-10k/10k-20k/20k+
    private String livingStatus;      // 自有房/租房/与父母同住/宿舍
    private String residenceType;     // 公寓/别墅/合租
    private Integer homeAreaSqm;      // 面积

    private Boolean petOwner;
    private Boolean smartHomeIntent;

    // 品牌/品类/过敏
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> preferredBrands;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> preferredCates;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> allergies;

    private String note;

    // ===== 行为聚合 =====
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> behaviorTags;

    private Integer violationCount;
    private LocalDateTime lastViolationAt;


    // 时间戳（建议交给 DB；若无 MetaObjectHandler，就用 NEVER）
    @TableField(value = "created_at", insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime createdAt;
    @TableField(value = "updated_at", insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime updatedAt;
}
