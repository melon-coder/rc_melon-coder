package com.example.notify.circuit;

import com.example.notify.config.NotifyProperties;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

/**
 * 按 supplier 维度的并发限流：每供应商一个信号量，限制同时在途的投递请求数，
 * 防止单个慢/坏供应商占满全部投递线程，从而影响其他供应商的通知。
 */
@Component
public class ConcurrencyLimiter {

    private final ConcurrentHashMap<String, Semaphore> semaphores = new ConcurrentHashMap<>();
    private final NotifyProperties properties;

    public ConcurrencyLimiter(NotifyProperties properties) {
        this.properties = properties;
    }

    public boolean tryAcquire(String supplier) {
        Semaphore semaphore = semaphores.computeIfAbsent(supplier,
                k -> new Semaphore(properties.getCircuit().getPerSupplierMaxConcurrency()));
        return semaphore.tryAcquire();
    }

    public void release(String supplier) {
        Semaphore semaphore = semaphores.get(supplier);
        if (semaphore != null) {
            semaphore.release();
        }
    }
}
