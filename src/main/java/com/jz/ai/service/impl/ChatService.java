package com.jz.ai.service;
import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.jz.ai.domain.dto.MyChatRequest;
import com.jz.ai.domain.dto.MyChatResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemory;
//import org.springframework.ai.chat.memory.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.messages.Message;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;

import static org.springframework.ai.chat.client.advisor.AbstractChatMemoryAdvisor.CHAT_MEMORY_CONVERSATION_ID_KEY;
import static org.springframework.ai.chat.client.advisor.AbstractChatMemoryAdvisor.CHAT_MEMORY_RETRIEVE_SIZE_KEY;


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
