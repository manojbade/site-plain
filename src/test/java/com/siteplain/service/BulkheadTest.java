package com.siteplain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class BulkheadTest {

    @Test
    void refusesFastWithA503WhileEverySlotIsBusy() throws Exception {
        Bulkhead bulkhead = new Bulkhead("PDF rendering", 1, Duration.ofMillis(50), 5);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<String> holder = executor.submit(() -> bulkhead.call(() -> {
                started.countDown();
                await(release);
                return "done";
            }));
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

            long t0 = System.nanoTime();
            assertThatThrownBy(() -> bulkhead.call(() -> "second"))
                    .isInstanceOfSatisfying(ServiceBusyException.class, busy -> {
                        assertThat(busy.status()).isEqualTo(503);
                        assertThat(busy.retryAfterSeconds()).isEqualTo(5);
                    });
            assertThat(Duration.ofNanos(System.nanoTime() - t0)).isLessThan(Duration.ofSeconds(2));

            release.countDown();
            assertThat(holder.get(5, TimeUnit.SECONDS)).isEqualTo("done");
            assertThat(bulkhead.call(() -> "third")).isEqualTo("third");
            assertThat(bulkhead.available()).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void releasesTheSlotWhenTheWorkFails() {
        Bulkhead bulkhead = new Bulkhead("proximity query", 1, Duration.ZERO, 5);
        assertThatThrownBy(() -> bulkhead.call(() -> {
            throw new IllegalStateException("query failed");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(bulkhead.available()).isEqualTo(1);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
