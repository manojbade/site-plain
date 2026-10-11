package com.siteplain.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class GeocoderPropertiesTest {

    @Test
    void defaults_identifyTheAppWithAContactAndKeepNominatimShort() {
        GeocoderProperties properties = new GeocoderProperties();
        assertThat(properties.getUserAgent()).isEqualTo("site-plain/0.0.1 (+https://site-plain.com/feedback)");
        assertThat(properties.getNominatimTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(properties.getCensusTimeout()).isEqualTo(Duration.ofSeconds(3));
    }
}
