package com.example.notify.metrics;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 投递指标的最小内存实现：用原子计数器累计投递成功/失败/死信次数。
 * 配合 /api/metrics 端点暴露；生产可替换为 Micrometer + Prometheus 的完整监控体系。
 */
@Component
public class NotificationMetrics {

    private final AtomicLong delivered = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final AtomicLong dead = new AtomicLong();

    public void recordDelivered() {
        delivered.incrementAndGet();
    }

    public void recordFailed() {
        failed.incrementAndGet();
    }

    public void recordDead() {
        dead.incrementAndGet();
    }

    public long getDelivered() {
        return delivered.get();
    }

    public long getFailed() {
        return failed.get();
    }

    public long getDead() {
        return dead.get();
    }
}
