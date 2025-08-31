package com.jz.ai.common;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class CaptchaCache {
    // 用于临时缓存验证码 key=sessionId, value=code
    public static final Map<String, String> CAPTCHA_MAP = new ConcurrentHashMap<>();
}
