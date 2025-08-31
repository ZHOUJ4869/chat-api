// src/main/java/com/jz/ai/domain/entity/Product.java
package com.jz.ai.domain.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.math.BigDecimal;

@Data
@TableName("product")
public class Product {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String title;
    private String description;
    private BigDecimal price;
    private Integer stock;
    private String brand;
    private String category;
    private String url;
    @TableField("is_active")
    private Boolean isActive;
}
