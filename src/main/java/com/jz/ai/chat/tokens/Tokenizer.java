package com.jz.ai.chat.tokens;


public interface Tokenizer {
    int countText(String text);
    default int countJson(String json) {
        if (json == null || json.isEmpty()) return 0;
        // JSON 更密集：2.2 chars ~= 1 token（经验值）
        return Math.max(1, (int)Math.round(json.length() / 2.2));
    }
}

