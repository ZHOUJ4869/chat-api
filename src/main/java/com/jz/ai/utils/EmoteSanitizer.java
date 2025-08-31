package com.jz.ai.utils;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 表情后处理（概率与频次控制 + 仅括号词生效 + 不以表情开头 + 上一条使用则本条禁用）。
 * 默认把占位词替换成 emoji（依赖 EmojiReplacer.replace）。
 */
public final class EmoteSanitizer {
    private EmoteSanitizer() {}

    /** 允许的括号占位词（仅这些会被保留并转 emoji） */
    private static final List<String> ALLOW = Arrays.asList(
            "(微笑)", "(笑哭)", "(汗)", "(OK)", "(点赞)", "(比心)", "(鼓掌)", "(疑问)", "(哭)", "(尴尬)", "(生气)"
    );
    private static final Set<String> ALLOW_SET = new LinkedHashSet<>(ALLOW);

    /** 仅括号内容： (xxx) / （xxx） */
    private static final Pattern PAREN = Pattern.compile("[（(]\\s*([\\p{L}\\p{sc=Han}A-Za-z0-9\\p{Punct}]{1,8})\\s*[)）]");
    private static final Pattern ONLY_PAREN_OR_WS = Pattern.compile("^[\\s()（）]+$");

    /** 括号同义词 → 规范占位。只对括号里的词生效；裸词（如“今天很开心”）不处理 */
    private static final Map<String, String> BRACKET_SYNONYMS;
    static {
        Map<String, String> m = new LinkedHashMap<>();
        // 开心系 → (微笑)
        m.put("开心", "(微笑)");
        m.put("高兴", "(微笑)");
        m.put("快乐", "(微笑)");
        // 认可 → (OK)/(点赞)
        m.put("ok", "(OK)");
        m.put("OK", "(OK)");
        m.put("好的", "(OK)");
        m.put("赞", "(点赞)");
        // 困惑 → (疑问)
        m.put("疑问", "(疑问)");
        m.put("不理解", "(疑问)");
        m.put("不明白", "(疑问)");
        m.put("？", "(疑问)");
        m.put("?", "(疑问)");
        // 尴尬/出汗
        m.put("尴尬", "(汗)");
        m.put("汗", "(汗)");
        // 难过/哭
        m.put("哭", "(哭)");
        m.put("难过", "(哭)");
        // 生气
        m.put("生气", "(生气)");
        BRACKET_SYNONYMS = Collections.unmodifiableMap(m);
    }

    /** ===== 内置默认策略（无需外部设置） ===== */
    private static final Options DEFAULT_OPTIONS = new Options();
    private static final EmoteHistory DEFAULT_HISTORY = new InMemoryEmoteHistory(8192);

    /** 便捷调用：使用内置历史与默认参数 */
    public static String clean(String raw, String chatId) {
        return clean(raw, chatId, DEFAULT_HISTORY, DEFAULT_OPTIONS);
    }

    /** 兼容扩展：可自带历史（可传 null） */
    public static String clean(String raw, String chatId, EmoteHistory hist) {
        return clean(raw, chatId, hist, DEFAULT_OPTIONS);
    }

    /** 主流程（允许自定义 options） */
    public static String clean(String raw, String chatId, EmoteHistory hist, Options opt) {
        if (raw == null || raw.isBlank()) return raw;
        if (opt == null) opt = DEFAULT_OPTIONS;

        // 统一括号 + 去空括号
        String s = raw.replace('（','(').replace('）',')').trim();
        while (s.contains("()")) s = s.replace("()", "");
        if (ONLY_PAREN_OR_WS.matcher(s).matches()) return "";

        // 规范化：只保留白名单占位；如果是括号同义词（如 (开心)），归一到白名单 (微笑)
        Matcher m = PAREN.matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String inner = m.group(1).trim();
            String tag = normalizeBracketToAllowed(inner); // -> 可能返回 "(微笑)" 或 null
            if (tag != null) {
                m.appendReplacement(sb, Matcher.quoteReplacement(tag));
            } else {
                // 非法/未知：去掉括号，保留内部文字
                m.appendReplacement(sb, Matcher.quoteReplacement(inner));
            }
        }
        m.appendTail(sb);
        s = sb.toString().trim();
        while (s.contains("()")) s = s.replace("()", "");
        if (ONLY_PAREN_OR_WS.matcher(s).matches()) return "";

        // 收集合法占位的区间
        List<int[]> spans = collectAllowedSpans(s);

        // 连续重复去重（"(OK)(OK)" → "(OK)"）
        s = dedupAdjacent(s, spans);

        // 上一条用了表情 → 本条禁用
        if (opt.forbidIfPrevUsed && hist != null && hist.prevAssistantUsedEmote(chatId)) {
            s = removeAllAllowedTags(s);
            if (ONLY_PAREN_OR_WS.matcher(s).matches()) s = "";
            hist.markAssistantEmoteUsage(chatId, false);
            return s;
        }

        // 概率门控：允许本条使用表情？
        Random rnd = (opt.random != null ? opt.random : ThreadLocalRandom.current());
        boolean allowThisMsg = rnd.nextDouble() < opt.allowEmoteProbability; // 40%
        if (!allowThisMsg) {
            s = removeAllAllowedTags(s);
            if (ONLY_PAREN_OR_WS.matcher(s).matches()) s = "";
            if (hist != null) hist.markAssistantEmoteUsage(chatId, false);
            return s;
        }

        // 禁止以表情开头
        spans = collectAllowedSpans(s);
        if (opt.forbidLeadingEmote && !spans.isEmpty() && spans.get(0)[0] == 0) {
            s = s.substring(spans.get(0)[1]).trim();
            spans.remove(0);
        }

        // 限制数量（常规上限 2；20% 概率允许到 4）
        int allowedMax = opt.maxEmotesPerMsg;
        if (rnd.nextDouble() < opt.allowBurstProbability) {
            allowedMax = Math.max(opt.maxEmotesPerMsg, opt.maxEmotesBurst);
        }
        if (spans.size() > allowedMax) {
            s = keepAtMost(s, spans, allowedMax);
        }

        // 兜底清理
        while (s.contains("()")) s = s.replace("()", "");
        if (ONLY_PAREN_OR_WS.matcher(s).matches()) s = "";

        // 转 emoji
        String out = opt.toEmoji ? EmojiReplacer.replace(s) : s;

        if (hist != null) {
            boolean used = containsAnyAllowedTag(s);
            hist.markAssistantEmoteUsage(chatId, used);
        }
        return out.trim();
    }

    // === 选项（内置默认值，不用外部设置） ===
    public static class Options {
        /** 常规最多几个表情（默认 2） */
        public int maxEmotesPerMsg = 2;
        /** 触发“爆发”时最多几个（默认 4） */
        public int maxEmotesBurst = 4;

        /** 禁止以表情开头（默认 true） */
        public boolean forbidLeadingEmote = true;
        /** 上一条用了表情，这一条禁用（默认 true） */
        public boolean forbidIfPrevUsed = true;
        /** 清洗后是否转成 emoji（默认 true） */
        public boolean toEmoji = true;

        /** 允许本条“出现表情”的概率（默认 0.40） */
        public double allowEmoteProbability = 0.40;
        /** 在已允许表情的前提下，允许“超过常规上限”的概率（默认 0.20） */
        public double allowBurstProbability = 0.20;

        /** 可注入自定义随机源（用于测试）；为 null 时用 ThreadLocalRandom */
        public Random random = null;
    }

    // === 历史：用于“上一条用了表情则禁用本条” ===
    public interface EmoteHistory {
        boolean prevAssistantUsedEmote(String chatId);
        void markAssistantEmoteUsage(String chatId, boolean used);
    }

    /** 简易 LRU 实现（线程安全包装），上限默认 8192 条 */
    public static final class InMemoryEmoteHistory implements EmoteHistory {
        private final Map<String, Boolean> lru;

        public InMemoryEmoteHistory(int maxEntries) {
            this.lru = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                    return size() > Math.max(256, maxEntries);
                }
            });
        }
        @Override public boolean prevAssistantUsedEmote(String chatId) {
            Boolean v = lru.get(chatId);
            return v != null && v;
        }
        @Override public void markAssistantEmoteUsage(String chatId, boolean used) {
            lru.put(chatId, used);
        }
    }

    // === 辅助方法 ===

    /** 若 inner 本身是允许占位或是同义词，则返回规范占位，否则返回 null */
    private static String normalizeBracketToAllowed(String inner) {
        String candidate = "(" + inner + ")";
        if (ALLOW_SET.contains(candidate)) return candidate;
        String mapped = BRACKET_SYNONYMS.get(inner);
        if (mapped != null && ALLOW_SET.contains(mapped)) return mapped;
        return null;
    }

    /** 收集允许占位的区间 [start, end, tagIndex]（按 start 升序） */
    private static List<int[]> collectAllowedSpans(String s) {
        List<int[]> spans = new ArrayList<>();
        for (int ti = 0; ti < ALLOW.size(); ti++) {
            String tag = ALLOW.get(ti);
            int from = 0;
            while (true) {
                int i = s.indexOf(tag, from);
                if (i < 0) break;
                spans.add(new int[]{i, i + tag.length(), ti});
                from = i + tag.length();
            }
        }
        spans.sort(Comparator.comparingInt(a -> a[0]));
        return spans;
    }

    /** 相邻重复的占位去重 */
    private static String dedupAdjacent(String s, List<int[]> spans) {
        for (int i = spans.size() - 2; i >= 0; i--) {
            int[] a = spans.get(i), b = spans.get(i + 1);
            if (a[1] == b[0]) {
                String ta = s.substring(a[0], a[1]);
                String tb = s.substring(b[0], b[1]);
                if (ta.equals(tb)) {
                    s = s.substring(0, b[0]) + s.substring(b[1]);
                    spans.remove(i + 1);
                }
            }
        }
        return s;
    }

    /** 仅保留最多 allowed 个占位（优先保留非(微笑)） */
    private static String keepAtMost(String s, List<int[]> spans, int allowed) {
        List<Integer> keepIdx = new ArrayList<>();
        List<Integer> smileIdx = new ArrayList<>();
        for (int i = 0; i < spans.size(); i++) {
            String tag = s.substring(spans.get(i)[0], spans.get(i)[1]);
            if ("(微笑)".equals(tag)) smileIdx.add(i); else keepIdx.add(i);
        }
        List<Integer> chosen = new ArrayList<>(allowed);
        for (int i : keepIdx) { if (chosen.size() < allowed) chosen.add(i); }
        for (int i : smileIdx) { if (chosen.size() < allowed) chosen.add(i); }

        Set<Integer> keepSet = new HashSet<>(chosen);
        for (int i = spans.size() - 1; i >= 0; i--) {
            if (!keepSet.contains(i)) {
                int[] sp = spans.get(i);
                s = s.substring(0, sp[0]) + s.substring(sp[1]);
            }
        }
        return s;
    }

    private static boolean containsAnyAllowedTag(String s) {
        for (String tag : ALLOW) if (s.contains(tag)) return true;
        return false;
    }

    private static String removeAllAllowedTags(String s) {
        String out = s;
        for (String tag : ALLOW) out = out.replace(tag, "");
        return out.trim();
    }
}
