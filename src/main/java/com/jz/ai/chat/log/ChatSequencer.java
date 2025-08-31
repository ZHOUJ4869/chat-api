package com.jz.ai.chat.log;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ChatSequencer {
    private final StringRedisTemplate redis;

    public long next(String chatId) {
        // key 例：chat:seq:ua-123-456
        return redis.opsForValue().increment("chat:seq:" + chatId);
    }
}