package com.jz.ai.chat.tokens;


import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class TokenCounter {
    private final Tokenizer tokenizer; // 通过配置注入

    public int ofText(String s) { return tokenizer.countText(s); }
    public int ofJson(String json) { return tokenizer.countJson(json); }

    /** 连接多个文本时，避免重复分隔符；这里简单拼接后估算。*/
    public int ofConcat(String... parts) {
        if (parts == null) return 0;
        StringBuilder sb = new StringBuilder();
        for (String p: parts) if (p!=null && !p.isEmpty()) sb.append(p).append('\n');
        return tokenizer.countText(sb.toString());
    }
}

