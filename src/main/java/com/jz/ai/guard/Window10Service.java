package com.jz.ai.guard;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class Window10Service {
    private final StringRedisTemplate redis;
    private static final int N = 10;
    private String k(String chatId){ return "mod:w10:" + chatId; }

    /**
     * 先检查窗口内（最近10条，不含本次）是否存在骚扰，再写入本次标记。
     * @param chatId 会话ID
     * @param isHarass 本次是否骚扰（true=骚扰）
     * @return 之前10条内是否出现过骚扰（不含本次）
     */
    public boolean hasHarassPrevThenMark(String chatId, boolean isHarass){
        try {
            final String key = k(chatId);

            // 1) 先查“之前10条”是否有骚扰（不含本次）
            //    用 -N 到 -1 取最后 N 条；长度不足时 Redis 会自动裁边界
            List<String> last = redis.opsForList().range(key, -N, -1);
            boolean prevHasHarass = false;
            if (last != null) {
                for (String s : last) {
                    if ("1".equals(s)) { prevHasHarass = true; break; }
                }
            }

            // 2) 再写入本次标记，并裁剪为最近 N 条
            redis.opsForList().rightPush(key, isHarass ? "1" : "0");
            redis.opsForList().trim(key, -N, -1);

            return prevHasHarass;
        } catch (Exception ignore) {
            // Redis 异常时，回退为“之前无骚扰”，避免误封
            return false;
        }
    }

    /** 仅检查之前10条（不含本次），不写入本次标记 */
    public boolean hasHarassPrev(String chatId){
        try {
            List<String> last = redis.opsForList().range(k(chatId), -N, -1);
            if (last == null) return false;
            for (String s : last) if ("1".equals(s)) return true;
            return false;
        } catch (Exception ignore) {
            return false;
        }
    }

    /** 手动清空窗口（通常不需要） */
    public void clear(String chatId){
        try { redis.delete(k(chatId)); } catch (Exception ignore) {}
    }
}
