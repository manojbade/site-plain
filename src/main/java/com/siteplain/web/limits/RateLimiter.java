package com.siteplain.web.limits;

import com.siteplain.config.LimitsProperties;
import com.siteplain.support.ExpiringLruCache;
import java.time.Clock;
import java.time.Duration;
import java.util.function.LongSupplier;

/**
 * Per-client token buckets for one endpoint: up to capacity requests at once, then one more per
 * refill interval. In memory, one replica, no new infrastructure; the number of clients tracked is
 * capped, dropping the least recently seen (which only hands that client a fresh, full bucket).
 */
public final class RateLimiter {

    /** Allowed, or refused with the whole seconds until the next token. */
    public record Decision(boolean allowed, long retryAfterSeconds) {
        static final Decision ALLOWED = new Decision(true, 0);
    }

    private final String name;
    private final int capacity;
    private final long refillNanos;
    private final LongSupplier nanoTime;
    private final ExpiringLruCache<String, Bucket> buckets;
    private final Duration idleReset;

    public RateLimiter(String name, LimitsProperties.Bucket config, int maxClients, LongSupplier nanoTime, Clock clock) {
        if (config.getCapacity() < 1 || config.getRefill() == null || config.getRefill().isZero() || config.getRefill().isNegative()) {
            throw new IllegalArgumentException(name + " rate limit needs a capacity of at least 1 and a positive refill");
        }
        this.name = name;
        this.capacity = config.getCapacity();
        this.refillNanos = config.getRefill().toNanos();
        this.nanoTime = nanoTime;
        this.buckets = new ExpiringLruCache<>(maxClients, clock);
        // An idle bucket is full again after capacity refills, the same as a new one, so it can go.
        this.idleReset = config.getRefill().multipliedBy(capacity);
    }

    public String name() {
        return name;
    }

    public Decision tryAcquire(String client) {
        Bucket bucket = buckets.getOrCreate(client, () -> new Bucket(capacity, nanoTime.getAsLong()), idleReset);
        Decision decision = bucket.take(capacity, refillNanos, nanoTime.getAsLong());
        // Seen again, so keep it for another full-refill period.
        buckets.put(client, bucket, idleReset);
        return decision;
    }

    private static final class Bucket {
        private double tokens;
        private long updatedNanos;

        Bucket(int capacity, long now) {
            this.tokens = capacity;
            this.updatedNanos = now;
        }

        synchronized Decision take(int capacity, long refillNanos, long now) {
            if (now > updatedNanos) {
                tokens = Math.min(capacity, tokens + (double) (now - updatedNanos) / refillNanos);
                updatedNanos = now;
            }
            if (tokens >= 1.0) {
                tokens -= 1.0;
                return Decision.ALLOWED;
            }
            // Whole seconds, rounded up; the small allowance keeps float noise from adding a second.
            double secondsToNext = (1.0 - tokens) * refillNanos / 1e9;
            return new Decision(false, Math.max(1, (long) Math.ceil(secondsToNext - 1e-6)));
        }
    }
}
