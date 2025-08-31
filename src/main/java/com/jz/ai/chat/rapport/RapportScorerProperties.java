// src/main/java/com/jz/ai/chat/rapport/RapportScorerProperties.java
package com.jz.ai.chat.rapport;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 从 application.yml 读取评分相关参数（模型名、阈值等）
 */
@Data
@Component
@ConfigurationProperties(prefix = "rapport.scoring")
public class RapportScorerProperties {
    /** 使用哪个模型做评分（建议稳定、便宜） */
    private String model = "qwen-plus";

    /** 最小触发间隔（秒），避免高频打分 */
    private int minIntervalSeconds = 45;

    /** 至少多少字符才触发模型评分（太短不稳） */
    private int minLengthForModel = 18;

    /** 即便没有关键词，每 N 轮强制评一次（兜底） */
    private int everyNTurns = 5;

    /** EWMA 平滑系数 0~1，越小越稳 */
    private double ewmaBeta = 0.25;

    /** 单轮最大分差（限幅） */
    private int maxDeltaPerTurn = 8;

    /** 死区（小于此差值不变动） */
    private int deadBand = 2;

    /** 越界后冷却期（分钟），限制“突然拉高” */
    private int violationCooldownMinutes = 5;

    /** 冷却期内每轮最多上涨阈值（例如 +2 分） */
    private int cooldownRiseCap = 2;

}
