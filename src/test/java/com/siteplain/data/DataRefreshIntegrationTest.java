package com.siteplain.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.siteplain.config.DataProperties;
import com.siteplain.data.loader.SourceRejectedException;
import com.siteplain.data.repository.HumanExposureRepository;
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
    private HumanExposureRepository humanExposureRepository;

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

        assertThatThrownBy(() -> dataBootstrapService.refresh(true)).hasMessageContaining("unknown nplstatus");

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

        assertThatThrownBy(() -> dataBootstrapService.refresh(true))
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

    @Test
    void sameSources_keepBoundariesAndSkipTheExposureSwap_recordingWhatIsLive() throws Exception {
        load("OLD", true);
        Map<String, Object> exposure = jdbcTemplate.queryForMap(
                "SELECT * FROM data_source_state WHERE dataset = 'human_exposure'");
        assertThat(exposure.get("source_url")).isEqualTo(dataProperties.getHumanExposureUrl());
        assertThat(exposure.get("sha256")).isEqualTo(sha256(Path.of(java.net.URI.create(dataProperties.getHumanExposureUrl()))));
        assertThat(exposure.get("bytes")).isEqualTo(Files.size(Path.of(java.net.URI.create(dataProperties.getHumanExposureUrl()))));
        assertThat(exposure.get("row_count")).isEqualTo(RefreshFixtures.SITES);
        assertThat(exposure.get("final_count")).isEqualTo(RefreshFixtures.FINAL_SITES);
        assertThat(exposure.get("last_status")).isEqualTo("LOADED");
        assertThat(exposure.get("last_modified")).isNull();
        Map<String, Object> boundaries = jdbcTemplate.queryForMap(
                "SELECT * FROM data_source_state WHERE dataset = 'npl_boundaries'");
        assertThat(boundaries.get("source_url")).isEqualTo(dataProperties.getNplBoundariesUrl());
        assertThat(boundaries.get("final_count")).isEqualTo(RefreshFixtures.FINAL_SITES);
        Object exposureLoadedAt = maxLoadedAt("npl_human_exposure");
        Object boundariesLoadedAt = maxLoadedAt("npl_site_boundaries");
        jdbcTemplate.update("DELETE FROM site_seo_page_cache WHERE epa_id = ?", RefreshFixtures.epaId(0));

        dataProperties.setRefreshOnStartup(false);
        dataBootstrapService.refresh(false);

        assertThat(maxLoadedAt("npl_human_exposure")).isEqualTo(exposureLoadedAt);
        assertThat(maxLoadedAt("npl_site_boundaries")).isEqualTo(boundariesLoadedAt);
        // Nothing changed and this is not a startup, so not even the caches were rebuilt.
        assertThat(count("site_seo_page_cache")).isEqualTo(RefreshFixtures.FINAL_SITES - 1);
        Map<String, Object> checked = jdbcTemplate.queryForMap(
                "SELECT * FROM data_source_state WHERE dataset = 'human_exposure'");
        assertThat(checked.get("last_status")).isEqualTo("UNCHANGED");
        assertThat(checked.get("sha256")).isEqualTo(exposure.get("sha256"));
        assertThat((java.sql.Timestamp) checked.get("last_checked_at")).isAfter((java.sql.Timestamp) exposure.get("last_checked_at"));
        assertThat(checked.get("loaded_at")).isEqualTo(exposure.get("loaded_at"));
    }

    @Test
    void startupWithNothingChanged_stillRebuildsTheCaches() throws Exception {
        load("OLD", true);
        Object exposureLoadedAt = maxLoadedAt("npl_human_exposure");
        jdbcTemplate.update("DELETE FROM site_seo_page_cache WHERE epa_id = ?", RefreshFixtures.epaId(0));

        dataProperties.setRefreshOnStartup(false);
        dataBootstrapService.refresh(true);

        assertThat(count("site_seo_page_cache")).isEqualTo(RefreshFixtures.FINAL_SITES);
        assertThat(maxLoadedAt("npl_human_exposure")).isEqualTo(exposureLoadedAt);
    }

    @Test
    void changedBoundaryUrl_reloadsWithoutTheStartupFlag() throws Exception {
        load("OLD", true);
        Path newRelease = RefreshFixtures.writeBoundaries(dir.resolve("release-2.geojson"), "NEW");
        dataProperties.setNplBoundariesUrl(newRelease.toUri().toString());
        dataProperties.setRefreshOnStartup(false);

        dataBootstrapService.refresh(false);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM npl_site_boundaries WHERE site_name LIKE 'NEW %'", Integer.class))
                .isEqualTo(RefreshFixtures.SITES);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT source_url FROM data_source_state WHERE dataset = 'npl_boundaries'", String.class))
                .isEqualTo(newRelease.toUri().toString());
    }

    @Test
    void liveBoundariesWithNoRecord_areAdoptedWithoutDownloading() throws Exception {
        load("OLD", true);
        Object boundariesLoadedAt = maxLoadedAt("npl_site_boundaries");
        jdbcTemplate.update("DELETE FROM data_source_state");
        // Unreadable on purpose: adopting must not download it.
        String configured = "file:///nonexistent/release/npl_site_boundaries.geojson";
        dataProperties.setNplBoundariesUrl(configured);
        dataProperties.setRefreshOnStartup(false);

        dataBootstrapService.refresh(true);

        Map<String, Object> adopted = jdbcTemplate.queryForMap(
                "SELECT * FROM data_source_state WHERE dataset = 'npl_boundaries'");
        assertThat(adopted.get("last_status")).isEqualTo("ADOPTED");
        assertThat(adopted.get("source_url")).isEqualTo(configured);
        assertThat(adopted.get("sha256")).isNull();
        assertThat(adopted.get("row_count")).isEqualTo(RefreshFixtures.SITES);
        assertThat(adopted.get("final_count")).isEqualTo(RefreshFixtures.FINAL_SITES);
        assertThat(adopted.get("loaded_at")).isNotNull();
        assertThat(maxLoadedAt("npl_site_boundaries")).isEqualTo(boundariesLoadedAt);
        // With the same URL configured, the next startup keeps the boundaries too.
        dataBootstrapService.refresh(true);
        assertThat(maxLoadedAt("npl_site_boundaries")).isEqualTo(boundariesLoadedAt);
    }

    @Test
    void statusAsOfDate_prefersTheFeedLastModifiedThenTheLoadDate() throws Exception {
        load("OLD", true);
        // file:// sources have no Last-Modified, so the date is the load date.
        assertThat(humanExposureRepository.statusAsOfDate()).contains(java.time.LocalDate.now());

        jdbcTemplate.update("UPDATE data_source_state SET last_modified = TIMESTAMPTZ '2026-10-09 23:30:00+00' "
                + "WHERE dataset = 'human_exposure'");
        assertThat(humanExposureRepository.statusAsOfDate()).contains(java.time.LocalDate.of(2026, 10, 9));
    }

    private Object maxLoadedAt(String table) {
        return jdbcTemplate.queryForObject("SELECT max(loaded_at) FROM " + table, Object.class);
    }

    private static String sha256(Path file) throws Exception {
        return java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }

    private void load(String namePrefix, boolean reloadBoundaries) throws Exception {
        Path boundaries = RefreshFixtures.writeBoundaries(dir.resolve(namePrefix + ".geojson"), namePrefix);
        Path exposure = RefreshFixtures.writeExposure(dir.resolve(namePrefix + ".json"), namePrefix);
        dataProperties.setNplBoundariesUrl(boundaries.toUri().toString());
        dataProperties.setHumanExposureUrl(exposure.toUri().toString());
        dataProperties.setRefreshOnStartup(reloadBoundaries);
        dataBootstrapService.refresh(true);
    }

    private int count(String table) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    private void clearAll() {
        jdbcTemplate.update("DELETE FROM data_source_state");
        jdbcTemplate.update("DELETE FROM site_seo_page_cache");
        jdbcTemplate.update("DELETE FROM state_page_cache");
        jdbcTemplate.update("DELETE FROM npl_human_exposure");
        jdbcTemplate.update("DELETE FROM npl_site_boundaries");
        jdbcTemplate.update("DELETE FROM npl_human_exposure_staging");
        jdbcTemplate.update("DELETE FROM npl_site_boundaries_staging");
    }
}
