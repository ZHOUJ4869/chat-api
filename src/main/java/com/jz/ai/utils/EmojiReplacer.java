package com.jz.ai.utils;

import java.util.LinkedHashMap;
import java.util.Map;

public class EmojiReplacer {
    private static final Map<String, String> EMOJI_MAP = new LinkedHashMap<>();
    static {
        // 常用客服表情映射（可继续扩充/换皮）
        EMOJI_MAP.put("(微笑)", "😊");
        EMOJI_MAP.put("(笑哭)", "😂");
        EMOJI_MAP.put("(汗)",   "😅");
        EMOJI_MAP.put("(OK)",   "👌");
        EMOJI_MAP.put("(点赞)", "👍");
        EMOJI_MAP.put("(比心)", "💖");
        EMOJI_MAP.put("(鼓掌)", "👏");
        EMOJI_MAP.put("(疑问)", "❓");
        EMOJI_MAP.put("(哭)",   "😭");
        EMOJI_MAP.put("(尴尬)", "😳");
        EMOJI_MAP.put("(生气)", "😠");
        // 可兼容部分 ASCII
        EMOJI_MAP.put(":)", "🙂");
        EMOJI_MAP.put(":D", "😄");
        EMOJI_MAP.put(":P", "😛");
    }

    public static String replace(String text) {
        if (text == null || text.isEmpty()) return text;
        String out = text;
        for (var e : EMOJI_MAP.entrySet()) {
            out = out.replace(e.getKey(), e.getValue());
        }
        return out;
    }
}
