// src/main/java/com/jz/ai/chat/rapport/RapportScoreResult.java
package com.jz.ai.chat.rapport;

import lombok.Data;

import java.util.Map;

/**
 * 模型返回的打分结果：
 * - targetScore：0~100 的总分（用于最终平滑）
 * - dimensions：各维 1~5（礼貌、清晰、配合、购买意向、预算明确、互动度等）
 */
@Data
public class RapportScoreResult {
    private int targetScore;                 // 0~100
    private Map<String, Integer> dimensions; // 维度：1~5，例如 {"politeness":4, ...}
}
