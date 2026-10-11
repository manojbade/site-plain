package com.siteplain.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class NplLookupServiceTest {

    private static final double METERS_PER_DEGREE_LAT_MAX = 111_694.0; // at the poles, the longest degree

    @Test
    void searchBox_holdsTheWholeRadiusAtEveryCoveredLatitude() {
        for (double lat : new double[] {-14.3, 0.0, 13.4, 18.4, 33.8, 40.7, 64.7, 71.3}) {
            double[] box = NplLookupService.searchBoxDegrees(lat, -100.0);
            assertThat(box).as("box at %s", lat).isNotNull();
            // A naive fixed 0.05 degree box missed sites 2.6 to 3 miles east or west of an address at 64.7N.
            double poleward = Math.toRadians(Math.abs(lat) + box[0]);
            double eastWestMeters = box[1] * 111_319.0 * Math.cos(poleward);
            assertThat(eastWestMeters).as("east-west reach at %s", lat).isGreaterThan(NplLookupService.RADIUS_METERS * 1.05);
            assertThat(box[0] * 110_574.0).isGreaterThan(NplLookupService.RADIUS_METERS * 1.05);
            // Still a small box: well under a tenth of a degree north-south.
            assertThat(box[0] * METERS_PER_DEGREE_LAT_MAX).isLessThan(NplLookupService.RADIUS_METERS * 1.2);
        }
    }

    @Test
    void searchBox_isSkippedAcrossThe180thMeridianAndNearThePoles() {
        assertThat(NplLookupService.searchBoxDegrees(51.8, 179.95)).isNull();
        assertThat(NplLookupService.searchBoxDegrees(51.8, -179.97)).isNull();
        assertThat(NplLookupService.searchBoxDegrees(89.5, 0.0)).isNull();
        assertThat(NplLookupService.searchBoxDegrees(51.8, 178.0)).isNotNull();
    }
}
