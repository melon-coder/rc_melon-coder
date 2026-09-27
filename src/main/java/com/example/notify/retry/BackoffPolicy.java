package com.example.notify.retry;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Random;

/**
 * 指数退避策略：重试间隔随尝试次数指数增长并封顶，叠加随机抖动。
 *
 * 抖动（jitter）的目的：避免大量失败任务在同一时刻集体重试，
 * 对下游供应商形成「惊群」式冲击；把重试分散到时间轴上。
 * 基准序列（秒）：1, 5, 30, 120, 600, 1800, 3600, 10800, 21600, 43200（12h 封顶）。
 */
@Component
public class BackoffPolicy {

    private static final long[] DELAY_SECONDS = {1L, 5L, 30L, 120L, 600L, 1800L, 3600L, 10800L, 21600L, 43200L};

    private final Random random;

    public BackoffPolicy() {
        this(new Random());
    }

    public BackoffPolicy(Random random) {
        this.random = random;
    }

    /**
     * @param attempt 已尝试次数（>=1），越界自动夹取到封顶档位
     * @return 叠加了 ±20% 抖动后的重试间隔
     */
    public Duration nextDelay(int attempt) {
        int idx = Math.min(Math.max(attempt, 1) - 1, DELAY_SECONDS.length - 1);
        long base = DELAY_SECONDS[idx];
        double jitter = 0.8 + 0.4 * random.nextDouble();
        long seconds = Math.max(1L, Math.round(base * jitter));
        return Duration.ofSeconds(seconds);
    }
}
