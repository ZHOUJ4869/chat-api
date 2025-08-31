// src/main/java/com/jz/ai/chat/lms/LmsWindowService.java
package com.jz.ai.chat.lms;

import com.jz.ai.domain.entity.ChatMessage;
import com.jz.ai.mapper.ChatLmsCursorMapper;
import com.jz.ai.mapper.ChatLmsMapper;
import com.jz.ai.mapper.ChatMessageMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class LmsWindowService {

    private final StringRedisTemplate redis;
    private final ChatMessageMapper chatMessageMapper;
    private final LmsService lmsService; // 复用你已有的 generateOnce(...)
    private final RedisLockWatchdog lockDog; // 复用你自研看门狗锁（上个回合给的）
    private final ChatLmsCursorMapper chatLmsCursorMapper;

    /** 每当产生一条新消息后调用：检查是否达到一个完整窗口；达到就总结这段最老未总结窗口 */
    @Async("lmsExecutor")
    public void maybeSummarizeNextWindow(String chatId, Long userId, Long agentId,
                                         String redisKeyPrefix, long counterTtlSeconds,
                                         int retrieveSize) {
        // 1) 窗口长度：retrieveSize的一半（向下取整，至少1）
        int windowLen = Math.max(1, retrieveSize / 2);
        String field="last_to_id";
        // 2) 读游标（最后已总结到的消息ID）
        String statKey = redisKeyPrefix + "stat:" + chatId;
        Long lastToId = getLongHash(statKey, field);
        if (lastToId == null) {
            lastToId = chatLmsCursorMapper.selectLastToId(chatId);
            if (lastToId == null) lastToId = 0L;
            redis.opsForHash().put(statKey,  field, String.valueOf(lastToId));
            redis.expire(statKey, Duration.ofSeconds(counterTtlSeconds));
        }
        // 3) 查看游标之后是否已有“完整窗口”的消息条数
        List<ChatMessage> win = chatMessageMapper.selectSinceExclusiveAsc(chatId, lastToId, windowLen);
        if (win.size() < windowLen) {
            // 还不够一个窗口；下次再来
            touchTtl(statKey, counterTtlSeconds);
            return;
        }
        // 4) 分布式锁（避免多实例并发总结同一窗口）
        String lockKey = redisKeyPrefix + "lock:window:" + chatId;
        long ttlMs = 60_000; // 窗口总结通常很快，1分钟够用；如需更久可调大
        var lock = lockDog.tryAcquire(lockKey, ttlMs);
        if (lock == null) return;

        try (lock) {
            lock.startWatchdog(); // 续期看门狗

            // 双检：拿到锁后再查一次，确保仍足够一窗口（并避免重复）
            win = chatMessageMapper.selectSinceExclusiveAsc(chatId, lastToId, windowLen);
            if (win.size() < windowLen) {
                return;
            }

            // 5) 生成这个窗口的摘要（#(lastToId+1) ... #(lastToId+windowLen) 的实际ID区间）
            int fromId = win.get(0).getId().intValue();
            int toId   = win.get(win.size() - 1).getId().intValue();

            lmsService.generateOnce(chatId, userId, agentId, win, fromId, toId); // 注意：generateOnce 会按 raw 顺序组prompt
            // 推进游标（DB + Redis）
            chatLmsCursorMapper.upsert(chatId, toId, win.get(win.size()-1).getCreatedAt()
                    .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(), windowLen);
            // 6) 推进游标：指向本窗口最后一条消息ID
            putLongHash(statKey, "last_to_id", (long) toId);
            touchTtl(statKey, counterTtlSeconds);
        }
    }

    private Long getLongHash(String key, String field) {
        String v = (String) redis.opsForHash().get(key, field);
        if (v != null) {
            return Long.parseLong(v);
        }else{
            return null;
        }
    }
    private void putLongHash(String key, String field, Long val) {
        redis.opsForHash().put(key, field, String.valueOf(val));
    }
    private void touchTtl(String key, long ttlSeconds) {
        redis.expire(key, Duration.ofSeconds(ttlSeconds));
    }
}
