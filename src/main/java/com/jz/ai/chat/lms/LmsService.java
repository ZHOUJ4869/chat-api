package com.jz.ai.chat.lms;


import com.fasterxml.jackson.databind.ObjectMapper;
import com.jz.ai.domain.entity.ChatLms;
import com.jz.ai.domain.entity.ChatMessage;
import com.jz.ai.mapper.ChatLmsMapper;
import com.jz.ai.mapper.ChatMessageMapper;
import com.jz.ai.chat.tokens.TokenCounter;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.ReturnType;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class LmsService {
 /*   @Qualifier("summarizerChatClient")
    private ChatClient chatClient;  */
 // 摘要专用
    @Qualifier("statelessChatClients")
    private final Map<String,ChatClient> chatClientMap;
    private final ChatMessageMapper chatMessageMapper;
    private final ChatLmsMapper chatLmsMapper;
    private final TokenCounter tokens;
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    // 来自 yml 的配置（可用 @ConfigurationProperties 注入，这里简化）
    @Value("${chat.lms.redis.key-prefix}")
    private String redisKeyPrefix;
    @Value("${chat.lms.redis.recent-cache-size}")
    private int recentCacheSize = 400;
    @Value("${chat.lms.redis.counter-ttl-seconds}")
    private long counterTtlSeconds = 604800;
    @Value("${chat.lms.template.max-tokens-per-item}")
    private int lmsMaxPerItem = 400;
    @Value("${chat.lms.template.min-tokens-per-item}")
    private final int lmsMinPerItem = 120;
    @Value("${chat.lms.summarizer.model}")
    private  String summarizerModelName;
    @Value("${chat.lms.compaction.keep-recent-ratio:0.5}")
    private double keepRecentRatio;

    // 注入
    private final RedisLockWatchdog lockDog;

    @Value("${chat.lms.redis.lock-ttl-seconds:60}")
    private long lockTtlSeconds;

    private ChatClient chatClient( ){
        return  chatClientMap.get(summarizerModelName);
    }
    private String lockKey(String chatId) {
        return redisKeyPrefix + "lock:compact:" + chatId;
    }
    private static long toMillis(java.time.LocalDateTime t) {
        return t.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
    }
    // LmsService 内部，新增：
    private String toValidJsonOrWrap(String modelOut) {
        String s = (modelOut == null) ? "" : modelOut.trim();

        // 1) 去掉 ``` 或 ```json 包裹
        if (s.startsWith("```")) {
            s = s.replaceFirst("(?s)^```(?:json)?\\s*", "");
            s = s.replaceFirst("(?s)\\s*```\\s*$", "");
            s = s.trim();
        }

        // 2) 完整 JSON 直接验
        if (s.startsWith("{") || s.startsWith("[")) {
            try { mapper.readTree(s); return s; } catch (Exception ignore) {}
        }

        // 3) 从文本中提取第一段 {...} 或 [...]（覆盖大多数“前后带说明”的情况）
        var m = java.util.regex.Pattern.compile("(?s)(\\{.*\\}|\\[.*\\])").matcher(s);
        if (m.find()) {
            String cand = m.group(1).trim();
            try { mapper.readTree(cand); return cand; } catch (Exception ignore) {}
        }

        // 4) 兜底包装成一个“合法对象”，避免写库失败，同时保留原文方便排查
        var root = mapper.createObjectNode();
        root.putArray("persona_signals");
        root.putArray("facts");
        root.putArray("goals");
        root.putArray("issues");
        root.putArray("commitments");
        root.put("emotions_trend", "");
        root.putArray("todo_us");
        root.putArray("todo_user");
        var ts = mapper.createObjectNode();
        ts.put("from", 0);
        ts.put("to", 0);
        root.set("time_span", ts);
        root.put("raw_text", s); // 把脏输出留在这里
        try {
            return mapper.writeValueAsString(root);
        } catch (Exception e) {
            // 极端兜底
            return "{\"raw_text\":" + mapper.valueToTree(s).toString() + "}";
        }
    }

    private List<ChatLms> parseMergedArrayAndInsert(String chatId, Long userId, Long agentId,
                                                    String mergedJsonArray) throws Exception {
        var root = mapper.readTree(mergedJsonArray);
        List<ChatLms> out = new ArrayList<>();
        if (!root.isArray()) {
            // 兜底：不是数组，当成单条对象
            ChatLms one = new ChatLms();
            one.setChatId(chatId);
            one.setUserId(userId);
            one.setAgentId(agentId);
            one.setSummaryJson(mergedJsonArray);
            one.setTokensEst(Math.max(lmsMinPerItem, Math.min(lmsMaxPerItem, tokens.ofJson(mergedJsonArray))));
            one.setOrigin("MERGED");
            one.setCompacted(0);
            // 时间窗可不设或设为 0；这里留空由 DB 默认
            chatLmsMapper.insert(one);
            out.add(one);
            return out;
        }
        for (var node : root) {
            String json = node.toString();
            ChatLms lms = new ChatLms();
            lms.setChatId(chatId);
            lms.setUserId(userId);
            lms.setAgentId(agentId);
            lms.setSummaryJson(json);
            lms.setTokensEst(Math.max(lmsMinPerItem, Math.min(lmsMaxPerItem, tokens.ofJson(json))));
            lms.setOrigin("MERGED");
            lms.setCompacted(0);
            // 可选：从 node.time_span 提取 from/to
            try {
                long from = node.path("time_span").path("from").asLong(0);
                long to   = node.path("time_span").path("to").asLong(0);
                if (from > 0) lms.setWindowFromTs(from);
                if (to > 0)   lms.setWindowToTs(to);
            } catch (Exception ignore) {}
            chatLmsMapper.insert(lms);
            out.add(lms);
        }
        return out;
    }
    private List<ChatLms> parseMergedArrayAndInsert(
            String chatId, Long userId, Long agentId,
            String mergedJsonArray,
            long fallbackFrom, long fallbackTo
    ) throws Exception {
        String cleaned = toValidJsonOrWrap(mergedJsonArray); // ★★ 先净化
        var root = mapper.readTree(cleaned);
        List<ChatLms> out = new ArrayList<>();

        if (!root.isArray()) {
            ChatLms one = new ChatLms();
            one.setChatId(chatId);
            one.setUserId(userId);
            one.setAgentId(agentId);
            one.setSummaryJson(mergedJsonArray);
            one.setTokensEst(Math.max(lmsMinPerItem, Math.min(lmsMaxPerItem, tokens.ofJson(mergedJsonArray))));
            one.setOrigin("MERGED");
            one.setCompacted(0);
            one.setWindowFromTs(fallbackFrom);
            one.setWindowToTs(fallbackTo);
            chatLmsMapper.insert(one);
            out.add(one);
            return out;
        }

        for (var node : root) {
            String json = node.toString();
            long from = node.path("time_span").path("from").asLong(0);
            long to   = node.path("time_span").path("to").asLong(0);
            if (from <= 0) from = fallbackFrom;
            if (to   <= 0) to   = fallbackTo;
            //如果用了兜底，那这些时间肯定是不准确的，但是能够保证基本的时间前后顺序
            ChatLms lms = new ChatLms();
            lms.setChatId(chatId);
            lms.setUserId(userId);
            lms.setAgentId(agentId);
            lms.setSummaryJson(json);
            lms.setTokensEst(Math.max(lmsMinPerItem, Math.min(lmsMaxPerItem, tokens.ofJson(json))));
            lms.setOrigin("MERGED");
            lms.setCompacted(0);
            lms.setWindowFromTs(from);
            lms.setWindowToTs(to);
            chatLmsMapper.insert(lms);
            out.add(lms);
        }
        return out;
    }

    public List<String> fetchRecentForPrompt(String chatId, int limit) {
        String key = LmsRedisKeys.list(redisKeyPrefix, chatId);
        List<String> list = redis.opsForList().range(key, 0, limit-1);
        if (!CollectionUtils.isEmpty(list)) return list;

        // 回源 SQL
        List<ChatLms> db = chatLmsMapper.selectRecent(chatId, limit);
        List<String> jsons = new ArrayList<>();
        for (ChatLms c : db) jsons.add(c.getSummaryJson());
        if (!jsons.isEmpty()) {
            // 回填 Redis
            redis.opsForList().leftPushAll(key, jsons.toArray(new String[0]));
            redis.opsForList().trim(key, 0, recentCacheSize-1);
            redis.expire(key, counterTtlSeconds, TimeUnit.SECONDS);
        }
        return jsons;
    }

    /** 同步生成一条摘要（给异步包装调用） */
    @SneakyThrows
    public ChatLms generateOnce(String chatId, Long userId, Long agentId,
                                List<ChatMessage> raw, Integer turnsFromId, Integer turnsToId) {

        String sys = """
                你是对话纪要生成器。请将给定对话压缩为**结构化JSON**，字段：
                persona_signals[], facts[], goals[], issues[], commitments[], emotions_trend, 
                todo_us[], todo_user[], time_span{from,to}。
                要求：
                - 只输出JSON本体，不要任何解释、前后缀、标题或Markdown代码块标记。
                - emotions_trend 为字符串；time_span.from/to 为毫秒时间戳（整数）；没有就省略该键。
                - 不要复述原文，不要杜撰。
                """;

        // 组 prompt（只给必要事实）
        StringBuilder sb = new StringBuilder();
        for (ChatMessage m : raw) {
            sb.append('[').append(m.getRole()).append("] ").append(m.getContent()).append('\n');
        }

        String rawOut = chatClient().prompt()
                .system(sys)
                .user("对话如下，生成JSON：\n" + sb)
                .call()
                .content();

        String safeJson = toValidJsonOrWrap(rawOut); // ★★ 关键：净化
        int tk = Math.max(lmsMinPerItem, Math.min(lmsMaxPerItem, tokens.ofJson(safeJson)));
        ChatLms lms = new ChatLms();
        lms.setUserId(userId);
        lms.setAgentId(agentId);
        lms.setChatId(chatId);
        long fromTs = raw.isEmpty()? Instant.now().toEpochMilli() :
                raw.get(0).getCreatedAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
        long toTs = raw.isEmpty()? fromTs :
                raw.get(raw.size()-1).getCreatedAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
        lms.setWindowFromTs(fromTs);
        lms.setWindowToTs(toTs);
        lms.setTurnsFromId(turnsFromId==null? null: turnsFromId.longValue());
        lms.setTurnsToId(turnsToId==null? null: turnsToId.longValue());
        lms.setTokensEst(tk);
        lms.setSummaryJson(safeJson);
        lms.setOrigin("RAW"); //未二级压缩的，（RAW表示经过了一级压缩）
        lms.setCompacted(0);
        chatLmsMapper.insert(lms);

        // 写入 Redis 列表头
        String key = LmsRedisKeys.list(redisKeyPrefix, chatId);
        redis.opsForList().leftPush(key, safeJson);
        redis.opsForList().trim(key, 0, recentCacheSize-1);
        redis.expire(key, counterTtlSeconds, TimeUnit.SECONDS);

        return lms;
    }

    @Async
    public void generateFromRecentAsync(String chatId, Long userId, Long agentId, int windowTurns) {
        // 拉最近 windowTurns 条消息（你已有 ChatMessageMapper）
        List<ChatMessage> list = chatMessageMapper.selectRecentByChatId(chatId, windowTurns);
        if (list == null || list.isEmpty()) return;

        Integer fromId = list.get(0).getId().intValue();
        Integer toId   = list.get(list.size()-1).getId().intValue();
        generateOnce(chatId, userId, agentId, list, fromId, toId);
    }
    private static long asMs(ChatLms x) {
        if (x.getWindowToTs() != null && x.getWindowToTs() > 0) return x.getWindowToTs();
        return x.getCreatedAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
    }
    private static long asMsFrom(ChatLms x) {
        if (x.getWindowFromTs() != null && x.getWindowFromTs() > 0) return x.getWindowFromTs();
        return x.getCreatedAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    @Async
    public void compactIfExceedAsync(String chatId, int softCap) {
        int total = chatLmsMapper.countByChat(chatId);
        if (total < softCap) return; // 未达阈值，不压缩
        // 分布式锁，避免并发压缩
        String lockKey = lockKey(chatId);
        long  ttlMs=lockTtlSeconds*1000L;//转为ms
//        String token = UUID.randomUUID().toString();
        RedisLockWatchdog.LockSession lock = lockDog.tryAcquire(lockKey, ttlMs);
        if (lock==null) return;
        try(lock){
            lock.startWatchdog(); // 开启看门狗
            // 目标总量 = softCap / 2
            int finalTarget = Math.max(2, softCap / 2);
            // 最终一半里，保留多少“最近原始摘要”
            int reserveRecentRaw = Math.max(1, (int)Math.ceil(finalTarget * keepRecentRatio));
            //相当于0.5*keepRecentRatio（最近的长期记忆 1/4是保留不二次压缩的）
            // 需要压缩的“最老部分”的条数
            // 压缩后：总 = reserveRecentRaw(原始保留) + compactOut(合并产生)
            // 希望：总 == finalTarget => compactOut = finalTarget - reserveRecentRaw
            int compactOut = Math.max(1, finalTarget - reserveRecentRaw);
            // 需要纳入压缩的“最旧的原始条数”
            int numToCompact = Math.max(0, total - reserveRecentRaw);
            if (numToCompact <= 0) return; // 已经比目标还少/等于
            // 取最旧 numToCompact 条
            List<ChatLms> oldest = chatLmsMapper.selectOldestByAge(chatId, numToCompact);
            long fallbackFrom = oldest.stream().mapToLong(LmsService::asMsFrom).min().orElse(System.currentTimeMillis());
            long fallbackTo   = oldest.stream().mapToLong(LmsService::asMs).max().orElse(fallbackFrom);
            if (oldest.isEmpty()) return;
            // 构造压缩提示：要求输出“恰好 compactOut 条”的 JSON 数组
            String sys = """
                你是长期记忆压缩器。把多条摘要JSON整合为更少条**结构化JSON**，字段与输入相同：
                persona_signals[], facts[], goals[], issues[], commitments[], emotions_trend,
                todo_us[], todo_user[], time_span{from,to}。
                规则：
                1) 去重、合并事实，保留时间跨度（from/to 为毫秒时间戳，取区间最小/最大）。
                2) 输出为JSON数组，**数组长度必须为 target_count**。
                3) 每一项尽量覆盖一段连续时间/主题，避免过度冗长。
                """;
            StringBuilder user = new StringBuilder();
            user.append("target_count=").append(compactOut).append("\n");
            user.append("下面是若干条摘要JSON（逐条，可能很长）：\n");
            for (ChatLms o : oldest) {
                user.append(o.getSummaryJson()).append("\n---\n");
            }
            String merged = chatClient().prompt()
                    .system(sys)
                    .user(user.toString())
                    .call()
                    .content();
            // 解析成数组，多了截断、少了接受，完全无效则兜底变单条
            List<ChatLms> mergedItems;
            try {
                mergedItems = parseMergedArrayAndInsert(chatId, oldest.get(0).getUserId(),
                        oldest.get(0).getAgentId(), merged,
                        fallbackFrom,fallbackTo);
            } catch (Exception e) {
                // 兜底：作为一条代表性摘要
                ChatLms rep = new ChatLms();
                rep.setChatId(chatId);
                rep.setUserId(oldest.get(0).getUserId());
                rep.setAgentId(oldest.get(0).getAgentId());
                rep.setSummaryJson(merged);
                rep.setTokensEst(tokens.ofJson(merged));
                rep.setOrigin("MERGED");//二级压缩的
                rep.setCompacted(0);
                chatLmsMapper.insert(rep);
                mergedItems = List.of(rep);
            }
            // 把“最旧的原始摘要”删除（或改为 compacted=1，看你策略）
            List<Long> delIds = oldest.stream().map(ChatLms::getId).toList();
            chatLmsMapper.markArchivedByIds(delIds);
            // 压缩完成后总条数≈ reserveRecentRaw + mergedItems.size()
            // 刷新 Redis：用最新 recentCacheSize 条（按 id DESC）
            String key = LmsRedisKeys.list(redisKeyPrefix, chatId);
            redis.delete(key);
            List<ChatLms> recent = chatLmsMapper.selectNewestByAge(chatId, recentCacheSize);
            if (!recent.isEmpty()) {
                List<String> jsons = recent.stream().map(ChatLms::getSummaryJson).toList();
                redis.opsForList().leftPushAll(key, jsons.toArray(new String[0]));
                redis.opsForList().trim(key, 0, recentCacheSize-1);
                redis.expire(key, counterTtlSeconds, TimeUnit.SECONDS);
            }
        }
    }

}
