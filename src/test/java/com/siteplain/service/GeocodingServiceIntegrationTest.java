package com.siteplain.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.siteplain.domain.model.GeocodedAddress;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@Tag("network")
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "TEST_DATASOURCE_URL", matches = ".+")
class GeocodingServiceIntegrationTest {

    @Autowired
    private GeocodingService geocodingService;

    @Test
    void geocode_census_knownAddress() {
        GeocodedAddress result = geocodingService.geocode("57 Salem Street, Woburn, MA 01801");

        assertThat(result.resolved()).isTrue();
        assertThat(result.latitude()).isBetween(42.4, 42.5);
        assertThat(result.longitude()).isBetween(-71.2, -71.1);
        assertThat(result.geocoderUsed()).isEqualTo("census");
    }

    @Disabled("Hard to force a deterministic Census failure that still yields a valid Nominatim fallback in an automated test.")
    @Test
    void geocode_fallsBackToNominatim_onBadCensusInput() {
    }

    @Test
    void geocode_returnsUnresolved_onGibberish() {
        GeocodedAddress result = geocodingService.geocode("xyzzy 99999 nowhere");

        assertThat(result.resolved()).isFalse();
    }
}
