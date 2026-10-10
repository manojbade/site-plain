package com.siteplain.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.siteplain.config.DataProperties;
import com.siteplain.data.loader.SourceRejectedException;
import com.siteplain.data.repository.NplBoundaryRepository;
import com.siteplain.service.DataBootstrapService;
import com.siteplain.service.SitemapService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/** End-to-end refresh against real Postgres: stage everything, then one swap transaction. */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "TEST_DATASOURCE_URL", matches = ".+")
class DataRefreshIntegrationTest {

    @Autowired
    private DataBootstrapService dataBootstrapService;

    @Autowired
    private DataProperties dataProperties;

    @Autowired
    private SitemapService sitemapService;

    @Autowired
    private NplBoundaryRepository nplBoundaryRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @TempDir
    Path dir;

    private DataProperties original;

    @BeforeEach
    void setUp() {
        original = new DataProperties();
        original.setNplBoundariesUrl(dataProperties.getNplBoundariesUrl());
        original.setHumanExposureUrl(dataProperties.getHumanExposureUrl());
        original.setRefreshOnStartup(dataProperties.isRefreshOnStartup());
        clearAll();
    }

    @AfterEach
    void tearDown() {
        dataProperties.setNplBoundariesUrl(original.getNplBoundariesUrl());
        dataProperties.setHumanExposureUrl(original.getHumanExposureUrl());
        dataProperties.setRefreshOnStartup(original.isRefreshOnStartup());
        clearAll();
        sitemapService.rebuild();
    }

    @Test
    void firstRefresh_loadsEverySourceAndCache_andClearsStaging() throws Exception {
        load("OLD", true);

        assertThat(count("npl_site_boundaries")).isEqualTo(RefreshFixtures.SITES);
        assertThat(count("npl_human_exposure")).isEqualTo(RefreshFixtures.SITES);
        assertThat(count("site_seo_page_cache")).isEqualTo(RefreshFixtures.FINAL_SITES);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT site_count FROM state_page_cache WHERE state_code = 'GA'", Integer.class))
                .isEqualTo(RefreshFixtures.FINAL_SITES);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT exposure_status_label FROM site_seo_page_cache WHERE epa_id = ?", String.class,
                RefreshFixtures.epaId(0))).isEqualTo("Human exposure is NOT currently under control");
        assertThat(count("npl_site_boundaries_staging")).isZero();
        assertThat(count("npl_human_exposure_staging")).isZero();
        assertThat(sitemapService.currentSitemap()).contains("/site/" + RefreshFixtures.epaId(0));
    }

    @Test
    void reload_readersSeeOnlyTheOldOrTheNewDataNeverEmptyOrMixed() throws Exception {
        load("OLD", true);
        List<Map<String, Object>> snapshots = new CopyOnWriteArrayList<>();
        AtomicBoolean running = new AtomicBoolean(true);
        Thread reader = new Thread(() -> {
            while (running.get()) {
                // One statement, so one snapshot of all four tables.
                snapshots.add(jdbcTemplate.queryForMap("""
                        SELECT (SELECT count(*) FROM npl_site_boundaries WHERE site_name LIKE 'OLD %') AS b_old,
                               (SELECT count(*) FROM npl_site_boundaries WHERE site_name LIKE 'NEW %') AS b_new,
                               (SELECT count(*) FROM npl_human_exposure WHERE site_name LIKE 'OLD %') AS e_old,
                               (SELECT count(*) FROM npl_human_exposure WHERE site_name LIKE 'NEW %') AS e_new,
                               (SELECT count(*) FROM site_seo_page_cache WHERE site_name LIKE 'OLD %') AS s_old,
                               (SELECT count(*) FROM site_seo_page_cache WHERE site_name LIKE 'NEW %') AS s_new,
                               (SELECT count(*) FROM state_page_cache) AS states
                        """));
            }
        });
        reader.start();
        try {
            load("NEW", true);
        } finally {
            running.set(false);
            reader.join();
        }

        long sites = RefreshFixtures.SITES;
        long finals = RefreshFixtures.FINAL_SITES;
        Map<String, Object> oldState = Map.of("b_old", sites, "b_new", 0L, "e_old", sites, "e_new", 0L,
                "s_old", finals, "s_new", 0L, "states", 1L);
        Map<String, Object> newState = Map.of("b_old", 0L, "b_new", sites, "e_old", 0L, "e_new", sites,
                "s_old", 0L, "s_new", finals, "states", 1L);
        assertThat(snapshots).hasSizeGreaterThan(5);
        assertThat(snapshots).allSatisfy(snapshot -> assertThat(snapshot).isIn(oldState, newState));
        assertThat(snapshots.getLast()).isEqualTo(newState);
    }

    @Test
    void failureAfterBoundariesAreStaged_leavesLiveDataUntouched() throws Exception {
        load("OLD", true);
        Path boundaries = RefreshFixtures.writeBoundaries(dir.resolve("new.geojson"), "NEW");
        Path badFeed = dir.resolve("bad.json");
        Files.writeString(badFeed, Files.readString(RefreshFixtures.writeExposure(dir.resolve("e.json"), "NEW"))
                .replace("\"nplstatus\":\"F\"", "\"nplstatus\":\"Final\""));
        dataProperties.setNplBoundariesUrl(boundaries.toUri().toString());
        dataProperties.setHumanExposureUrl(badFeed.toUri().toString());
        dataProperties.setRefreshOnStartup(true);

        assertThatThrownBy(dataBootstrapService::refreshIfNeeded).hasMessageContaining("unknown nplstatus");

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM npl_site_boundaries WHERE site_name LIKE 'OLD %'", Integer.class))
                .isEqualTo(RefreshFixtures.SITES);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM npl_human_exposure WHERE site_name LIKE 'OLD %'", Integer.class))
                .isEqualTo(RefreshFixtures.SITES);
        assertThat(count("site_seo_page_cache")).isEqualTo(RefreshFixtures.FINAL_SITES);
        assertThat(count("state_page_cache")).isEqualTo(1);
    }

    @Test
    void rejectedBoundaryFile_saysWhatFailed_andLeavesLiveDataUntouched() throws Exception {
        load("OLD", true);
        Path boundaries = RefreshFixtures.writeBoundaries(dir.resolve("dup.geojson"), "NEW");
        // The second site reuses the first site's EPA_ID.
        Files.writeString(boundaries, Files.readString(boundaries).replace(RefreshFixtures.epaId(1) + "\"", RefreshFixtures.epaId(0) + "\""));
        dataProperties.setNplBoundariesUrl(boundaries.toUri().toString());
        dataProperties.setHumanExposureUrl(RefreshFixtures.writeExposure(dir.resolve("e.json"), "NEW").toUri().toString());
        dataProperties.setRefreshOnStartup(true);

        assertThatThrownBy(dataBootstrapService::refreshIfNeeded)
                .isInstanceOf(SourceRejectedException.class)
                .hasMessage("NPL boundary file rejected: 1 duplicate EPA_ID values (" + RefreshFixtures.epaId(0) + ")");

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM npl_site_boundaries WHERE site_name LIKE 'OLD %'", Integer.class))
                .isEqualTo(RefreshFixtures.SITES);
    }

    @Test
    void polygonFeature_isStoredAsMultiPolygon() {
        nplBoundaryRepository.insertStaging("GAR999999999", "POLYGON SITE", "GA", "F", null,
                "{\"type\":\"Polygon\",\"coordinates\":[[[-84.4,33.77],[-84.39,33.77],[-84.39,33.78],[-84.4,33.77]]]}");

        assertThat(jdbcTemplate.queryForObject(
                "SELECT GeometryType(geom) FROM npl_site_boundaries_staging WHERE epa_id = 'GAR999999999'", String.class))
                .isEqualTo("MULTIPOLYGON");
    }

    private void load(String namePrefix, boolean reloadBoundaries) throws Exception {
        Path boundaries = RefreshFixtures.writeBoundaries(dir.resolve(namePrefix + ".geojson"), namePrefix);
        Path exposure = RefreshFixtures.writeExposure(dir.resolve(namePrefix + ".json"), namePrefix);
        dataProperties.setNplBoundariesUrl(boundaries.toUri().toString());
        dataProperties.setHumanExposureUrl(exposure.toUri().toString());
        dataProperties.setRefreshOnStartup(reloadBoundaries);
        dataBootstrapService.refreshIfNeeded();
    }

    private int count(String table) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    private void clearAll() {
        jdbcTemplate.update("DELETE FROM site_seo_page_cache");
        jdbcTemplate.update("DELETE FROM state_page_cache");
        jdbcTemplate.update("DELETE FROM npl_human_exposure");
        jdbcTemplate.update("DELETE FROM npl_site_boundaries");
        jdbcTemplate.update("DELETE FROM npl_human_exposure_staging");
        jdbcTemplate.update("DELETE FROM npl_site_boundaries_staging");
    }
}
