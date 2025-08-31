package com.jz.ai.chat.budget;

import com.jz.ai.chat.tokens.TokenCounter;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class BudgetService {

    private final TokenCounter tokens;
    private final ModelRegistry modelRegistry;

    @Data @Builder
    public static class LmsBudget {
        private int nLmsMax;
        private int nLmsSoftCap;
        private int availableInput;    // W - reserve
        private int usedBeforeLms;     // 已占用（不含LMS）
        private int roomForLms;        // 可用于LMS
        private int tHistAvg;          // 实测或估算
        private int tLmsTarget;        // 目标每条摘要 tokens
    }

    /**
     * @param model 当前模型名
     * @param sBase 基础系统提示
     * @param sProfile 用户画像JSON
     * @param sHot 商品简要JSON
     * @param sTone 情感/风格提示
     * @param retrieveSize 本轮Redis取出的历史条数R
     * @param tHistAvg 实测平均每条历史消息tokens（若没有就给估算值）
     * @param tLmsTarget 目标每条LMS tokens（例如 300）
     * @param userMsg 本轮用户消息
     */
    public LmsBudget compute(String model,
                             String sBase, String sProfile, String sHot, String sTone,
                             String sRetrieved,     // 👈 新增：向量检索结果 JSON
                             int retrieveSize,
                             int tHistAvg, int tLmsTarget,
                             String userMsg,
                             double softCapRatio) {
        ModelProfile prof = modelRegistry.get(model);
        int W = prof.getContextWindow();
        int reserve = prof.getOutputReserveTokens();
        int available = W - reserve;

        int used = tokens.ofText(sBase)
                + tokens.ofJson(sProfile)
                + tokens.ofJson(sHot)
                + tokens.ofText(sTone)
                + tokens.ofJson(sRetrieved)// 👈 命中才有，未命中=0
                + (retrieveSize * Math.max(1, tHistAvg))
                + tokens.ofText(userMsg);

        int room = Math.max(0, available - used);
        int nMax = Math.max(0, room / Math.max(1, tLmsTarget));
        int nSoft = (int)Math.floor(nMax * softCapRatio);

        return LmsBudget.builder()
                .nLmsMax(nMax)
                .nLmsSoftCap(nSoft)
                .availableInput(available)
                .usedBeforeLms(used)
                .roomForLms(room)
                .tHistAvg(tHistAvg)
                .tLmsTarget(tLmsTarget)
                .build();
    }
}

