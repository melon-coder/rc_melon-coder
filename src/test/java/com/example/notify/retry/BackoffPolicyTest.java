package com.example.notify.retry;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertTrue;

class BackoffPolicyTest {

    @Test
    void delayGrowsWithAttempts() {
        BackoffPolicy policy = new BackoffPolicy(new Random(1));
        Duration first = policy.nextDelay(1);
        Duration later = policy.nextDelay(6);
        assertTrue(later.compareTo(first) > 0, "delay should grow with attempts");
    }

    @Test
    void delayIsCappedAroundMaxBase() {
        BackoffPolicy policy = new BackoffPolicy(new Random(1));
        for (int i = 10; i <= 20; i++) {
            long seconds = policy.nextDelay(i).getSeconds();
            assertTrue(seconds >= 34560 && seconds <= 51840,
                    "delay should stay around the 12h cap, got " + seconds);
        }
    }

    @Test
    void delayIsAtLeastOneSecond() {
        BackoffPolicy policy = new BackoffPolicy(new Random(1));
        for (int i = 1; i <= 20; i++) {
            assertTrue(policy.nextDelay(i).getSeconds() >= 1, "delay must be at least 1s");
        }
    }
}
