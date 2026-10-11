package com.siteplain.web.limits;

import static org.assertj.core.api.Assertions.assertThat;

import com.siteplain.config.LimitsProperties;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class RateLimiterTest {

    private final AtomicLong nanos = new AtomicLong(1_000_000_000L);

    private RateLimiter limiter(int capacity, Duration refill, int maxClients) {
        return new RateLimiter("test", new LimitsProperties.Bucket(capacity, refill), maxClients, nanos::get, Clock.systemUTC());
    }

    @Test
    void allowsTheBurstThenRefusesWithTheSecondsToTheNextToken() {
        RateLimiter limiter = limiter(3, Duration.ofSeconds(6), 100);
        for (int i = 0; i < 3; i++) {
            assertThat(limiter.tryAcquire("v4:1.2.3.4").allowed()).isTrue();
        }
        RateLimiter.Decision refused = limiter.tryAcquire("v4:1.2.3.4");
        assertThat(refused.allowed()).isFalse();
        assertThat(refused.retryAfterSeconds()).isEqualTo(6);

        nanos.addAndGet(Duration.ofSeconds(4).toNanos());
        assertThat(limiter.tryAcquire("v4:1.2.3.4").retryAfterSeconds()).isEqualTo(2);
        nanos.addAndGet(Duration.ofSeconds(2).toNanos());
        assertThat(limiter.tryAcquire("v4:1.2.3.4").allowed()).isTrue();
        assertThat(limiter.tryAcquire("v4:1.2.3.4").allowed()).isFalse();
    }

    @Test
    void refillsUpToCapacityOnly() {
        RateLimiter limiter = limiter(2, Duration.ofSeconds(1), 100);
        limiter.tryAcquire("a");
        limiter.tryAcquire("a");
        nanos.addAndGet(Duration.ofHours(1).toNanos());
        assertThat(limiter.tryAcquire("a").allowed()).isTrue();
        assertThat(limiter.tryAcquire("a").allowed()).isTrue();
        assertThat(limiter.tryAcquire("a").allowed()).isFalse();
    }

    @Test
    void clientsHaveSeparateBuckets() {
        RateLimiter limiter = limiter(1, Duration.ofMinutes(1), 100);
        assertThat(limiter.tryAcquire("a").allowed()).isTrue();
        assertThat(limiter.tryAcquire("a").allowed()).isFalse();
        assertThat(limiter.tryAcquire("b").allowed()).isTrue();
    }

    @Test
    void tracksABoundedNumberOfClients() {
        RateLimiter limiter = limiter(1, Duration.ofMinutes(1), 2);
        limiter.tryAcquire("a");
        limiter.tryAcquire("b");
        limiter.tryAcquire("c");
        // "a" was the least recently seen and was dropped, so it starts again with a full bucket.
        assertThat(limiter.tryAcquire("a").allowed()).isTrue();
        assertThat(limiter.tryAcquire("c").allowed()).isFalse();
    }
}
