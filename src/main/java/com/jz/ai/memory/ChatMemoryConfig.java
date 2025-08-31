package com.jz.ai.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jz.ai.mapper.ChatMessageMapper;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.Map;
import java.util.stream.Collectors;

@Configuration
public class ChatMemoryConfig {

    @Bean
    @ConfigurationProperties(prefix = "chat.memory")
    public Props chatMemoryProps() { return new Props(); }

    @Bean
    public ChatMemory chatMemory(StringRedisTemplate redis, ObjectMapper mapper, Props p, ChatMessageMapper chatMessageMapper) {
        return new RedisChatMemoryImpl(
                redis,
                mapper,
                p.getKeyPrefix(),
                p.getMaxMessages(),
                Duration.ofSeconds(p.getTtlSeconds()),
                chatMessageMapper
        );
    }

    @Bean
    public MessageChatMemoryAdvisor memoryAdvisor(ChatMemory chatMemory) {
        return new MessageChatMemoryAdvisor(chatMemory);
    }

    /* 外置配置 */
    public static class Props {
        private String keyPrefix = "chat:mem:";
        private int maxMessages = 50;
        private long ttlSeconds = 7 * 24 * 3600;

        public String getKeyPrefix() { return keyPrefix; }
        public void setKeyPrefix(String keyPrefix) { this.keyPrefix = keyPrefix; }
        public int getMaxMessages() { return maxMessages; }
        public void setMaxMessages(int maxMessages) { this.maxMessages = maxMessages; }
        public long getTtlSeconds() { return ttlSeconds; }
        public void setTtlSeconds(long ttlSeconds) { this.ttlSeconds = ttlSeconds; }
    }
}
