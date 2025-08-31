package com.jz.ai.service.impl;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Service
public class CaptchaService {

    private static final String CAPTCHA_KEY_PREFIX = "captcha:"; // Redis 中的前缀
    private static final long CAPTCHA_EXPIRE_SECONDS = 300; // 5分钟

    private final StringRedisTemplate redisTemplate;

    public CaptchaService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public void saveCaptcha(String sessionId, String code) {
        redisTemplate.opsForValue().set(CAPTCHA_KEY_PREFIX + sessionId, code, CAPTCHA_EXPIRE_SECONDS, TimeUnit.SECONDS);
    }

    public String getCaptcha(String sessionId) {
        return redisTemplate.opsForValue().get(CAPTCHA_KEY_PREFIX + sessionId);
    }

    public void deleteCaptcha(String sessionId) {
        redisTemplate.delete(CAPTCHA_KEY_PREFIX + sessionId);
    }

}

