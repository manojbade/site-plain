package com.siteplain.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.siteplain.domain.model.GeocodedAddress;
import com.siteplain.domain.model.NplLookupResult;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.ConnectionCallback;
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

    @Test
    void lookupQuery_canUseTheGeographyIndexThroughTheActiveSiteView() {
        // With a few hundred boundaries the planner must pick idx_npl_boundaries_geog for the view's
        // ST_DWithin on geom::geography. Without the V6 index it seq scans every boundary row.
        // Everything runs in one transaction that is rolled back, including the ANALYZE.
        List<String> plan = jdbcTemplate.execute((ConnectionCallback<List<String>>) connection -> {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute("""
                        INSERT INTO npl_site_boundaries (epa_id, site_name, state_code, npl_status_code, geom, loaded_at)
                        SELECT 'IDX' || g, 'Index Test ' || g, 'GA', 'F',
                               ST_Multi(ST_MakeEnvelope(-120 + g * 0.1, 30, -119.99 + g * 0.1, 30.01, 4326)), now()
                        FROM generate_series(1, 400) g
                        """);
                statement.execute("ANALYZE npl_site_boundaries");
                List<String> lines = new ArrayList<>();
                try (ResultSet rs = statement.executeQuery("""
                        EXPLAIN SELECT a.epa_id FROM active_npl_sites a
                        WHERE a.geom IS NOT NULL
                          AND ST_DWithin(a.geom::geography,
                                         ST_SetSRID(ST_MakePoint(-84.395, 33.775), 4326)::geography, 4828.032)
                        """)) {
                    while (rs.next()) {
                        lines.add(rs.getString(1));
                    }
                }
                return lines;
            } finally {
                connection.rollback();
                connection.setAutoCommit(autoCommit);
            }
        });

        assertThat(String.join("\n", plan)).contains("idx_npl_boundaries_geog");
    }

    @Test
    void findSitesNear_findsSitesNearTheEdgeOfTheRadiusAtHighLatitudeAndAcrossThe180thMeridian() {
        // 4.80 km east of a Fairbanks-area point (inside 3 miles), and 4.86 km east (outside). A fixed
        // 0.05 degree box is only about 2.4 km wide there.
        insertBoundaryAt("TEST001", "F", jdbcTemplate.queryForObject(
                "SELECT ST_AsText(ST_Project(ST_SetSRID(ST_MakePoint(-147.10, 64.70), 4326)::geography, 4800, radians(90))::geometry)",
                String.class));
        insertBoundaryAt("TEST002", "F", jdbcTemplate.queryForObject(
                "SELECT ST_AsText(ST_Project(ST_SetSRID(ST_MakePoint(-147.10, 64.70), 4326)::geography, 4860, radians(90))::geometry)",
                String.class));
        assertThat(nplLookupService.findSitesNear(resolvedAddress(64.70, -147.10)).sites())
                .extracting(site -> site.getEpaId()).containsExactly("TEST001");

        cleanup();
        // A site just east of the 180th meridian, seen from just west of it (about 1.4 km apart).
        insertBoundaryAt("TEST001", "F", "POINT(-179.995 51.80)");
        assertThat(nplLookupService.findSitesNear(resolvedAddress(51.80, 179.985)).sites())
                .extracting(site -> site.getEpaId()).containsExactly("TEST001");
    }

    @Test
    void lookupQuery_withThePrefilterStaysIndexBacked() {
        List<String> plan = jdbcTemplate.execute((ConnectionCallback<List<String>>) connection -> {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute("""
                        INSERT INTO npl_site_boundaries (epa_id, site_name, state_code, npl_status_code, geom, loaded_at)
                        SELECT 'IDX' || g, 'Index Test ' || g, 'GA', 'F',
                               ST_Multi(ST_MakeEnvelope(-120 + g * 0.1, 30, -119.99 + g * 0.1, 30.01, 4326)), now()
                        FROM generate_series(1, 400) g
                        """);
                statement.execute("ANALYZE npl_site_boundaries");
                double[] box = NplLookupService.searchBoxDegrees(33.775, -84.395);
                List<String> lines = new ArrayList<>();
                try (ResultSet rs = statement.executeQuery("""
                        EXPLAIN SELECT a.epa_id FROM active_npl_sites a
                        WHERE a.geom IS NOT NULL
                          AND a.geom && ST_Expand(ST_SetSRID(ST_MakePoint(-84.395, 33.775), 4326), %s, %s)
                          AND ST_DWithin(a.geom::geography,
                                         ST_SetSRID(ST_MakePoint(-84.395, 33.775), 4326)::geography, 4828.032)
                        """.formatted(box[1], box[0]))) {
                    while (rs.next()) {
                        lines.add(rs.getString(1));
                    }
                }
                return lines;
            } finally {
                connection.rollback();
                connection.setAutoCommit(autoCommit);
            }
        });

        assertThat(String.join("\n", plan)).containsPattern("Index Scan (using|on) idx_npl_boundaries_(geom|geog)")
                .doesNotContain("Seq Scan on npl_site_boundaries");
    }

    private void insertBoundaryAt(String epaId, String statusCode, String pointWkt) {
        jdbcTemplate.update("""
                INSERT INTO npl_site_boundaries
                  (epa_id, site_name, state_code, npl_status_code, epa_url, geom)
                VALUES
                  (?, ?, 'AK', ?, ?, ST_Multi(ST_Buffer(ST_GeomFromText(?, 4326), 0.0001)))
                """,
                epaId, "Test Site " + epaId, statusCode, "https://example.com/" + epaId, pointWkt);
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
                  (?, ?, 'Test pathway description', 'F', ?)
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
