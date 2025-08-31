// src/main/java/com/jz/ai/chat/async/BurstBatcher.java
package com.jz.ai.chat.async;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.AllArgsConstructor;
import lombok.Data;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 每个 chatId 一个简单的“连发合并”队列：
 * - 第一条到来，1s 后触发处理；
 * - 每次最多取 MAX_BATCH 条合并（默认 3 条），剩余的留给下一轮；
 * - 无取消/打断逻辑，够简单。
 */
@Component
public class BurstBatcher {

    private static final long WINDOW_MS = 9000;   // 1s内 合并窗口(再大点）
    private static final int  MAX_BATCH = 6;      // 每批最多条数（尽量是9s内最多3条消息发送）
    private static final long MIN_GAP_MS  = 500;   // 如果新来了3+以上消息快速回复0.5s
    private static final long FAST_GAP_MS = 1000;   // 不急回复，至少等1s
    private final ScheduledExecutorService exec =
            Executors.newScheduledThreadPool(Math.max(8, Runtime.getRuntime().availableProcessors() * 2));

    private final Map<String, Deque<UserMsg>> queues = new ConcurrentHashMap<>();
    private final Map<String, AtomicBoolean> scheduled = new ConcurrentHashMap<>();

    // === 指标 ===
    private final Counter submitCounter;
    private final DistributionSummary batchSizeSummary;
    private final Timer windowWaitTimer;
    private final MeterRegistry registry;

    public BurstBatcher(MeterRegistry registry) {
        this.registry = registry;
        this.submitCounter = Counter.builder("chat.burst.submit.count")
                .description("Number of messages submitted into BurstBatcher")
                .register(registry);

        this.batchSizeSummary = DistributionSummary.builder("chat.burst.batch.size")
                .description("Batch size distribution when fire() runs")
                .baseUnit("messages")
                .register(registry);

        this.windowWaitTimer = Timer.builder("chat.burst.window.wait")
                .description("Wait duration from first message in window to fire()")
                .publishPercentiles(0.5, 0.9, 0.95, 0.99)
                .register(registry);

        // 总积压 gauge
        Gauge.builder("chat.burst.backlog.total", this, BurstBatcher::totalBacklog)
                .description("Total backlog size across all chatId queues")
                .register(registry);
    }


    public void submit(String chatId, UserMsg msg, Consumer<List<UserMsg>> onBatch) {
        submitCounter.increment();
        queues.computeIfAbsent(chatId, k -> new ConcurrentLinkedDeque<>()).addLast(msg);
        scheduled.computeIfAbsent(chatId, k -> new AtomicBoolean(false));

        // 保证的是一个ID同一时刻只加入一个任务，执行完后会变成false，这时候再塞，延时任务线程池使用的是一个延时队列
        if (scheduled.get(chatId).compareAndSet(false, true)) {
            exec.schedule(() -> fire(chatId, onBatch), WINDOW_MS, TimeUnit.MILLISECONDS);
        }
    }

    private void fire(String chatId, Consumer<List<UserMsg>> onBatch) {
        try {
            Deque<UserMsg> q = queues.get(chatId);
            if (q == null) return;

            List<UserMsg> batch = new ArrayList<>(MAX_BATCH);
            for (int i = 0; i < MAX_BATCH; i++) {
                UserMsg m = q.pollFirst();
                if (m == null) break;
                batch.add(m);
            }

            if (!batch.isEmpty()) {
                // 记录窗口等待（从窗口里最早入队的消息到现在的等待时长）
                long minTs = batch.stream().mapToLong(UserMsg::getTs).min().orElse(System.currentTimeMillis());
                windowWaitTimer.record(System.currentTimeMillis() - minTs, TimeUnit.MILLISECONDS);
                batchSizeSummary.record(batch.size());
                //理想情况下等待时候应该等于WINDOW_MS多一点
                onBatch.accept(List.copyOf(batch)); // 复制不可变，避免后续被修改
            }
        } finally {
            // 看看队列里是否还有剩余，有则继续排下一轮；没有则清空“已排程”标记
            //有剩余说明一点，就是在回复消息的过程中有新的消息来了，
            //一般这种情况两种做法：1、不管它，我已经打好消息了直接回复 2、可以放弃这条消息重新编辑即支持打断
            //3、支持两种做法，一种就是在finally这里把上一次消息拼接起来，再次给模型 ，较好的做法应该是，设计一个flag打断标志
            //在模型回复前（在onBatch函数内部几个断点处查看是否已经被打断，如果打断后续的就不用进行了
            Deque<UserMsg> q2 = queues.get(chatId);
            if (q2 != null && !q2.isEmpty()) {
                int remain = q2.size();
                long nextDelay =
                        (remain >= MAX_BATCH)
                                ? FAST_GAP_MS
                                : Math.max(MIN_GAP_MS, WINDOW_MS / 2); // ← 这里把 1s 改成一半
                // 继续下一轮
                exec.schedule(() -> fire(chatId, onBatch), nextDelay, TimeUnit.MILLISECONDS);
            } else {
                scheduled.get(chatId).set(false);
            }
        }
    }
    /** 供监控用：返回总积压条数 */
    public int totalBacklog() {
        int sum = 0;
        for (Deque<UserMsg> q : queues.values()) sum += q.size();
        return sum;
    }

    /** 供监控/诊断：某个 chatId 当前积压条数（没有则 0） */
    public int getQueueSize(String chatId) {
        Deque<UserMsg> q = queues.get(chatId);
        return (q == null) ? 0 : q.size();
    }
    @Data
    @AllArgsConstructor
    public static class UserMsg {
        private Long   userId;
        private String text;
        private long   ts;
    }
}
