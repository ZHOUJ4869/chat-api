package com.jz.ai.chat.lms;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Slf4j
@Service
@RequiredArgsConstructor
public class LmsCountersService {
    private final StringRedisTemplate redis;

    public long bumpSinceLast(String statKey, long ttlSeconds) {
        //7天内不互动，redis key可能会丢失，但最多也就是少了30条消息的摘要（一半retreive的摘要）
        Long v = redis.opsForHash().increment(statKey, "c_since_last_lms", 1L);
        redis.expire(statKey, Duration.ofSeconds(ttlSeconds));
        return v == null ? 0 : v;
    }

    public void resetSinceLast(String statKey, long ttlSeconds) {
        redis.opsForHash().put(statKey, "c_since_last_lms", "0");
        redis.expire(statKey, Duration.ofSeconds(ttlSeconds));
    }

    public void updateEwma(String ewmaKey, double alpha, int histTk, int lmsTk, long ttlSeconds) {
        // 指数滑动平均：new = alpha*x + (1-alpha)*old
        double oldHist = parse(redis.opsForHash().get(ewmaKey, "t_hist_avg"));
        double oldLms  = parse(redis.opsForHash().get(ewmaKey, "t_lms_avg"));
        double newHist = alpha*histTk + (1-alpha)* (oldHist==0?histTk:oldHist);
        double newLms  = alpha*lmsTk  + (1-alpha)* (oldLms==0?lmsTk:oldLms);
        redis.opsForHash().put(ewmaKey, "t_hist_avg", String.valueOf((int)Math.round(newHist)));
        redis.opsForHash().put(ewmaKey, "t_lms_avg",  String.valueOf((int)Math.round(newLms)));
        redis.expire(ewmaKey, Duration.ofSeconds(ttlSeconds));
    }

    public int getAvgHistTokens(String ewmaKey, int fallback) {
        int v = (int)parse(redis.opsForHash().get(ewmaKey, "t_hist_avg"));
        return v==0?fallback:v;
    }
    public int getAvgLmsTokens(String ewmaKey, int fallback) {
        int v = (int)parse(redis.opsForHash().get(ewmaKey, "t_lms_avg"));
        return v==0?fallback:v;
    }

    private double parse(Object o) {
        if (o==null) return 0;
        try { return Double.parseDouble(String.valueOf(o)); }
        catch (Exception e){ return 0; }
    }
}
