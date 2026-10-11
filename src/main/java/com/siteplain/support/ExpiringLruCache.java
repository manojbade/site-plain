package com.siteplain.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * A small in-memory map with a hard size cap and a time to live per entry. Past the cap the least
 * recently used entry is dropped, so memory stays bounded whatever keys arrive. One lock guards
 * the map: the app runs one replica at low volume, and every operation is a few hash lookups.
 */
public final class ExpiringLruCache<K, V> {

    private record Entry<V>(V value, Instant expiresAt) {
    }

    private final int maxEntries;
    private final Clock clock;
    private final LinkedHashMap<K, Entry<V>> entries;

    public ExpiringLruCache(int maxEntries, Clock clock) {
        if (maxEntries < 1) {
            throw new IllegalArgumentException("maxEntries must be at least 1");
        }
        this.maxEntries = maxEntries;
        this.clock = clock;
        this.entries = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, Entry<V>> eldest) {
                return size() > ExpiringLruCache.this.maxEntries;
            }
        };
    }

    /** The live value for the key, or null when there is none or it has expired. */
    public synchronized V get(K key) {
        Entry<V> entry = entries.get(key);
        if (entry == null) {
            return null;
        }
        if (!clock.instant().isBefore(entry.expiresAt())) {
            entries.remove(key);
            return null;
        }
        return entry.value();
    }

    public synchronized void put(K key, V value, Duration ttl) {
        entries.put(key, new Entry<>(value, clock.instant().plus(ttl)));
    }

    /** The live value for the key, or a new one from the factory stored with the given time to live. */
    public synchronized V getOrCreate(K key, Supplier<V> factory, Duration ttl) {
        V existing = get(key);
        if (existing != null) {
            return existing;
        }
        V created = factory.get();
        put(key, created, ttl);
        return created;
    }

    public synchronized int size() {
        return entries.size();
    }

    public synchronized void clear() {
        entries.clear();
    }
}
