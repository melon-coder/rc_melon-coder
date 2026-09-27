package com.example.notify.circuit;

import com.example.notify.config.NotifyProperties;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按 supplier 维度的熔断器（计数型）。
 *
 * 状态机：CLOSED --连续失败达阈值--> OPEN --冷却期满--> HALF_OPEN
 *         HALF_OPEN --试探成功--> CLOSED；--试探失败--> OPEN
 *
 * 目的：某供应商宕机时快速失败、停止无谓请求，避免持续打满自己的线程池与连接；
 * 冷却后以「半开」状态放行一个试探请求，成功后自动恢复。
 */
@Component
public class CircuitBreaker {

    private enum State { CLOSED, OPEN, HALF_OPEN }

    private static final class Entry {
        private State state = State.CLOSED;
        private int failures;
        private long openedAt;
    }

    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();
    private final NotifyProperties properties;
    private final Clock clock;

    public CircuitBreaker(NotifyProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * 判断是否应短路该 supplier 的请求。
     * OPEN 且未到冷却期 → 短路；冷却期满 → 转 HALF_OPEN 并放行一个试探请求。
     */
    public boolean isOpen(String supplier) {
        Entry entry = entries.computeIfAbsent(supplier, k -> new Entry());
        synchronized (entry) {
            if (entry.state == State.OPEN) {
                long cooldownMs = properties.getCircuit().getCooldownSeconds() * 1000L;
                if (clock.millis() - entry.openedAt >= cooldownMs) {
                    entry.state = State.HALF_OPEN;
                    return false;
                }
                return true;
            }
            return false;
        }
    }

    public void onSuccess(String supplier) {
        Entry entry = entries.computeIfAbsent(supplier, k -> new Entry());
        synchronized (entry) {
            entry.state = State.CLOSED;
            entry.failures = 0;
        }
    }

    public void onFailure(String supplier) {
        Entry entry = entries.computeIfAbsent(supplier, k -> new Entry());
        synchronized (entry) {
            // 半开状态的试探失败 → 立即重新开闸
            if (entry.state == State.HALF_OPEN) {
                entry.state = State.OPEN;
                entry.openedAt = clock.millis();
                return;
            }
            entry.failures++;
            if (entry.failures >= properties.getCircuit().getFailureThreshold()) {
                entry.state = State.OPEN;
                entry.openedAt = clock.millis();
            }
        }
    }
}
