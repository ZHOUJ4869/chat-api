package com.jz.ai.memory;

import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Component
@RequiredArgsConstructor
public class ChatMemoryAdapter implements ConversationMemoryPort {

    private final ChatMemory chatMemory; // 你的配置里应已有 RedisChatMemory/其他实现

    @Override
    public void appendUser(String chatId, String text) {
        if (text == null || text.isEmpty()) return;
        chatMemory.add(chatId, List.of(new UserMessage(text)));
    }

    @Override
    public void appendAssistant(String chatId, String text) {
        if (text == null) text = "";
        chatMemory.add(chatId, List.of(new AssistantMessage(text)));
    }


    /** 原子追加一轮（比默认实现更稳：一次 add() 保证顺序） */
    @Override
    public void appendExchange(String chatId, String userText, String assistantText) {
        if (blank(chatId)) return;
        List<Message> bundle = new ArrayList<>(2);
        if (!blank(userText))      bundle.add(new UserMessage(userText.trim()));
        if (assistantText != null && !assistantText.trim().isEmpty()) {
            bundle.add(new AssistantMessage(assistantText.trim()));
        }
        if (!bundle.isEmpty()) chatMemory.add(chatId, bundle);
    }


    @Override
    public List<Message> fetchRecent(String chatId, int n) {
        return chatMemory.get(chatId,n);
    }

    @Override
    public void clear(String chatId) {
        if (blank(chatId)) return;
        chatMemory.clear(chatId);
    }


    private static boolean blank(String s) {
        return s == null || s.trim().isEmpty();
    }
}