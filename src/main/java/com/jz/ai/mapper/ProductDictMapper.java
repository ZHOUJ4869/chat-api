// src/main/java/com/jz/ai/rag/ProductDictMapper.java
package com.jz.ai.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 从 product 表里读取去重后的 brand/category。
 * 注意：请把表名/字段名改成你实际的字段（这里假设是 product.brand / product.category）。
 */
@Mapper
public interface ProductDictMapper {

    @Select("""
        SELECT DISTINCT LOWER(TRIM(brand)) AS brand
        FROM product
        WHERE brand IS NOT NULL AND brand <> ''
        """)
    List<String> distinctBrands();

    @Select("""
        SELECT DISTINCT LOWER(TRIM(category)) AS category
        FROM product
        WHERE category IS NOT NULL AND category <> ''
        """)
    List<String> distinctCategories();
}
