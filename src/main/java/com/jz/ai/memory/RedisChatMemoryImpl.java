package com.jz.ai.memory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jz.ai.domain.dto.ChatRow;
import com.jz.ai.mapper.ChatMessageMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * 轻量 Redis ChatMemory：LIST + JSON + LTRIM + TTL
 * Key 形如：{keyPrefix}{conversationId}，例如 chat:mem:u:1001
 */
@Slf4j
@RequiredArgsConstructor
public class RedisChatMemoryImpl implements ChatMemory {

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final String keyPrefix;     // e.g. "chat:mem:"
    private final int    maxMessages;   // 窗口上限（硬裁剪）
    private final Duration ttl;         // 过期（null/<=0 表示不过期）
    private final ChatMessageMapper chatMessageMapper;
    private String k(String conversationId) { return keyPrefix + conversationId; }

    @Override
    public void add(String conversationId, List<Message> messages) {
        if (!StringUtils.hasText(conversationId) || messages == null || messages.isEmpty()) return;
        String key = k(conversationId);

        List<String> toPush = new ArrayList<>(messages.size());
        for (Message m : messages) {
            try {
                StoredMessage sm = fromSpringMessage(m);
                toPush.add(mapper.writeValueAsString(sm));
            } catch (Exception e) {
                log.warn("[ChatMemory] serialize failed, skip message. {}", e.getMessage());
            }
        }
        if (toPush.isEmpty()) return;

        redis.opsForList().rightPushAll(key, toPush);
        // 裁剪到最近 maxMessages 条
        redis.opsForList().trim(key, -maxMessages, -1);
        // 刷新 TTL
        if (ttl != null && !ttl.isZero() && !ttl.isNegative()) {
            redis.expire(key, ttl);
        }
    }
    boolean tryLock(String lockKey, String token, Duration ttl) {
        return Boolean.TRUE.equals(
                redis.opsForValue().setIfAbsent(lockKey, token, ttl) // SET NX PX
        );
    }

    // 原子解锁（避免误删）
    static final DefaultRedisScript<Long> UNLOCK_SCRIPT =
            new DefaultRedisScript<>("if redis.call('get', KEYS[1]) == ARGV[1] then " +
                    "  return redis.call('del', KEYS[1]) else return 0 end", Long.class);
    // 仅当空才写（原子）
    static final DefaultRedisScript<Long> WRITE_IF_EMPTY_SCRIPT =
            new DefaultRedisScript<>("local k   = KEYS[1]\n" +
                    "local ttl = tonumber(ARGV[1])\n" +
                    "if redis.call('LLEN', k) == 0 then\n" +
                    "  for i = 2, #ARGV do\n" +
                    "    redis.call('RPUSH', k, ARGV[i])\n" +
                    "  end\n" +
                    "  if ttl and ttl > 0 then redis.call('PEXPIRE', k, ttl) end\n" +
                    "  return 1\n" +
                    "else\n" +
                    "  return 0\n" +
                    "end", Long.class);
    @Override
    public List<Message> get(String conversationId, int lastN) {
        if (!StringUtils.hasText(conversationId) || lastN <= 0) return List.of();

        final String key  = k(conversationId);
        final int need    = Math.min(lastN, maxMessages);

        // ① 快路径：Redis 命中
        Long size = redis.opsForList().size(key);
        if (size != null && size > 0) {
            long startIdx = Math.max(0L, size - need);
            List<String> page = redis.opsForList().range(key, startIdx, -1);
            if (page != null && !page.isEmpty()) {
                return parse(page);
            }
            // 如果 page 为空，继续走回填
        }

        // ② miss：加锁回填（仅 miss 时加锁）
        String lockKey = key + ":lock";
        String token   = UUID.randomUUID().toString();
        boolean locked = tryLock(lockKey, token, Duration.ofSeconds(15)); // ttl 覆盖“查库+写回”的最长时间

        try {
            if (locked) {
                // 二次检查，避免惊群后仍重复回填
                Long s2 = redis.opsForList().size(key);
                if (s2 == null || s2 == 0) {
                    // 从 MySQL 取最近 need 条（最新在前）→ 反转成正序
                    List<ChatRow> rows = chatMessageMapper.findLastN(conversationId, need);
                    if (!rows.isEmpty()) {
                        Collections.reverse(rows);

                        long ttlMillis = (ttl != null && !ttl.isZero() && !ttl.isNegative())
                                ? ttl.toMillis() : 0L;

                        List<String> argv = new ArrayList<>(rows.size() + 1);
                        argv.add(String.valueOf(ttlMillis));          // ARGV[1] = ttl(ms)
                        for (ChatRow r : rows) {
                            String json = toStoredJson(r.role(), r.content());
                            if (json != null) argv.add(json);
                        }
                        // 原子：仅当列表为空才写，防止锁过期产生重复写
                        redis.execute(WRITE_IF_EMPTY_SCRIPT, List.of(key), argv.toArray());
                    }
                }
            } else {
                // 其他线程/实例正在回填，稍等再读
                try { Thread.sleep(60); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
        } finally {
            if (locked) {
                redis.execute(UNLOCK_SCRIPT, List.of(lockKey), token);
            }
        }

        // ③ 回填后再读一次（没有就返回空列表）(没有就是真没有)
        size = redis.opsForList().size(key);
        if (size == null || size == 0) return List.of();
        long startIdx = Math.max(0L, size - need);
        List<String> page = redis.opsForList().range(key, startIdx, -1);
        return (page == null || page.isEmpty()) ? List.of() : parse(page);
    }

    @Override
    public void clear(String conversationId) {
        if (!StringUtils.hasText(conversationId)) return;
        redis.delete(k(conversationId));
    }

    /* ===== 存储层 <-> Spring AI Message 互转 ===== */

    private StoredMessage fromSpringMessage(Message m) {
        String role = switch (m.getMessageType()) {
            case USER -> "user";
            case ASSISTANT -> "assistant";
            case SYSTEM -> "system";
            default -> "assistant"; // 其他（如 tool）先当 assistant 处理；需要时扩展
        };
        return StoredMessage.builder()
                .role(role)
                .content(m.getContent() == null ? "" : m.getContent())
                .build();
    }

    private Message toSpringMessage(StoredMessage sm) {
        String role = sm.getRole() == null ? "" : sm.getRole();
        String content = sm.getContent() == null ? "" : sm.getContent();
        return switch (role) {
            case "user" -> new UserMessage(content);
            case "system" -> new SystemMessage(content);
            default -> new AssistantMessage(content);
        };
    }
    /** 解析 Redis 中的 JSON 列表为 Spring AI Message 列表 */
    private List<Message> parse(List<String> jsons) {
        List<Message> out = new ArrayList<>(jsons.size());
        for (String j : jsons) {
            try {
                StoredMessage sm = mapper.readValue(j, new TypeReference<StoredMessage>() {});
                out.add(toSpringMessage(sm));
            } catch (Exception e) {
                log.warn("[ChatMemory] bad json ignored: {}", e.getMessage());
            }
        }
        return out;
    }

    /** 把一条 (role, content) 序列化为 Redis 的 StoredMessage JSON */
    private String toStoredJson(String role, String content) {
        try {
            return mapper.writeValueAsString(
                    StoredMessage.builder()
                            .role(role == null ? "assistant" : role)
                            .content(content == null ? "" : content)
                            .build()
            );
        } catch (Exception e) {
            log.warn("[ChatMemory] toStoredJson failed: {}", e.getMessage());
            return null;
        }
    }

}
