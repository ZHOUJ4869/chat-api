package com.jz.ai.utils;


import java.util.*;

public class MoodDetector {
    private static final Map<String, String> KW = new LinkedHashMap<>();
    static {
        // 开心
        for (String k : List.of("开心","高兴","太好了","不错","棒","赞","哈哈","😁","😂","😄","🙂","👍")) KW.put(k, "开心");
        // 生气
        for (String k : List.of("生气","气死","愤怒","火大","😠","😡")) KW.put(k, "生气");
        // 焦虑
        for (String k : List.of("焦虑","担心","紧张","着急","😟","😰","😥")) KW.put(k, "焦虑");
        // 失望
        for (String k : List.of("失望","无语","唉","算了","难过","郁闷","😭","😞")) KW.put(k, "失望");
        // 激动
        for (String k : List.of("太激动","兴奋","冲动","！！","!！","!!!","😆")) KW.put(k, "激动");
    }
    /** 返回 Optional 情绪；未识别则 empty */
    public static Optional<String> detect(String text) {
        if (text == null || text.isBlank()) return Optional.empty();
        String s = text.trim();
        // 简单权重：匹配越多越靠前
        Map<String,Integer> score = new HashMap<>();
        for (var e : KW.entrySet()) {
            if (s.contains(e.getKey())) score.merge(e.getValue(), 1, Integer::sum);
        }
        return score.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey);
    }
}
