package com.siteplain.support;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.slf4j.Logger;

/**
 * WARN lines for events that can repeat thousands of times under abuse (a refused request, say):
 * at most one line per key per interval, carrying how many events it stands for, so the log shows
 * that limits are firing without being flooded by them.
 */
public final class ThrottledLog {

    private record Window(AtomicLong startMillis, AtomicLong count) {
    }

    private final Logger log;
    private final long intervalMillis;
    private final LongSupplier currentMillis;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    public ThrottledLog(Logger log, long intervalMillis, LongSupplier currentMillis) {
        this.log = log;
        this.intervalMillis = intervalMillis;
        this.currentMillis = currentMillis;
    }

    /** Counts one event for the key (a fixed label, never user input) and logs when its window is due. */
    public void warn(String key, String message) {
        long now = currentMillis.getAsLong();
        Window window = windows.computeIfAbsent(key, k -> new Window(new AtomicLong(now - intervalMillis), new AtomicLong()));
        long count = window.count().incrementAndGet();
        long start = window.startMillis().get();
        if (now - start >= intervalMillis && window.startMillis().compareAndSet(start, now)) {
            window.count().addAndGet(-count);
            log.warn("{} ({} since the last report)", message, count);
        }
    }
}
