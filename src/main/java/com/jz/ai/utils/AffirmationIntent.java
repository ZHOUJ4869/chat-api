// com.jz.ai.utils.AffirmationIntent.java
package com.jz.ai.utils;

import java.util.regex.Pattern;
import java.util.regex.Matcher;

public final class AffirmationIntent {
    private AffirmationIntent(){}

    // 纯肯定（允许少量尾词）
    private static final Pattern YES = Pattern.compile(
            "^(好(的|呀|啊)?|可以|行|ok|OK|嗯(呢|嗯)?|没问题|要|继续|是的|对|可以的|妥了|安排)$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );

    // 常见否定词（允许尾词/礼貌词/标点），尽量覆盖真实对话
    private static final Pattern NO = Pattern.compile(
            "(?:^|\\s)(?:不|不用|不用了|不了|不了啦?|先这样|暂时不(?:需要)?|等会(?:吧)?|先不(?:了)?|算了(?:吧)?|不要(?:了)?|不需要(?:了)?|不用麻烦了|不麻烦了|改天吧|下次吧|先看看|先观望|no(?:pe)?)" +
                    "(?:[\\p{Punct}\\s·•，。！？!?～~]*谢谢)?(?:\\s|$)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );

    // “上一轮为提议/确认型”问题
    private static final Pattern OFFER = Pattern.compile(
            "(需要我|要不要|要我.*(?:介绍|推荐|看看)|继续(?:看看|介绍|了解)|是否|还要.*吗|要不要继续|要我安排).*?$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );

    public static boolean isYes(String s){
        return s != null && YES.matcher(s.trim()).matches();
    }

    /** 更宽松的否定：句首/前若干字符出现否定意图即可 */
    public static boolean isNo(String s){
        if (s == null) return false;
        // 先保留原样判断，再做一次“去掉常见标点”的查找
        String t = s.trim();
        // 允许前置少量礼貌/语气符号后出现否定词
        Matcher m = NO.matcher(t);
        if (m.find()) {
            // 否定词出现在开头附近（例如前 0~6 个字符），认为是拒绝
            return m.start() <= 6;
        }
        // 再做一次“轻清洗”后重试
        String norm = t.replaceAll("[\\s\\p{Punct}·•，。！？!?～~]+", "");
        m = NO.matcher(norm);
        return m.find() && m.start() <= 4;
    }

    public static boolean lastIsOffer(String lastAssistantText){
        return lastAssistantText != null &&
                OFFER.matcher(lastAssistantText.replaceAll("\\s+","")).find();
    }
}
