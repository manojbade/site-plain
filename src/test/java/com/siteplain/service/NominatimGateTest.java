package com.siteplain.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class NominatimGateTest {

    private final AtomicLong now = new AtomicLong(0);
    private final List<Long> sleeps = new ArrayList<>();
    private final NominatimGate gate = new NominatimGate(Duration.ofSeconds(1), Duration.ofSeconds(3), now::get, nanos -> {
        sleeps.add(nanos);
        now.addAndGet(nanos);
    });

    @Test
    void spacesCallsOneSecondApart() {
        assertThat(gate.awaitTurn()).isTrue();
        assertThat(gate.awaitTurn()).isTrue();
        now.addAndGet(Duration.ofMillis(300).toNanos());
        assertThat(gate.awaitTurn()).isTrue();

        assertThat(sleeps).containsExactly(Duration.ofSeconds(1).toNanos(), Duration.ofMillis(700).toNanos());
    }

    @Test
    void refusesWhenTheNextFreeSlotIsTooFarOff() {
        // Four callers at the same instant take slots 0, 1, 2 and 3 seconds out (no sleeping here).
        NominatimGate busy = new NominatimGate(Duration.ofSeconds(1), Duration.ofSeconds(3), now::get, nanos -> { });
        for (int i = 0; i < 4; i++) {
            assertThat(busy.awaitTurn()).isTrue();
        }
        assertThat(busy.awaitTurn()).isFalse();
        assertThat(busy.secondsUntilFree()).isEqualTo(1);

        now.addAndGet(Duration.ofSeconds(1).toNanos());
        assertThat(busy.awaitTurn()).isTrue();
    }
}
