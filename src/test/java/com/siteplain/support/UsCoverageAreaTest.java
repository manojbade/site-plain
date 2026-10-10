package com.siteplain.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class UsCoverageAreaTest {

    @Test
    void acceptsStatesAndTerritoriesWithNplSites() {
        assertThat(UsCoverageArea.contains(42.4925, -71.1497)).isTrue();   // Woburn, MA
        assertThat(UsCoverageArea.contains(24.55, -81.78)).isTrue();       // Key West, FL
        assertThat(UsCoverageArea.contains(48.99, -124.7)).isTrue();       // northwest Washington
        assertThat(UsCoverageArea.contains(64.66, -147.08)).isTrue();      // Eielson AFB, AK
        assertThat(UsCoverageArea.contains(51.88, -176.65)).isTrue();      // Adak, AK
        assertThat(UsCoverageArea.contains(52.84, 173.18)).isTrue();       // Attu, AK
        assertThat(UsCoverageArea.contains(21.31, -157.86)).isTrue();      // Honolulu, HI
        assertThat(UsCoverageArea.contains(18.4655, -66.1057)).isTrue();   // San Juan, PR
        assertThat(UsCoverageArea.contains(17.73, -64.78)).isTrue();       // St. Croix, VI
        assertThat(UsCoverageArea.contains(13.44, 144.79)).isTrue();       // Guam
        assertThat(UsCoverageArea.contains(15.21, 145.73)).isTrue();       // Saipan, MP
        assertThat(UsCoverageArea.contains(-14.36, -170.77)).isTrue();     // American Samoa
        assertThat(UsCoverageArea.contains(7.41, 151.78)).isTrue();        // Chuuk, FM
    }

    @Test
    void rejectsMissingNonFiniteAndOutOfRangeValues() {
        assertThat(UsCoverageArea.contains(null, -71.0)).isFalse();
        assertThat(UsCoverageArea.contains(42.0, null)).isFalse();
        assertThat(UsCoverageArea.contains(Double.NaN, Double.NaN)).isFalse();
        assertThat(UsCoverageArea.contains(Double.POSITIVE_INFINITY, -71.0)).isFalse();
        assertThat(UsCoverageArea.contains(42.0, Double.NEGATIVE_INFINITY)).isFalse();
        assertThat(UsCoverageArea.contains(95.0, 200.0)).isFalse();
        assertThat(UsCoverageArea.contains(1e300, -71.0)).isFalse();
        assertThat(UsCoverageArea.contains(-91.0, -71.0)).isFalse();
    }

    @Test
    void rejectsPointsFarOutsideTheUs() {
        assertThat(UsCoverageArea.contains(0.0, 0.0)).isFalse();
        assertThat(UsCoverageArea.contains(51.5, -0.12)).isFalse();      // London
        assertThat(UsCoverageArea.contains(19.43, -99.13)).isFalse();    // Mexico City
        assertThat(UsCoverageArea.contains(-33.87, 151.21)).isFalse();   // Sydney
        assertThat(UsCoverageArea.contains(35.68, 139.69)).isFalse();    // Tokyo
    }
}
