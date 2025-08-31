package com.jz.ai.utils;


import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jz.ai.domain.entity.AgentUserRapport;
import com.jz.ai.mapper.AgentUserRapportMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;

@Repository
@RequiredArgsConstructor
public class RapportCacheRepository {

    private final RedisTemplate<String, Object> redis;
    private final AgentUserRapportMapper mapper;

    private static final String KEY_FMT = "rapport:%d:%d"; // rapport:agentId:userId
    private static final Duration TTL = Duration.ofHours(24);

    private String key(Long agentId, Long userId) {
        return String.format(KEY_FMT, agentId, userId);
    }

    /** 读：优先缓存；未命中回源 DB；DB 也没有则在内存构造一个默认对象，直接缓存（写库走异步批） */
    public AgentUserRapport getOrInit(Long agentId, Long userId) {
        String k = key(agentId, userId);
        Object v = redis.opsForValue().get(k);
        if (v instanceof AgentUserRapport r) return r;

        AgentUserRapport db = mapper.selectOne(
                new LambdaQueryWrapper<AgentUserRapport>()
                        .eq(AgentUserRapport::getAgentId, agentId)
                        .eq(AgentUserRapport::getUserId, userId)
                        .last("LIMIT 1")
        );

        if (db == null) {
            db = new AgentUserRapport();
            db.setAgentId(agentId);
            db.setUserId(userId);
            db.setScore(50);
            db.setTurns(0);
            db.setSinceLastReco(999);
            db.setLastInteractionAt(java.time.LocalDateTime.now());
            // 注意：此处不立即 insert DB，交给异步持久化
        }

        redis.opsForValue().set(k, db, TTL);
        return db;
    }

    /** 写缓存：每次改对象后刷新缓存（延长有效期） */
    public void putCache(AgentUserRapport r) {
        redis.opsForValue().set(key(r.getAgentId(), r.getUserId()), r, TTL);
    }

    public void evict(Long agentId, Long userId) {
        redis.delete(key(agentId, userId));
    }
}
