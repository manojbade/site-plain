package com.siteplain.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class ExpiringLruCacheTest {

    private static final Instant T0 = Instant.parse("2026-10-10T12:00:00Z");

    @Test
    void entriesExpireAfterTheirTimeToLive() {
        ExpiringLruCache<String, String> cache = new ExpiringLruCache<>(10, Clock.fixed(T0, ZoneOffset.UTC));
        cache.put("a", "1", Duration.ofMinutes(1));
        assertThat(cache.get("a")).isEqualTo("1");

        ExpiringLruCache<String, String> later = new ExpiringLruCache<>(10, Clock.fixed(T0, ZoneOffset.UTC));
        later.put("a", "1", Duration.ZERO);
        assertThat(later.get("a")).isNull();
        assertThat(later.size()).isZero();
    }

    @Test
    void theLeastRecentlyUsedEntryGoesPastTheCap() {
        ExpiringLruCache<String, String> cache = new ExpiringLruCache<>(2, Clock.fixed(T0, ZoneOffset.UTC));
        cache.put("a", "1", Duration.ofHours(1));
        cache.put("b", "2", Duration.ofHours(1));
        cache.get("a");
        cache.put("c", "3", Duration.ofHours(1));

        assertThat(cache.size()).isEqualTo(2);
        assertThat(cache.get("b")).isNull();
        assertThat(cache.get("a")).isEqualTo("1");
        assertThat(cache.get("c")).isEqualTo("3");
    }

    @Test
    void getOrCreateMakesOneValuePerKey() {
        ExpiringLruCache<String, Object> cache = new ExpiringLruCache<>(2, Clock.fixed(T0, ZoneOffset.UTC));
        Object first = cache.getOrCreate("a", Object::new, Duration.ofHours(1));
        assertThat(cache.getOrCreate("a", Object::new, Duration.ofHours(1))).isSameAs(first);
    }
}
