package com.jz.ai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jz.ai.config.BehaviorSignalsProperties;
import com.jz.ai.domain.entity.UserModerationEvent;
import com.jz.ai.mapper.UserModerationEventMapper;
import com.jz.ai.service.BehaviorSignalsService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;

@Service
@RequiredArgsConstructor
public class BehaviorSignalsServiceImpl implements BehaviorSignalsService {

    private final UserModerationEventMapper eventMapper;
    private final BehaviorSignalsProperties props;
    private final ObjectMapper mapper;
    private final StringRedisTemplate redis;

    @Override
    public String buildSignalsJson(Long userId) {
        if (!props.isInject()) return "";

        // --- 0) key & lookback ---
        int daysCfg = Math.max(1, props.getLookbackDays()); // 下限 1 天，避免 0/负数
        final String key = props.getRedisKeyPrefix() + userId + ":" + daysCfg;

        // --- 1) 缓存命中 ---
        if (props.isCacheEnabled()) {
            try {
                String cached = redis.opsForValue().get(key);
                if (cached != null) return cached;
            } catch (Exception ignore) {}
        }

        // --- 2) 查库（仅必要字段） ---
        LocalDateTime since = LocalDateTime.now().minusDays(daysCfg);
        List<UserModerationEvent> list = eventMapper.selectList(
                new LambdaQueryWrapper<UserModerationEvent>()
                        .select(UserModerationEvent::getLevel,
                                UserModerationEvent::getCreatedAt,
                                UserModerationEvent::getCategories)
                        .eq(UserModerationEvent::getUserId, userId)
                        .ge(UserModerationEvent::getCreatedAt, since)
                        .orderByDesc(UserModerationEvent::getCreatedAt)
        );

        if ((list == null || list.isEmpty()) && props.isInjectOnlyIfPresent()) {
            cacheValue(key, ""); // 缓存空串，避免穿透
            return "";
        }

        // --- 3) 聚合 ---
        int cntLight = 0, cntMid = 0, cntHeavy = 0;
        LocalDateTime lastAt = null;
        String lastLevel = null;
        String lastCategory = null; // 从 categories_json 取一个最具代表性的（第一个）

        for (UserModerationEvent e : list) {
            String lvl = String.valueOf(e.getLevel());
            switch (lvl) {
                case "LIGHT" -> cntLight++;
                case "MID"   -> cntMid++;
                case "HEAVY" -> cntHeavy++;
                default -> {} // NONE 或其他不计
            }
            if (lastAt == null || e.getCreatedAt().isAfter(lastAt)) {
                lastAt = e.getCreatedAt();
                lastLevel = lvl;

                // 解析最近一次的类别
                try {
                    List<String> cats = mapper.readValue(
                            Optional.ofNullable(e.getCategories()).orElse("[]"),
                            new TypeReference<List<String>>() {}
                    );
                    if (!cats.isEmpty()) lastCategory = cats.get(0);
                } catch (Exception ignore) { lastCategory = null; }
            }
        }

        boolean hasAny = (cntLight + cntMid + cntHeavy) > 0;

        // --- 4) 组织 JSON ---
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("窗口天数", daysCfg);
        json.put("是否有越界", hasAny);
        json.put("轻度越界次数", cntLight);
        json.put("中度越界次数", cntMid);
        json.put("重度越界次数", cntHeavy);
        if (lastAt != null) {
            json.put("最近一次时间", lastAt.toString());
            if (lastLevel != null)     json.put("最近一次等级", lastLevel);
            if (lastCategory != null)  json.put("最近一次类别", lastCategory);
        }

        String out;
        try { out = mapper.writeValueAsString(json); }
        catch (Exception ex) { out = ""; }

        cacheValue(key, out);
        return out;
    }

    // --- 缓存写入 + 维护索引（支持批量失效） ---
    private void cacheValue(String key, String val) {
        if (!props.isCacheEnabled()) return;
        try {
            long base = Math.max(0, props.getCacheTtlSeconds());
            long jitter = (long) (base * 0.1 * Math.random()); // 0~10% 抖动
            Duration ttl = base == 0 ? null : Duration.ofSeconds(base + jitter);

            if (ttl == null) {
                redis.opsForValue().set(key, val);
            } else {
                redis.opsForValue().set(key, val, ttl);
            }

            // 记录索引，便于按 userId 失效所有天数的缓存
            String idxKey = props.getRedisIndexPrefix() + keyUserPart(key);
            redis.opsForSet().add(idxKey, key);
            // 索引自身过期时间给宽一点
            redis.expire(idxKey, Duration.ofDays(2));
        } catch (Exception ignore) {}
    }

    /** key 形如：{prefix}{userId}:{days} -> 取 {prefix}{userId} */
    private String keyUserPart(String key) {
        int p = key.lastIndexOf(':');
        return p > 0 ? key.substring(0, p) : key;
    }
}
