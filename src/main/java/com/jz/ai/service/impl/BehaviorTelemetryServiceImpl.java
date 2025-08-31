package com.jz.ai.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jz.ai.config.BehaviorSignalsProperties;
import com.jz.ai.domain.entity.UserModerationEvent;
import com.jz.ai.domain.entity.UserProfile;
import com.jz.ai.guard.BoundaryLevel;
import com.jz.ai.guard.BoundaryVerdict;
import com.jz.ai.guard.ModerationDecision.Action;
import com.jz.ai.mapper.UserModerationEventMapper;
import com.jz.ai.mapper.UserProfileMapper;
import com.jz.ai.service.BehaviorTelemetryService;
import com.jz.ai.utils.UserProfileCache;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

@Service
@RequiredArgsConstructor
public class BehaviorTelemetryServiceImpl implements BehaviorTelemetryService {

    private final UserModerationEventMapper eventMapper;
    private final UserProfileMapper userProfileMapper;
    private final ObjectMapper mapper;
    private final StringRedisTemplate redis;
    private final BehaviorSignalsProperties signalsProps;
    private final UserProfileCache profileCache; // 刷新画像缓存

    // ========= 新接口：推荐使用（含类别/置信度） =========
    @Override
    @Transactional
    public void recordModeration(Long userId,
                                 String chatId,
                                 BoundaryVerdict verdict,
                                 Action action,
                                 String userMessage,
                                 int scoreDelta) {
        // —— 0) 兜底判定 —— //
        if (verdict == null) {
            verdict = BoundaryVerdict.builder()
                    .level(BoundaryLevel.NONE)
                    .confidence(0.0)
                    .categories(Set.of())
                    .reason("null verdict")
                    .build();
        }

        // —— 1) 事件落库 —— //
        String excerpt = (userMessage == null) ? "" :
                (userMessage.length() > 256 ? userMessage.substring(0, 256) : userMessage);

        UserModerationEvent e = new UserModerationEvent();
        e.setUserId(userId);
        e.setChatId(chatId);
        e.setLevel(verdict.getLevel().name());
        e.setAction(action.name());
        e.setScoreDelta(scoreDelta);
        e.setMessageExcerpt(excerpt);
        try {
            e.setCategories(mapper.writeValueAsString(
                    Optional.ofNullable(verdict.getCategories()).orElse(Set.of())
            ));
        } catch (Exception ignore) {
            e.setCategories("[]");
        }
        e.setConfidence(BigDecimal.valueOf(
                Math.max(0, Math.min(1, verdict.getConfidence()))
        ));
        e.setCreatedAt(LocalDateTime.now());
        eventMapper.insert(e);

        // —— 2) 失效行为信号缓存（用于 PromptAssembler 的短缓存） —— //
        invalidateSignalsCache(userId);

        // —— 3) user_profile：原子自增（若不存在则插入），并合并行为标签 —— //
        LocalDateTime now = LocalDateTime.now();

        // 3.1 先尝试对已存在画像做原子自增
        int affected = userProfileMapper.update(
                null,
                new LambdaUpdateWrapper<UserProfile>()
                        .eq(UserProfile::getUserId, userId) // 要求 user_id 上有唯一约束
                        .setSql("violation_count = COALESCE(violation_count, 0) + 1")
                        .set(UserProfile::getLastViolationAt, now)
        );

        // 初始化要并入的行为标签
        List<String> initTags = new ArrayList<>();
        String lvlTag = "boundary_" + verdict.getLevel().name().toLowerCase(Locale.ROOT);
        if (!lvlTag.isBlank()) initTags.add(lvlTag);
        for (String c : Optional.ofNullable(verdict.getCategories()).orElse(Set.of())) {
            if (c != null && !c.isBlank()) initTags.add("cat_" + c);
        }

        UserProfile fresh = null;

        if (affected == 0) {
            // 3.2 数据不存在：插入一条新画像（并发下可能会撞唯一键）
            try {
                UserProfile up = new UserProfile();
                up.setUserId(userId);
                up.setViolationCount(1);
                up.setLastViolationAt(now);
                // 去重保序
                up.setBehaviorTags(new ArrayList<>(new LinkedHashSet<>(initTags)));
                userProfileMapper.insert(up);
                fresh = up;
            } catch (org.springframework.dao.DuplicateKeyException dup) {
                // 3.3 并发插入竞争：回退为查询后再合并标签更新
                fresh = userProfileMapper.selectOne(
                        new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<UserProfile>()
                                .eq(UserProfile::getUserId, userId)
                                .last("LIMIT 1")
                );
                if (fresh != null) {
                    List<String> tags = Optional.ofNullable(fresh.getBehaviorTags()).orElseGet(ArrayList::new);
                    LinkedHashSet<String> set = new LinkedHashSet<>(tags);
                    set.addAll(initTags);
                    fresh.setBehaviorTags(new ArrayList<>(set));
                    userProfileMapper.updateById(fresh);
                }
            }
        } else {
            // 3.4 已存在：查询后合并标签
            fresh = userProfileMapper.selectOne(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<UserProfile>()
                            .eq(UserProfile::getUserId, userId)
                            .last("LIMIT 1")
            );
            if (fresh != null) {
                List<String> tags = Optional.ofNullable(fresh.getBehaviorTags()).orElseGet(ArrayList::new);
                LinkedHashSet<String> set = new LinkedHashSet<>(tags);
                set.addAll(initTags);
                fresh.setBehaviorTags(new ArrayList<>(set));
                userProfileMapper.updateById(fresh);
            }
        }

        // —— 4) 刷新画像 Redis 缓存（提示注入不要包含 violation_count/lastViolationAt） —— //
        if (fresh != null) {
            profileCache.put(userId, profileCache.toPortraitJson(fresh));
        }
    }


    private static void addIfAbsent(List<String> list, String v) {
        if (v == null || v.isBlank()) return;
        if (!list.contains(v)) list.add(v);
    }

    // ========= 缓存失效：行为信号聚合的短缓存 =========
    private void invalidateSignalsCache(Long userId) {
        String idxKey = signalsProps.getRedisIndexPrefix()
                + signalsProps.getRedisKeyPrefix()
                + userId; // 形如 behv:signals:idx:behv:signals:123
        Set<String> keys = redis.opsForSet().members(idxKey);
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
        redis.delete(idxKey);
    }
}
