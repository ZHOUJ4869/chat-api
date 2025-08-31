package com.jz.ai.utils;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class EmoteNormalizer {
    private EmoteNormalizer(){}

    /** 只匹配括号占位：(微笑)/( OK )/（汗）等；不影响普通文字 */
    private static final Pattern PAREN_TOKEN =
            Pattern.compile("[（(]\\s*([A-Za-z\\u4e00-\\u9fa5]{1,6})\\s*[)）]");

    /** 每条消息最多替换的表情数量 */
    private static final int DEFAULT_MAX = 2;

    /** 单个占位被“删除表情”的概率（0.5=50%） */
    private static final double DROP_PROBABILITY = 0.70;

    /** 占位词 -> 候选 emoji 列表（可多选；等概率随机） */
    private static final Map<String, List<String>> EMOJI_POOL = new LinkedHashMap<>();
    static {
        EMOJI_POOL.put("微笑", List.of("😊"));           // (微笑)
        EMOJI_POOL.put("笑哭", List.of("😂"));           // (笑哭)
        EMOJI_POOL.put("汗",   List.of("😅","😓"));      // (汗) 适度扩充
        EMOJI_POOL.put("OK",   List.of("👌"));           // (OK)
        EMOJI_POOL.put("点赞", List.of("👍"));           // (点赞)
        EMOJI_POOL.put("比心", List.of("💖"));          // (比心)
        EMOJI_POOL.put("鼓掌", List.of("👏"));           // (鼓掌)

        // 重要：不再使用 ❓，改为在 🤔 / 🧐 中随机
        EMOJI_POOL.put("疑问", List.of("🤔","🧐"));      // (疑问)

        EMOJI_POOL.put("哭",   List.of("😭"));           // (哭)
        EMOJI_POOL.put("尴尬", List.of("😳","😅"));      // (尴尬)
        EMOJI_POOL.put("生气", List.of("😠","😤"));      // (生气)
    }

    /** 便捷重载：最多替换 DEFAULT_MAX 个，50% 概率删除 */
    public static String emojify(String text) {
        return emojify(text, DEFAULT_MAX, DROP_PROBABILITY);
    }

    /** 每条消息最多替换 maxPerMessage 个；按 dropProb 概率“删除表情”；未知或超限则把整个括号占位删掉 */
    public static String emojify(String text, int maxPerMessage, double dropProb) {
        if (text == null || text.isEmpty()) return text;

        int limit = Math.max(0, maxPerMessage);
        double p = clamp01(dropProb);

        Matcher m = PAREN_TOKEN.matcher(text);
        StringBuffer sb = new StringBuffer();
        int used = 0;

        while (m.find()) {
            String key = m.group(1).trim();
            List<String> pool = EMOJI_POOL.get(key);

            if (pool != null && used < limit) {
                // 50% 概率直接删除该表情（降低整体表情频率）
                if (ThreadLocalRandom.current().nextDouble() < p) {
                    m.appendReplacement(sb, ""); // 删除整个占位
                } else {
                    String emoji = pick(pool);
                    used++;
                    m.appendReplacement(sb, Matcher.quoteReplacement(emoji));
                }
            } else {
                // 未知占位或超过上限：直接删掉括号占位，避免出现“(随便写)”之类
                m.appendReplacement(sb, "");
            }
        }
        m.appendTail(sb);
        return sb.toString().replaceAll("\\s{2,}", " ").trim();
    }

    private static String pick(List<String> pool) {
        if (pool == null || pool.isEmpty()) return "";
        int i = ThreadLocalRandom.current().nextInt(pool.size());
        return pool.get(i);
    }

    private static double clamp01(double v) {
        if (v < 0) return 0;
        if (v > 1) return 1;
        return v;
    }
}
