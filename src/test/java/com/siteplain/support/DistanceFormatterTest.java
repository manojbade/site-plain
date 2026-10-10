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
    void format_zeroMetersSaysInsideBoundary() {
        assertThat(DistanceFormatter.format(0)).isEqualTo("Inside the EPA site boundary");
    }

    @Test
    void format_underTenFeetOutsideUsesImmediateAdjacencyMessage() {
        assertThat(DistanceFormatter.format(1.5)).isEqualTo("Less than 10 feet (immediately adjacent to site boundary)");
    }
}
