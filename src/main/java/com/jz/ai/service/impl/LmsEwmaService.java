package com.jz.ai.service.impl;


import com.jz.ai.chat.lms.LmsCountersService;
import com.jz.ai.chat.tokens.TokenCounter;
import com.jz.ai.domain.entity.ChatMessage;
import com.jz.ai.mapper.ChatMessageMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class LmsEwmaService {

    private final TokenCounter tokens;
    private final ChatMessageMapper chatMessageMapper;
    private final LmsCountersService counters;

    // 配置项集中在这里，避免散落 Controller
    @Value("${chat.lms.redis.key-prefix:chat:lms:}")
    private String lmsRedisPrefix;
    @Value("${chat.lms.redis.counter-ttl-seconds:604800}")
    private long counterTtlSeconds;
    @Value("${chat.lms.ewma-alpha:0.2}")
    private double ewmaAlpha;
    @Value("${chat.lms.fallback.hist-tokens:120}")
    private int fallbackHistTokens;
    @Value("${chat.lms.fallback.lms-tokens:300}")
    private int fallbackLmsTokens;

    /**
     * 异步更新：基于 DB 取到的最近 R 条历史消息 + 本轮注入的 LMS 列表
     */
    @Async("lmsExecutor") // 可选：若没自定义线程池，去掉括号即可
    public void updateAfterTurnAsync(String chatId, int retrieveSize, List<String> lmsInjected) {
        try {
            updateAfterTurn(chatId, retrieveSize, lmsInjected);
        } catch (Exception e) {
            log.warn("EWMA update failed for chatId={}, err={}", chatId, e.toString());
        }
    }

    /**
     * 同步版本：供测试或链路内直接调用
     */
    public void updateAfterTurn(String chatId, int retrieveSize, List<String> lmsInjected) {
        // 1) 最近 R 条历史消息 → 平均 token
        List<ChatMessage> recent = chatMessageMapper.selectRecentByChatId(chatId, retrieveSize);
        int histAvg = computeHistAvg(recent);

        // 2) 本轮注入的 LMS → 平均 token
        int lmsAvg = computeLmsAvg(lmsInjected);

        // 3) 指数滑动平均写回 Redis
        String ewmaKey = lmsRedisPrefix + "ewma:" + chatId;
        counters.updateEwma(ewmaKey, clampAlpha(ewmaAlpha), histAvg, lmsAvg, counterTtlSeconds);

        if (log.isDebugEnabled()) {
            log.debug("EWMA updated chatId={}, histAvg={}, lmsAvg={}, alpha={}",
                    chatId, histAvg, lmsAvg, ewmaAlpha);
        }
    }

    // —— 工具方法 ——

    private int computeHistAvg(List<ChatMessage> recent) {
        if (recent == null || recent.isEmpty()) return fallbackHistTokens;
        int total = 0;
        for (ChatMessage m : recent) {
            String c = (m.getContent() == null) ? "" : m.getContent();
            total += tokens.ofText(c);
        }
        return Math.max(1, total / recent.size());
    }

    private int computeLmsAvg(List<String> lmsInjected) {
        if (lmsInjected == null || lmsInjected.isEmpty()) return fallbackLmsTokens;
        int total = 0;
        for (String js : lmsInjected) {
            total += tokens.ofJson(js == null ? "" : js);
        }
        return Math.max(1, total / lmsInjected.size());
    }

    private double clampAlpha(double a) {
        if (a < 0.01) return 0.01;
        if (a > 1.0) return 1.0;
        return a;
    }
}
