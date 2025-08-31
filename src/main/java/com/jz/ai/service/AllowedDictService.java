// src/main/java/com/jz/ai/rag/AllowedDictService.java
package com.jz.ai.service;

import jakarta.annotation.Nullable;

import java.util.Map;
import java.util.Set;

/**
 * 提供允许集合与归一化能力（品牌/类目）。
 * - 允许集合来自 product 表并缓存到 Redis（无额外表）
 * - 同义词只在代码里写死少量示例
 */
public interface AllowedDictService {

    /** 允许的品牌规范名集合（统一小写） */
    Set<String> allowedBrands();

    /** 允许的类目规范名集合（统一小写） */
    Set<String> allowedCategories();

    /** 写死的一些“别名→规范名”示例（品牌），键和值不保证都小写；仅用于展示给模型 */
    Map<String, String> brandAliasToCanon();

    /** 写死的一些“别名→规范名”示例（类目） */
    Map<String, String> categoryAliasToCanon();

    /** 归一化：输入任意大小写/中文/别名，输出规范名或 null */
    @Nullable
    String canonBrand(String raw);

    /** 归一化：输入任意大小写/中文/别名，输出规范名或 null */
    @Nullable
    String canonCategory(String raw);

    /** 手动刷新：从 DB 重建 Redis 缓存 */
    void refresh();
}
