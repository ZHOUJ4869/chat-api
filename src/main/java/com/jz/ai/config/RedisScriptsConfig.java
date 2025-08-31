package com.jz.ai.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.script.DefaultRedisScript;

@Configuration
public class RedisScriptsConfig {

    /** 原子解锁：只有 token 匹配才 DEL */
    @Bean
    public DefaultRedisScript<Long> unlockScript() {
        var s = new DefaultRedisScript<Long>();
        s.setResultType(Long.class);
        s.setScriptText(
                "if redis.call('get', KEYS[1]) == ARGV[1] then " +
                        "  return redis.call('del', KEYS[1]) " +
                        "else return 0 end"
        );
        return s;
    }

    /** 原子续期：只有 token 匹配才 PEXPIRE 新 TTL(ms) */
    @Bean
    public DefaultRedisScript<Long> renewScript() {
        var s = new DefaultRedisScript<Long>();
        s.setResultType(Long.class);
        s.setScriptText(
                "if redis.call('get', KEYS[1]) == ARGV[1] then " +
                        "  return redis.call('pexpire', KEYS[1], ARGV[2]) " +
                        "else return 0 end"
        );
        return s;
    }
}
