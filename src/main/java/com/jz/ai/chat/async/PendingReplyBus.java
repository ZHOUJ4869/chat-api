// src/main/java/com/jz/ai/chat/async/PendingReplyBus.java
package com.jz.ai.chat.async;

import com.jz.ai.domain.dto.ChatReplyDTO;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 生成好的客服回复临时放这里；前端 /api/chat/pull 拉走。
 */
@Component
public class PendingReplyBus {

    private final Map<String, Queue<ChatReplyDTO>> box = new ConcurrentHashMap<>();

    public void push(String chatId, ChatReplyDTO dto) {
        box.computeIfAbsent(chatId, k -> new ConcurrentLinkedQueue<>()).add(dto);
    }

    public List<ChatReplyDTO> pull(String chatId, int max) {
        Queue<ChatReplyDTO> q = box.getOrDefault(chatId, new ConcurrentLinkedQueue<>());
        List<ChatReplyDTO> out = new ArrayList<>();
        for (int i = 0; i < Math.max(1, max); i++) {
            ChatReplyDTO d = q.poll();
            if (d == null) break;
            out.add(d);
        }
        return out;
    }
}
