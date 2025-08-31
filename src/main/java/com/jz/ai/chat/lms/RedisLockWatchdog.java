package com.jz.ai.chat.lms;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class RedisLockWatchdog {

    private final StringRedisTemplate redis;
    private final DefaultRedisScript<Long> unlockScript;
    private final DefaultRedisScript<Long> renewScript;
    private final ScheduledExecutorService scheduler;

    public RedisLockWatchdog(
            StringRedisTemplate redis,
            DefaultRedisScript<Long> unlockScript,
            DefaultRedisScript<Long> renewScript,
            ScheduledExecutorService lockRenewScheduler
    ) {
        this.redis = redis;
        this.unlockScript = unlockScript;
        this.renewScript = renewScript;
        this.scheduler = lockRenewScheduler;
    }

    @Value("${chat.lms.lock.renew-initial-delay-ms:0}")
    private long renewInitialDelayMs;

    @Value("${chat.lms.lock.min-renew-interval-ms:1000}")
    private long minRenewIntervalMs;

    /** 尝试加锁（SET NX EX）成功则返回会话；失败返回 null */
    public LockSession tryAcquire(String key, long ttlMs) {
        String token = UUID.randomUUID().toString();
        Boolean ok = redis.opsForValue().setIfAbsent(key, token, ttlMs, TimeUnit.MILLISECONDS);
        if (!Boolean.TRUE.equals(ok)) return null;
        return new LockSession(key, token, ttlMs);
    }

    /** 加锁会话（AutoCloseable，支持 try-with-resources） */
    @Getter
    public class LockSession implements AutoCloseable {
        private final String key;
        private final String token;
        private volatile long ttlMs;
        private volatile ScheduledFuture<?> renewTask;
        private volatile boolean closed = false;

        private LockSession(String key, String token, long ttlMs) {
            this.key = key;
            this.token = token;
            this.ttlMs = ttlMs;
        }

        /** 启动看门狗（每 ttl/3 续期一次） */
        public void startWatchdog() {
            long period = Math.max(minRenewIntervalMs, ttlMs / 3);
            long initial = Math.max(0, renewInitialDelayMs);
            // 轻微抖动，避免羊群效应
            long jitter = (long)(Math.random() * (period / 10.0));
            long firstDelay = initial + jitter;

            this.renewTask = scheduler.scheduleAtFixedRate(() -> {
                try {
                    Long res = redis.execute(renewScript,
                            Collections.singletonList(key),
                            token,
                            String.valueOf(ttlMs));
                    if (res == null || res == 0L) {
                        // 锁不再属于当前持有者或已过期
                        cancelRenewal();
                        log.debug("Lock renew failed (lost ownership), key={}", key);
                    }
                } catch (Exception e) {
                    log.debug("Lock renew exception key={}, err={}", key, e.toString());
                    // 出错不立刻退出，等待下个周期再试
                }
            }, firstDelay, period, TimeUnit.MILLISECONDS);
        }

        /** 立即手动续期一次（可用于处理超长阶段性任务后立刻续期） */
        public boolean renewNow() {
            try {
                Long res = redis.execute(renewScript,
                        Collections.singletonList(key),
                        token,
                        String.valueOf(ttlMs));
                return res != null && res > 0;
            } catch (Exception e) {
                return false;
            }
        }

        /** 取消续期任务（仅停止调度，不释放锁） */
        public void cancelRenewal() {
            ScheduledFuture<?> t = this.renewTask;
            if (t != null) t.cancel(true);
            this.renewTask = null;
        }

        /** 关闭会话：取消续期并原子解锁（compare token + DEL） */
        @Override public void close() {
            if (closed) return;
            closed = true;
            cancelRenewal();
            try {
                Long res = redis.execute(unlockScript, Collections.singletonList(key), token);
                // res==1 表示删除成功；0 表示已不属于你或已过期
            } catch (Exception ignore) {}
        }
    }
}
