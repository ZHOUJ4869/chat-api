package com.jz.ai.utils;

public class TypingDelayUtil {

    /**
     * @param replyText    回复文本
     * @param rapportScore 亲密度(0-100)
     * @return 建议延时毫秒（前端据此延迟显示）
     */
    public static int suggestDelayMs(String replyText, int rapportScore) {
        int len = replyText == null ? 0 : replyText.length();

        // 基础打字速度：每秒 ~6 字符（接近真人客服）
        double cps = 5.0;

        // 根据亲密度微调：分高 => 更快（最多 25% 加速）；分低 => 略慢（最多 10% 减速）
        double adjust = (rapportScore - 50) / 100.0; // [-0.5, 0.5]
        double factor = 1.0 - clamp(adjust * 0.5, -0.10, 0.25); // [-10%, +25%]

        double seconds = (len / cps) * factor;

        // 最少 400ms，最多 6.5s，避免过长/过短
        int ms = (int) Math.round(seconds * 1000);
        ms = Math.max(400, Math.min(ms, 6500));

        // 如果只回复很短（如“好的~”），但又不是寒暄，给一个轻量停顿
        if (len < 15) ms = Math.max(ms, 700);

        return ms;
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }
}

