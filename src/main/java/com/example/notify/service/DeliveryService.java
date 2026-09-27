package com.example.notify.service;

import com.example.notify.circuit.CircuitBreaker;
import com.example.notify.circuit.ConcurrencyLimiter;
import com.example.notify.config.NotifyProperties;
import com.example.notify.dispatcher.HttpSender;
import com.example.notify.metrics.NotificationMetrics;
import com.example.notify.model.Notification;
import com.example.notify.model.NotificationStatus;
import com.example.notify.repository.NotificationRepository;
import com.example.notify.retry.BackoffPolicy;
import com.example.notify.support.DeadLetterNotifier;
import com.example.notify.support.DeliveryResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * 单条通知的投递执行器，由 Dispatcher 的有界线程池调用。
 *
 * 核心职责：对一条已认领（DELIVERING）的通知执行一次 HTTP 投递，
 * 依据结果推进状态机——2xx → SUCCESS；失败 → 退避重试(RETRY)或进死信(DEAD)。
 * 投递前先过熔断与并发限流，避免在供应商宕机时无谓打满自身资源。
 */
@Service
public class DeliveryService {

    private static final Logger log = LoggerFactory.getLogger(DeliveryService.class);

    private final NotificationRepository repository;
    private final HttpSender httpSender;
    private final BackoffPolicy backoffPolicy;
    private final CircuitBreaker circuitBreaker;
    private final ConcurrencyLimiter concurrencyLimiter;
    private final NotifyProperties properties;
    private final NotificationMetrics metrics;
    private final DeadLetterNotifier deadLetterNotifier;
    private final Clock clock;

    public DeliveryService(NotificationRepository repository,
                           HttpSender httpSender,
                           BackoffPolicy backoffPolicy,
                           CircuitBreaker circuitBreaker,
                           ConcurrencyLimiter concurrencyLimiter,
                           NotifyProperties properties,
                           NotificationMetrics metrics,
                           DeadLetterNotifier deadLetterNotifier,
                           Clock clock) {
        this.repository = repository;
        this.httpSender = httpSender;
        this.backoffPolicy = backoffPolicy;
        this.circuitBreaker = circuitBreaker;
        this.concurrencyLimiter = concurrencyLimiter;
        this.properties = properties;
        this.metrics = metrics;
        this.deadLetterNotifier = deadLetterNotifier;
        this.clock = clock;
    }

    public void deliver(Long id) {
        Notification notification = repository.findById(id).orElse(null);
        // 只处理被 claim 过的在途任务；否则说明已被并发处理或状态已变，直接跳过
        if (notification == null || notification.getStatus() != NotificationStatus.DELIVERING) {
            return;
        }

        String supplier = notification.getSupplier();
        // 熔断打开：不做无谓请求，重新排期等待冷却，避免持续冲击宕机的供应商
        if (circuitBreaker.isOpen(supplier)) {
            reschedule(notification, "circuit breaker open for supplier: " + supplier);
            return;
        }
        // 并发限流：超过该供应商的在途上限则让出，稍后重试（不消耗尝试次数）
        if (!concurrencyLimiter.tryAcquire(supplier)) {
            reschedule(notification, "concurrency limit reached for supplier: " + supplier);
            return;
        }

        int attempt = notification.getAttemptCount() + 1;
        DeliveryResult result;
        try {
            result = httpSender.send(notification);
        } finally {
            // 无论成败都释放信号量，避免在途计数泄漏
            concurrencyLimiter.release(supplier);
        }

        if (result.isSuccess()) {
            circuitBreaker.onSuccess(supplier);
            markSuccess(notification, attempt);
        } else {
            circuitBreaker.onFailure(supplier);
            handleFailure(notification, attempt, result.getError());
        }
    }

    private void markSuccess(Notification n, int attempt) {
        log.info("notification {} delivered successfully after {} attempt(s)", n.getNotifyId(), attempt);
        metrics.recordDelivered();
        n.setAttemptCount(attempt);
        n.setStatus(NotificationStatus.SUCCESS);
        n.setNextRetryAt(null);
        n.setLastError(null);
        n.setUpdatedAt(LocalDateTime.now(clock));
        repository.save(n);
    }

    private void handleFailure(Notification n, int attempt, String error) {
        LocalDateTime now = LocalDateTime.now(clock);
        n.setAttemptCount(attempt);
        n.setLastError(error);
        n.setUpdatedAt(now);
        metrics.recordFailed();
        // 终止条件二选一：达到重试上限，或超过最终截止时间（应对「长期不可用」的兜底）
        if (attempt >= n.getMaxAttempts() || !now.isBefore(n.getDeadlineAt())) {
            n.setStatus(NotificationStatus.DEAD);
            n.setNextRetryAt(null);
            // 死信：计数并触发告警通道，保证「长期不可用」时可见、可干预
            metrics.recordDead();
            deadLetterNotifier.notifyDead(n);
        } else {
            n.setStatus(NotificationStatus.RETRY);
            n.setNextRetryAt(now.plus(backoffPolicy.nextDelay(attempt)));
        }
        repository.save(n);
    }

    /**
     * 因熔断/限流而暂缓：保持为 RETRY 并延后重试，但不消耗尝试次数，
     * 因为并未真正发出请求。这样供应商恢复后仍保有完整重试预算。
     */
    private void reschedule(Notification n, String reason) {
        n.setStatus(NotificationStatus.RETRY);
        n.setNextRetryAt(LocalDateTime.now(clock).plus(backoffPolicy.nextDelay(n.getAttemptCount() + 1)));
        n.setLastError(reason);
        n.setUpdatedAt(LocalDateTime.now(clock));
        repository.save(n);
    }
}
