package com.jz.ai.service.impl;


import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.jz.ai.domain.entity.ChatMessage;
import com.jz.ai.mapper.ChatMessageMapper;
import com.jz.ai.service.ChatLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatLogServiceImpl extends ServiceImpl<ChatMessageMapper, ChatMessage>
        implements ChatLogService {

    private static LocalDateTime toLdt(long ts) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(ts), ZoneId.systemDefault());
    }
    @Async("chatAsyncExecutor")
    @Transactional(rollbackFor = Exception.class)
    @Override
    public void persistExchangeAsync(String chatId, Long userId,
                                     String userText, String assistantText,
                                     String modelName, Integer latencyMs) {
        try {
            // 用户消息
            ChatMessage u = ChatMessage.builder()
                    .chatId(chatId).userId(userId)
                    .role("user").content(userText)
                    .build();
            this.save(u);

            // 助手消息
            ChatMessage a = ChatMessage.builder()
                    .chatId(chatId).userId(userId)
                    .role("assistant").content(assistantText)
                    .modelName(modelName).latencyMs(latencyMs)
                    .build();
            this.save(a);
        } catch (Exception e) {
            log.error("persist chat exchange failed, chatId={}, userId={}, err={}",
                    chatId, userId, e.getMessage(), e);
            throw e;
        }
    }
    @Async("chatAsyncExecutor")
    @Transactional(rollbackFor = Exception.class)
    @Override
    public void persistUserAsync(String chatId, Long userId, String userText,long ts,long seq){
        try {
            // 用户消息
            ChatMessage u = ChatMessage.builder()
                    .chatId(chatId).userId(userId)
                    .role("user").content(userText)
                    .seq(seq).createdAt(toLdt(ts))
                    .build();
            this.save(u);
        } catch (Exception e) {
            log.error("persist chat exchange failed, chatId={}, userId={}, err={}",
                    chatId, userId, e.getMessage(), e);
            throw e;
        }
    }


    @Async("chatAsyncExecutor")
    @Transactional(rollbackFor = Exception.class)
    @Override
    public void persistAssistantAsync(String chatId, Long userId, String assistantText, String modelName, Integer latencyMs
    ,long ts,long seq){
        try {
            // 助手消息
            ChatMessage a = ChatMessage.builder()
                    .chatId(chatId).userId(userId)
                    .role("assistant").content(assistantText)
                    .modelName(modelName).latencyMs(latencyMs)
                    .seq(seq)
                    .createdAt(toLdt(ts))
                    .build();
            this.save(a);
        } catch (Exception e) {
            log.error("persist chat exchange failed, chatId={}, userId={}, err={}",
                    chatId, userId, e.getMessage(), e);
            throw e;
        }
    }
}
