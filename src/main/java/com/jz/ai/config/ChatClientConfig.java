package com.jz.ai.config;


import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.client.DefaultChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Configuration
public class ChatClientConfig {

    // 有记忆（默认）
    @Bean
    public Map<String, ChatClient> statefulChatClients(
            @Qualifier("customChatModelMap") Map<String, ChatModel> chatModelMap,
            MessageChatMemoryAdvisor memoryAdvisor
    ) {
        return chatModelMap.entrySet().stream().collect(Collectors.toMap(
                Map.Entry::getKey,
                e -> ChatClient.builder(e.getValue()).defaultAdvisors(memoryAdvisor).build()
        ));
    }

    // 无记忆（不装任何 Advisor）
    @Bean
    @Primary //冲突时优先选它
    public Map<String, ChatClient> statelessChatClients(
            @Qualifier("customChatModelMap") Map<String, ChatModel> chatModelMap
    ) {
        return chatModelMap.entrySet().stream().collect(Collectors.toMap(
                Map.Entry::getKey,
                e -> ChatClient.builder(e.getValue()).build()
        ));
    }

}

