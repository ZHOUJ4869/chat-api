// src/main/java/com/jz/ai/mapper/ProductMapper.java
package com.jz.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jz.ai.domain.entity.Product;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ProductMapper extends BaseMapper<Product> {}
