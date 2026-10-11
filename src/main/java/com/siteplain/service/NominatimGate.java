package com.siteplain.service;

import java.time.Duration;
import java.util.function.LongSupplier;

/**
 * Spaces calls to the public Nominatim server at least one interval apart across the whole app,
 * per the OSMF usage policy's absolute maximum of one request per second. Callers take the next
 * free slot and sleep until it; when the next slot is further away than the longest wait, the
 * call is refused instead (the lookup then answers busy, not "address not found").
 */
public final class NominatimGate {

    /** Sleeps for the given nanoseconds; replaceable in tests. */
    public interface Sleeper {
        void sleepNanos(long nanos) throws InterruptedException;
    }

    private final long intervalNanos;
    private final long maxWaitNanos;
    private final LongSupplier nanoTime;
    private final Sleeper sleeper;
    private long nextSlotNanos;
    private boolean anyCall;

    public NominatimGate(Duration interval, Duration maxWait, LongSupplier nanoTime, Sleeper sleeper) {
        this.intervalNanos = interval.toNanos();
        this.maxWaitNanos = maxWait.toNanos();
        this.nanoTime = nanoTime;
        this.sleeper = sleeper;
    }

    public static NominatimGate realTime(Duration interval, Duration maxWait) {
        return new NominatimGate(interval, maxWait, System::nanoTime,
                nanos -> Thread.sleep(nanos / 1_000_000, (int) (nanos % 1_000_000)));
    }

    /** True once the caller may send its request; false when the wait would be too long. */
    public boolean awaitTurn() {
        long waitNanos;
        synchronized (this) {
            long now = nanoTime.getAsLong();
            long slot = anyCall ? Math.max(now, nextSlotNanos) : now;
            waitNanos = slot - now;
            if (waitNanos > maxWaitNanos) {
                return false;
            }
            nextSlotNanos = slot + intervalNanos;
            anyCall = true;
        }
        if (waitNanos > 0) {
            try {
                sleeper.sleepNanos(waitNanos);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }

    /** Whole seconds until a slot within the longest wait opens, for Retry-After. */
    public synchronized long secondsUntilFree() {
        long behind = nextSlotNanos - nanoTime.getAsLong() - maxWaitNanos;
        return Math.max(1, (behind + 999_999_999L) / 1_000_000_000L);
    }
}
