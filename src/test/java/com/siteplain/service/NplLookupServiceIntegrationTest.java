package com.siteplain.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.siteplain.domain.model.GeocodedAddress;
import com.siteplain.domain.model.NplLookupResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "TEST_DATASOURCE_URL", matches = ".+")
class NplLookupServiceIntegrationTest {

    @Autowired
    private NplLookupService nplLookupService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM npl_human_exposure WHERE epa_id IN ('TEST001', 'TEST002')");
        jdbcTemplate.update("DELETE FROM npl_site_boundaries WHERE epa_id IN ('TEST001', 'TEST002')");
    }

    @Test
    void findSitesNear_returnsResult_whenAddressIsInsideRadius() {
        insertBoundary("TEST001", "F");
        insertExposure("TEST001", "HENC");

        NplLookupResult result = nplLookupService.findSitesNear(resolvedAddress(33.775, -84.395));

        assertThat(result.unresolved()).isFalse();
        assertThat(result.sites()).hasSize(1);
        assertThat(result.sites().getFirst().getEpaId()).isEqualTo("TEST001");
        assertThat(result.sites().getFirst().getExposureStatusLabel())
                .isEqualTo("Human exposure is NOT currently under control");
    }

    @Test
    void findSitesNear_returnsEmpty_whenAddressIsOutsideRadius() {
        insertBoundary("TEST001", "F");
        insertExposure("TEST001", "HENC");

        NplLookupResult result = nplLookupService.findSitesNear(resolvedAddress(34.5, -85.5));

        assertThat(result.unresolved()).isFalse();
        assertThat(result.sites()).isEmpty();
    }

    @Test
    void findSitesNear_excludesNonFinalSites() {
        insertBoundary("TEST001", "F");
        insertExposure("TEST001", "HENC");
        insertBoundary("TEST002", "P");

        NplLookupResult result = nplLookupService.findSitesNear(resolvedAddress(33.775, -84.395));

        assertThat(result.sites()).extracting(site -> site.getEpaId()).containsExactly("TEST001");
    }

    private void insertBoundary(String epaId, String statusCode) {
        jdbcTemplate.update("""
                INSERT INTO npl_site_boundaries
                  (epa_id, site_name, state_code, npl_status_code, epa_url, geom)
                VALUES
                  (?, ?, 'GA', ?, ?, ST_Multi(ST_GeomFromText(
                    'POLYGON((-84.40 33.77, -84.39 33.77, -84.39 33.78, -84.40 33.78, -84.40 33.77))',
                    4326
                  )))
                """,
                epaId,
                "Test Site " + epaId,
                statusCode,
                "https://example.com/" + epaId);
    }

    private void insertExposure(String epaId, String statusCode) {
        jdbcTemplate.update("""
                INSERT INTO npl_human_exposure
                  (epa_id, humexposurestscode, humanexposurepathdesc, npl_status, site_name)
                VALUES
                  (?, ?, 'Test pathway description', 'Final', ?)
                """,
                epaId, statusCode, "Test Site " + epaId);
    }

    private GeocodedAddress resolvedAddress(double latitude, double longitude) {
        return new GeocodedAddress(
                "test address",
                "test address",
                "Atlanta",
                "GA",
                "30303",
                "test",
                latitude,
                longitude,
                true
        );
    }
}
