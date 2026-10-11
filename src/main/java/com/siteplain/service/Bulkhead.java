package com.siteplain.service;

import com.siteplain.support.ThrottledLog;
import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A global cap on how many of one kind of expensive work run at once, whoever asks. It does not
 * depend on the client's IP, which proxies report and a determined client may be able to vary, so
 * it bounds CPU, database connections and outbound calls even when per-client limits are evaded.
 * A caller waits briefly for a slot, then gets a fast 503 with Retry-After instead of queueing.
 */
public final class Bulkhead {

    private static final Logger log = LoggerFactory.getLogger(Bulkhead.class);
    private static final ThrottledLog refusals = new ThrottledLog(log, 60_000, System::currentTimeMillis);

    private final String name;
    private final Semaphore permits;
    private final int maxConcurrent;
    private final Duration maxWait;
    private final long retryAfterSeconds;

    public Bulkhead(String name, int maxConcurrent, Duration maxWait, long retryAfterSeconds) {
        if (maxConcurrent < 1) {
            throw new IllegalArgumentException(name + " concurrency must be at least 1");
        }
        this.name = name;
        this.permits = new Semaphore(maxConcurrent);
        this.maxConcurrent = maxConcurrent;
        this.maxWait = maxWait;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    /** For tests and tools: a cap high enough never to apply. */
    public static Bulkhead unlimited(String name) {
        return new Bulkhead(name, Integer.MAX_VALUE, Duration.ZERO, 1);
    }

    public <T> T call(Supplier<T> work) {
        boolean acquired;
        try {
            acquired = permits.tryAcquire(maxWait.toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            acquired = false;
        }
        if (!acquired) {
            refusals.warn(name, "Refused " + name + ": all " + maxConcurrent + " slots busy");
            throw new ServiceBusyException(ServiceBusyException.SERVICE_UNAVAILABLE, retryAfterSeconds, name + " busy");
        }
        try {
            return work.get();
        } finally {
            permits.release();
        }
    }

    public String name() {
        return name;
    }

    public int available() {
        return permits.availablePermits();
    }
}
