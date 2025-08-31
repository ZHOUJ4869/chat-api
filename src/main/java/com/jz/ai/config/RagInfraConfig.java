// src/main/java/com/jz/ai/config/RagInfraConfig.java
package com.jz.ai.config;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.JedisPooled;

@Configuration
@RequiredArgsConstructor
public class RagInfraConfig {

    @Value("${spring.data.redis.host}")
    private String host;
    @Value("${spring.data.redis.port}")
    private int port;
    @Value("${spring.data.redis.password}")
    private String password;

    @Bean
    public JedisPooled jedisPooled() {
        // 简单直连；生产可改为 JedisPoolConfig 或 Redis Sentinel/Cluster
        if (password == null || password.isBlank()) {
            return new JedisPooled(host, port);
        }
        return new JedisPooled(
                new HostAndPort(host, port),
                DefaultJedisClientConfig.builder()
                        .user("default")       // Redis 6+ ACL 用户名，通常就是 "default"
                        .password(password)
                        .build()
        );
    }
}
