package com.jz.ai.utils;


import com.jz.ai.domain.entity.AgentUserRapport;
import com.jz.ai.mapper.AgentUserRapportMapper;
import io.micrometer.core.instrument.*;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * 异步批落库：
 * - offer() 入队（非阻塞）；
 * - 满 BATCH_SIZE 或到 FLUSH_MS 刷一次；
 * - 去重（同键只保留最后一条），防止频繁抖动；
 * - 单线程执行，避免写冲突与锁竞争。
 */
@Component
@RequiredArgsConstructor
public class RapportPersistWorker {

    private final AgentUserRapportMapper mapper;
    private final MeterRegistry registry;

    private final BlockingQueue<AgentUserRapport> q = new LinkedBlockingQueue<>(100_000);
    private Thread worker = new Thread(this::loop, "rapport-persist");
    private volatile boolean running = true;

    private static final int  BATCH_SIZE = 100;  // 满 100 条刷一次
    private static final long FLUSH_MS   = 500;  // 或 500ms 定时刷

    // 指标
    private Counter offerReject ;
    private Counter flushCount ;
    private Timer   flushTimer ;

    @PostConstruct
    public void start() {
        // ★ 这里 registry 已注入，可安全注册指标
        this.offerReject = Counter.builder("rapport.persist.offer.reject")
                .description("Number of rejected offers when queue is full")
                .register(registry);

        this.flushCount = Counter.builder("rapport.persist.flush.count")
                .description("Number of batch flushes to DB")
                .register(registry);

        this.flushTimer = Timer.builder("rapport.persist.flush.latency")
                .description("Latency of batch upsert to DB")
                .register(registry);

        Gauge.builder("rapport.persist.queue.size", q, BlockingQueue::size)
                .description("Queue size of pending rapport writes")
                .register(registry);

        // 启动消费者线程
        this.worker = new Thread(this::loop, "rapport-persist");
        this.worker.setDaemon(true);
        this.worker.start();
    }

    @PreDestroy
    public void stop() {
        running = false;
        worker.interrupt();
    }

    /** 生产者：将更新对象丢到队列 */
    public void offer(AgentUserRapport r) {
        if (!q.offer(r)) {
            offerReject.increment();
            // 队列满了可考虑：阻塞 put / 退化同步写 / 丢弃并打标记等策略
        }
    }

    /** 消费者循环：合并去重后一次 upsert */
    private void loop() {
        List<AgentUserRapport> buf = new ArrayList<>(BATCH_SIZE * 2);
        while (running) {
            try {
                AgentUserRapport first = q.poll(FLUSH_MS, TimeUnit.MILLISECONDS);
                if (first != null) buf.add(first);
                q.drainTo(buf, BATCH_SIZE - 1);

                if (!buf.isEmpty()) {
                    var sample = Timer.start(registry);

                    // 去重：同一个 (agentId:userId) 只保留最后一次
                    Map<String, AgentUserRapport> last = buf.stream().collect(
                            Collectors.toMap(
                                    r -> r.getAgentId() + ":" + r.getUserId(),
                                    r -> r,
                                    (a, b) -> b
                            )
                    );

                    // 批量 UPSERT（见下方 Mapper XML）
                    mapper.upsertBatch(new ArrayList<>(last.values()));

                    sample.stop(flushTimer);
                    flushCount.increment();
                    buf.clear();
                }
            } catch (InterruptedException ignore) {
                // 正常退出或继续轮询
            } catch (Exception e) {
                // 写库失败不可杀死线程，打印错误并继续
                e.printStackTrace();
            }
        }
    }
}
