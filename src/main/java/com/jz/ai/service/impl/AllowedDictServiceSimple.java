package com.jz.ai.service.impl;


import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jz.ai.mapper.ProductDictMapper;
import com.jz.ai.service.AllowedDictService;
import jakarta.annotation.Nullable;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * 不建额外表：
 * - 允许集合：从 product 表 distinct 后写入 Redis，有 TTL，过期再拉一次。
 * - 同义词：代码里写死一些常见示例（不追求全量）。
 */
@Service
@RequiredArgsConstructor
public class AllowedDictServiceSimple implements AllowedDictService {
    private final ProductDictMapper productDictMapper;
    private final StringRedisTemplate redis;
    private final ObjectMapper om = new ObjectMapper();

    // Redis Key（按需改前缀）
    private static final String KEY_BRANDS     = "rag:dict:brands";
    private static final String KEY_CATEGORIES = "rag:dict:categories";

    // 缓存时长（可改为配置）
    private static final Duration TTL = Duration.ofMinutes(10);

    // —— 写死：常见同义词示例（仅举例，不追求全）——
    private static final Map<String,String> BRAND_SYNONYM = Map.ofEntries(
            Map.entry("联想","lenovo"), Map.entry("拯救者","lenovo"),
            Map.entry("华硕","asus"),   Map.entry("rog","asus"),
            Map.entry("戴尔","dell"),
            Map.entry("惠普","hp"),
            Map.entry("索尼","sony"),
            Map.entry("韶音","shokz"),
            Map.entry("华子","huawei"),
            Map.entry("小米","xiaomi"), Map.entry("红米","xiaomi"),
            Map.entry("乐金","lg")
    );

    private static final Map<String,String> CATEGORY_SYNONYM = Map.ofEntries(
            Map.entry("笔记本","laptop"), Map.entry("游戏本","laptop"), Map.entry("轻薄本","laptop"),
            Map.entry("耳机","headphone"), Map.entry("降噪耳机","headphone"), Map.entry("骨传导","headphone"), Map.entry("蓝牙耳机","headphone"),
            Map.entry("手机","phone"), Map.entry("智能手机","phone"),
            Map.entry("显示器","monitor"), Map.entry("屏幕","monitor"), Map.entry("显示屏","monitor")
    );

    @Override
    public Set<String> allowedBrands() {
        return loadSet(KEY_BRANDS, productDictMapper::distinctBrands);
    }

    @Override
    public Set<String> allowedCategories() {
        return loadSet(KEY_CATEGORIES, productDictMapper::distinctCategories);
    }

    @Override
    public Map<String, String> brandAliasToCanon() {
        return BRAND_SYNONYM;
    }

    @Override
    public Map<String, String> categoryAliasToCanon() {
        return CATEGORY_SYNONYM;
    }

    @Override
    public @Nullable String canonBrand(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String lower = norm(raw);
        // 1) 先看是否本身就是规范名（存在于允许集合）
        if (allowedBrands().contains(lower)) return lower;
        // 2) 再尝试用写死的同义词示例
        String mapped = BRAND_SYNONYM.get(raw);
        if (mapped == null) mapped = BRAND_SYNONYM.get(lower);
        return mapped == null ? null : norm(mapped);
    }

    @Override
    public @Nullable String canonCategory(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String lower = norm(raw);
        if (allowedCategories().contains(lower)) return lower;
        String mapped = CATEGORY_SYNONYM.get(raw);
        if (mapped == null) mapped = CATEGORY_SYNONYM.get(lower);
        return mapped == null ? null : norm(mapped);
    }

    @Override
    public void refresh() {
        // 手动刷新：DB -> Redis
        //如果后续有新增的牌子或者类别需要刷新重建redis
        writeJson(KEY_BRANDS, new ArrayList<>(productDictMapper.distinctBrands()));
        writeJson(KEY_CATEGORIES, new ArrayList<>(productDictMapper.distinctCategories()));
    }

    // =================== Redis/JSON 工具 ===================
    private Set<String> loadSet(String key, Supplier<List<String>> loader) {
        try {
            String s = redis.opsForValue().get(key);
            if (s != null) {
                List<String> list = om.readValue(s, new TypeReference<List<String>>() {});
                return list.stream().map(this::norm)
                        .filter(v -> !v.isEmpty())
                        .collect(Collectors.toCollection(LinkedHashSet::new));
            }
        } catch (Exception ignore) {}
        // 缓存未命中：从 DB 取并写回
        List<String> vals = loader.get().stream()
                .map(this::norm)
                .filter(v -> !v.isEmpty())
                .toList();
        writeJson(key, vals);
        return new LinkedHashSet<>(vals);
    }

    private void writeJson(String key, Object v) {
        try {
            redis.opsForValue().set(key, om.writeValueAsString(v), TTL);
        } catch (Exception ignore) {}
    }

    private String norm(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }
}
