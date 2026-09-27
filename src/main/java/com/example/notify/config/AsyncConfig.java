package com.example.notify.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

@Configuration
public class AsyncConfig {

    /**
     * 投递专用线程池（有界）。
     * 拒绝策略用 CallerRunsPolicy：线程池饱和时由调度线程直接执行，
     * 形成天然背压，放缓 poll 捞取节奏，避免任务无限堆积。
     */
    @Bean(name = "deliveryExecutor")
    public ThreadPoolTaskExecutor deliveryExecutor(NotifyProperties properties) {
        int max = properties.getDispatcher().getMaxConcurrency();
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(max);
        executor.setMaxPoolSize(max);
        executor.setQueueCapacity(properties.getDispatcher().getBatchSize() * 2);
        executor.setThreadNamePrefix("delivery-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}
