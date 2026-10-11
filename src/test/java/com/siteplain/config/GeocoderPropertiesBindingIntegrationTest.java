package com.siteplain.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/** application.properties' own default, used when SITEPLAIN_GEOCODER_USER_AGENT is unset, names the contact too. */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "TEST_DATASOURCE_URL", matches = ".+")
class GeocoderPropertiesBindingIntegrationTest {

    @Autowired
    private GeocoderProperties geocoderProperties;

    @Test
    void boundDefaults_matchTheNominatimPolicySettings() {
        assertThat(System.getenv("SITEPLAIN_GEOCODER_USER_AGENT")).isNull();
        assertThat(geocoderProperties.getUserAgent()).isEqualTo(GeocoderProperties.DEFAULT_USER_AGENT);
        assertThat(geocoderProperties.getNominatimTimeout()).isEqualTo(Duration.ofSeconds(5));
    }
}
