package com.jz.ai.service;

public interface ChatLogService {
    void persistExchangeAsync(String chatId, Long userId,
                              String userText, String assistantText,
                              String modelName, Integer latencyMs);
    void persistUserAsync(String chatId, Long userId, String userText,long ts,long seq);
    void persistAssistantAsync(String chatId, Long userId, String assistantText, String modelName, Integer latencyMs,long ts,long seq);
}

