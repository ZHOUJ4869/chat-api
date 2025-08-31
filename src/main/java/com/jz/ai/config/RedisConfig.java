// src/main/java/com/jz/ai/config/RedisConfig.java
package com.jz.ai.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.*;

@Configuration
public class RedisConfig {

    /** 统一用 JSON 序列化，避免 JDK 序列化的可读性差/兼容性差 */
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory cf, ObjectMapper mapper) {
        RedisTemplate<String, Object> t = new RedisTemplate<>();
        t.setConnectionFactory(cf);

        StringRedisSerializer keySer = new StringRedisSerializer();
        GenericJackson2JsonRedisSerializer valSer = new GenericJackson2JsonRedisSerializer(mapper);

        t.setKeySerializer(keySer);
        t.setHashKeySerializer(keySer);
        t.setValueSerializer(valSer);
        t.setHashValueSerializer(valSer);
        t.afterPropertiesSet();
        return t;
    }
}
