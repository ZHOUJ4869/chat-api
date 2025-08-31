package com.jz.ai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@Data
@ConfigurationProperties(prefix = "chat.profile")
public class ProfileProperties {
    private boolean inject = true;
    private String model = "qwen-plus";
    private String redisKeyPrefix = "chat:profile:u:";
    private Duration cacheTtl = Duration.ofDays(30);
    /** 情绪缓存前缀（避免和画像主键冲突） */
    private String moodRedisKeyPrefix = "profile:mood:";
    /** 情绪缓存 TTL（秒），会被强制限制在 <= 1800 秒 */
    private long moodTtlSeconds = 1800; // 默认 30 分钟// 0/负值表示不过期
    // 新增：是否注入情绪到 System（内部提示）
    private boolean injectMood = true;
}
