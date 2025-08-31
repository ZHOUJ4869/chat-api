package com.jz.ai.config;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean(name = "chatAsyncExecutor")
    public ThreadPoolTaskExecutor chatAsyncExecutor() {
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(4);
        ex.setMaxPoolSize(16);
        ex.setQueueCapacity(1000);
        ex.setKeepAliveSeconds(60);
        ex.setThreadNamePrefix("chat-async-");
        ex.setAwaitTerminationSeconds(10);
        ex.setWaitForTasksToCompleteOnShutdown(true);
        ex.initialize();
        return ex;
    }

    @Bean("lmsExecutor")
    public Executor lmsExecutor() {
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(2);
        ex.setMaxPoolSize(8);
        ex.setQueueCapacity(200);
        ex.setThreadNamePrefix("lms-");
        ex.initialize();
        return ex;
    }

    // 可选：处理无返回值 @Async 方法的未捕获异常
    @Bean
    public AsyncUncaughtExceptionHandler asyncUncaughtExceptionHandler() {
        return (ex, method, params) ->
                System.err.println("Async error in " + method.getName() + ": " + ex.getMessage());
    }

    /** 专用续期线程池（单线程守护） */
    @Bean(name = "lockRenewScheduler")
    public ScheduledExecutorService lockRenewScheduler() {
        return Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "lock-renewer");
            t.setDaemon(true);
            return t;
        });
    }
}
