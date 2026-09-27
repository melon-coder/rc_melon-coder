package com.example.notify.circuit;

import com.example.notify.config.NotifyProperties;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CircuitBreakerTest {

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration duration) {
            this.instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    private NotifyProperties props(int threshold, int cooldown) {
        NotifyProperties p = new NotifyProperties();
        p.getCircuit().setFailureThreshold(threshold);
        p.getCircuit().setCooldownSeconds(cooldown);
        return p;
    }

    @Test
    void opensAfterThresholdConsecutiveFailures() {
        CircuitBreaker cb = new CircuitBreaker(props(3, 60), Clock.systemDefaultZone());
        assertFalse(cb.isOpen("s"));
        cb.onFailure("s");
        cb.onFailure("s");
        assertFalse(cb.isOpen("s"));
        cb.onFailure("s");
        assertTrue(cb.isOpen("s"));
    }

    @Test
    void successResetsFailureCount() {
        CircuitBreaker cb = new CircuitBreaker(props(3, 60), Clock.systemDefaultZone());
        cb.onFailure("s");
        cb.onFailure("s");
        cb.onSuccess("s");
        cb.onFailure("s");
        cb.onFailure("s");
        assertFalse(cb.isOpen("s"), "success should have reset the failure count");
    }

    @Test
    void openCircuitAllowsProbeAfterCooldown() {
        MutableClock clock = new MutableClock(Instant.EPOCH);
        CircuitBreaker cb = new CircuitBreaker(props(2, 60), clock);
        cb.onFailure("s");
        cb.onFailure("s");
        assertTrue(cb.isOpen("s"), "should be open before cooldown");
        clock.advance(Duration.ofSeconds(61));
        assertFalse(cb.isOpen("s"), "should allow a probe after cooldown (half-open)");
    }

    @Test
    void halfOpenFailureReopensCircuit() {
        MutableClock clock = new MutableClock(Instant.EPOCH);
        CircuitBreaker cb = new CircuitBreaker(props(2, 60), clock);
        cb.onFailure("s");
        cb.onFailure("s");
        clock.advance(Duration.ofSeconds(61));
        assertFalse(cb.isOpen("s"), "probe allowed in half-open");
        cb.onFailure("s");
        assertTrue(cb.isOpen("s"), "failed probe should reopen the circuit");
    }

    @Test
    void halfOpenSuccessClosesCircuit() {
        MutableClock clock = new MutableClock(Instant.EPOCH);
        CircuitBreaker cb = new CircuitBreaker(props(2, 60), clock);
        cb.onFailure("s");
        cb.onFailure("s");
        clock.advance(Duration.ofSeconds(61));
        cb.onSuccess("s");
        assertFalse(cb.isOpen("s"));
        cb.onFailure("s");
        assertFalse(cb.isOpen("s"), "a single failure after closing should not reopen");
    }
}
