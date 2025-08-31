package com.jz.ai.chat.tokens;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TokenizerConfig {
    @Bean
    public Tokenizer tokenizer() {
        return new QwenHeuristicTokenizer();
    }
}
