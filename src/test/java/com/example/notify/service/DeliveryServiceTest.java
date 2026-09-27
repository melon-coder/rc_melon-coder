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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DeliveryServiceTest {

    @Mock
    private NotificationRepository repository;
    @Mock
    private HttpSender httpSender;
    @Mock
    private CircuitBreaker circuitBreaker;
    @Mock
    private ConcurrencyLimiter concurrencyLimiter;

    private DeliveryService deliveryService;
    private NotificationMetrics metrics;
    private final Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        NotifyProperties props = new NotifyProperties();
        BackoffPolicy backoffPolicy = new BackoffPolicy(new Random(1));
        metrics = new NotificationMetrics();
        DeadLetterNotifier deadLetterNotifier = n -> { };
        deliveryService = new DeliveryService(repository, httpSender, backoffPolicy,
                circuitBreaker, concurrencyLimiter, props, metrics, deadLetterNotifier, clock);
        when(concurrencyLimiter.tryAcquire(any())).thenReturn(true);
    }

    private Notification delivering(int attemptCount, int maxAttempts, LocalDateTime deadline) {
        Notification n = new Notification();
        n.setId(1L);
        n.setNotifyId("n-1");
        n.setSupplier("s1");
        n.setStatus(NotificationStatus.DELIVERING);
        n.setAttemptCount(attemptCount);
        n.setMaxAttempts(maxAttempts);
        n.setDeadlineAt(deadline);
        n.setUrl("http://localhost/cb");
        n.setMethod("POST");
        n.setHeaders("{}");
        return n;
    }

    @Test
    void marksSuccessOn2xx() {
        Notification n = delivering(0, 15, LocalDateTime.now(clock).plusDays(1));
        when(repository.findById(1L)).thenReturn(Optional.of(n));
        when(httpSender.send(n)).thenReturn(DeliveryResult.success());

        deliveryService.deliver(1L);

        verify(repository).save(n);
        assertEquals(NotificationStatus.SUCCESS, n.getStatus());
        assertEquals(1, n.getAttemptCount());
        verify(circuitBreaker).onSuccess("s1");
    }

    @Test
    void marksRetryOnFailureAndBacksOff() {
        Notification n = delivering(0, 15, LocalDateTime.now(clock).plusDays(1));
        when(repository.findById(1L)).thenReturn(Optional.of(n));
        when(httpSender.send(n)).thenReturn(DeliveryResult.failure("HTTP 500"));

        deliveryService.deliver(1L);

        verify(repository).save(n);
        assertEquals(NotificationStatus.RETRY, n.getStatus());
        assertEquals(1, n.getAttemptCount());
        assertTrue(n.getNextRetryAt().isAfter(LocalDateTime.now(clock)));
        verify(circuitBreaker).onFailure("s1");
    }

    @Test
    void movesToDeadWhenAttemptsExhausted() {
        Notification n = delivering(4, 5, LocalDateTime.now(clock).plusDays(1));
        when(repository.findById(1L)).thenReturn(Optional.of(n));
        when(httpSender.send(n)).thenReturn(DeliveryResult.failure("HTTP 500"));

        deliveryService.deliver(1L);

        verify(repository).save(n);
        assertEquals(NotificationStatus.DEAD, n.getStatus());
        assertEquals(1, metrics.getDead());
    }

    @Test
    void movesToDeadWhenDeadlineExceeded() {
        Notification n = delivering(0, 15, LocalDateTime.now(clock).minusSeconds(1));
        when(repository.findById(1L)).thenReturn(Optional.of(n));
        when(httpSender.send(n)).thenReturn(DeliveryResult.failure("HTTP 500"));

        deliveryService.deliver(1L);

        verify(repository).save(n);
        assertEquals(NotificationStatus.DEAD, n.getStatus());
    }

    @Test
    void skipsWhenCircuitOpen() {
        Notification n = delivering(0, 15, LocalDateTime.now(clock).plusDays(1));
        when(repository.findById(1L)).thenReturn(Optional.of(n));
        when(circuitBreaker.isOpen("s1")).thenReturn(true);

        deliveryService.deliver(1L);

        verify(repository).save(n);
        assertEquals(NotificationStatus.RETRY, n.getStatus());
        verify(httpSender, never()).send(any());
    }

    @Test
    void skipsWhenConcurrencyExhausted() {
        Notification n = delivering(0, 15, LocalDateTime.now(clock).plusDays(1));
        when(repository.findById(1L)).thenReturn(Optional.of(n));
        when(concurrencyLimiter.tryAcquire("s1")).thenReturn(false);

        deliveryService.deliver(1L);

        verify(repository).save(n);
        assertEquals(NotificationStatus.RETRY, n.getStatus());
        verify(httpSender, never()).send(any());
    }
}
