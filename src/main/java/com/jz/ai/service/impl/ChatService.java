package com.jz.ai.service.impl;
import org.springframework.ai.chat.client.ChatClient;
//import org.springframework.ai.chat.memory.MessageChatMemoryAdvisor;
import org.springframework.stereotype.Service;

import java.util.Map;


@Service
public class ChatService {

    private final Map<String, ChatClient> chatClientMap;

    public ChatService(Map<String, ChatClient> chatClientMap) {
        this.chatClientMap = chatClientMap;
    }

    public String chat(String modelName, String userMessage) {
        ChatClient client = chatClientMap.getOrDefault(modelName, chatClientMap.get("qwen-plus"));
        return client.prompt()
                .user(userMessage)
                .call()
                .content();
    }
}
