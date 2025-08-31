package com.jz.ai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "chat.behavior-signals")
public class BehaviorSignalsProperties {
    /** 是否注入最近行为信号 */
    private boolean inject = true;
    /** 统计窗口天数（如 30 天） */
    private int lookbackDays = 3;
    /** 仅当有信号时才注入 */
    private boolean injectOnlyIfPresent = true;

    // 缓存相关
    private boolean cacheEnabled = true;
    private long cacheTtlSeconds = 120;
    private String redisKeyPrefix = "behv:signals:";        // value 缓存
    private String redisIndexPrefix = "behv:signals:idx:";  // 索引集合：记录该用户产生过哪些缓存键，便于失效
}