package com.jz.ai.chat.prompt;


import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.jz.ai.chat.budget.BudgetService;
import com.jz.ai.chat.lms.LmsCountersService;
import com.jz.ai.chat.lms.LmsService;
import com.jz.ai.chat.tokens.TokenCounter;
import com.jz.ai.config.BehaviorSignalsProperties;
import com.jz.ai.config.ProfileProperties;
import com.jz.ai.service.BehaviorSignalsService;
import com.jz.ai.utils.UserProfileCache;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class PromptAssembler {
    private final UserProfileCache profileCache;
    private final ProfileProperties profileProps;
    private final LmsService lmsService;
    private final LmsCountersService counters;
    private final TokenCounter tokens;
    private final BudgetService budget;
    private final BehaviorSignalsService behaviorSignalsService;
    private final BehaviorSignalsProperties behaviorSignalsProps;

    // 允许注入的情绪白名单
    private static final Set<String> ALLOWED_MOODS = Set.of("开心","平静","焦虑","生气","失望","激动");

    // 为了示例，热卖商品/情感风格先由调用方传入
    public static record AsmResult(String systemPrompt, BudgetService.LmsBudget lmsBudget, List<String> lmsInjected  ) {}

    public AsmResult build(String model, Long userId, String chatId,
                           String baseSys,
                           String hotProductsJson,
                           String retrievedProductsJson,
                           String toneHint,
                           int retrieveSize,
                           String userMsg,
                           double softCapRatio,
                           int fallbackHistTok,
                           int targetLmsTok,
                           String redisKeyPrefix) throws Exception {

        // 画像注入（你的实现已存在）
        String sProfileJson = "";
        if (profileProps.isInject()) {
            var portraitFull = profileCache.getOrLoad(userId);
            var injectView = profileCache.buildInjectView(portraitFull);
            if (!injectView.isEmpty()) {
                sProfileJson = JsonMapper.builder().build()
                        .writeValueAsString(injectView);
            }
        }

        String ewmaKey = redisKeyPrefix + "ewma:" + chatId;

        int tHistAvg = counters.getAvgHistTokens(ewmaKey, fallbackHistTok);
        // LMS 的平均 token（读不到就用 targetLmsTok 作为回退）
        int tLmsTarget = counters.getAvgLmsTokens(ewmaKey, targetLmsTok);
        var bdg = budget.compute(model, baseSys, sProfileJson, hotProductsJson, toneHint,
                retrievedProductsJson, retrieveSize, tHistAvg, tLmsTarget, userMsg, softCapRatio);
        //mood没多少不计入
        //nLmsMax 是理论上最大可用摘要数，soft都是保险起见的摘要数，tHist是短期上下文tokens/message ttarget是长期上下文
        // 取 LMS 软上限数量
        List<String> lmsJsonList = lmsService.fetchRecentForPrompt(chatId, bdg.getNLmsSoftCap());

        String sys = baseSys;
        if (!sProfileJson.isEmpty()) {
            sys += "\n【用户画像（仅供参考，勿直述/背诵）】" + sProfileJson;
        }
/*        String behaviorJson = behaviorSignalsService.buildSignalsJson(userId);
        if (behaviorSignalsProps.isInject() && !behaviorJson.isEmpty()) {
            sys += "\n【客服内部行为信号（仅供风格调节，严禁向用户直述或提及）】" + behaviorJson;
        }*/

        // 2.2 短期“情绪”注入（读取 Redis，仅内部提示，命中才注）
        if (profileProps.isInjectMood()) {
            String moodKey = profileProps.getMoodRedisKeyPrefix() + userId;
            Optional<String> raw = profileCache.getRaw(moodKey);
            if (raw.isPresent()) {
                try {
                    JsonNode node = JsonMapper.builder().build().readTree(raw.get());
                    String mood = node.has("情绪") ? node.get("情绪").asText() : null;
                    if (mood != null && ALLOWED_MOODS.contains(mood)) {
                        // 仅内部风格调节，不得向用户直述“你在生气”等
                        sys += "\n【客户短期情绪（仅供风格调节，严禁向用户直述或提及）】{\"情绪\":\"" + mood + "\"}";
                        // 可选：根据情绪加一点点风格提示（不直述）
                        // if ("生气".equals(mood) || "失望".equals(mood) || "焦虑".equals(mood)) {
                        //     sys += "\n（情绪适配提示）优先给方案与下一步，减少表情与感叹号，必要时先致歉再处理。";
                        // }
                    }
                } catch (Exception ignore) { /* 不阻断主流程 */ }
            }
        }
        if (hotProductsJson != null && !hotProductsJson.isEmpty()) {
            sys += "\n【热卖商品简要】" + hotProductsJson;
        }
        // ★ 本轮「向量检索参考商品」
        if (retrievedProductsJson != null && !retrievedProductsJson.isEmpty()) {
            // 明确规范：仅将其作为证据参考，优先依据证据，不足时要说明信息不足，禁止编造
            sys += "\n【参考商品（向量检索）】如需要商品信息，请优先依据以下参考资料回答，若资料不足请直说不足，勿编造。\n" + retrievedProductsJson;
        }
        if (toneHint != null && !toneHint.isEmpty()) {
            sys += "\n（风格提示）" + toneHint;
        }

        if (!lmsJsonList.isEmpty()) {
            sys += "\n【长期记忆摘要（结构化，不要逐字复述）】\n" + String.join("\n", lmsJsonList);
        }


        return new AsmResult(sys, bdg,lmsJsonList);
    }
}

