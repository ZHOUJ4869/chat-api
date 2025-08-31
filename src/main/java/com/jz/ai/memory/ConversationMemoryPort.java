package com.jz.ai.memory;

import org.springframework.ai.chat.messages.Message;

import java.util.List;

public interface ConversationMemoryPort {
    void appendUser(String chatId, String text);
    void appendAssistant(String chatId, String text);
    /** 原子写入一轮（避免写入顺序被并发打乱） */
    default void appendExchange(String chatId, String userText, String assistantText) {
        appendUser(chatId, userText);
        appendAssistant(chatId, assistantText);
    }

    /**
     * 读取最近 n 条（不足 n 条则全返回）
     */
    List<Message> fetchRecent(String chatId, int n);

    /** 清空会话记忆 */
    void clear(String chatId);

}