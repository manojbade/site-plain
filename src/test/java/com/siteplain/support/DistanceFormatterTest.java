package com.siteplain.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DistanceFormatterTest {

    @Test
    void format_showsFeetForSmallDistances() {
        assertThat(DistanceFormatter.format(100)).contains("feet");
    }

    @Test
    void format_roundsHalfKilometerToTenthsOfMiles() {
        assertThat(DistanceFormatter.format(500)).contains("0.3 miles");
    }

    @Test
    void format_roundsFiveKilometersToMiles() {
        assertThat(DistanceFormatter.format(5000)).isEqualTo("3.1 miles");
    }

    @Test
    void format_zeroMetersUsesImmediateAdjacencyMessage() {
        assertThat(DistanceFormatter.format(0)).contains("Less than 10 feet");
    }
}
