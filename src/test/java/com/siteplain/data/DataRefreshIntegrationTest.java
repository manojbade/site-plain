package com.siteplain.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.siteplain.config.DataProperties;
import com.siteplain.data.repository.HumanExposureRepository;
import com.siteplain.data.repository.NplBoundaryRepository;
import com.siteplain.service.DataBootstrapService;
import com.siteplain.service.DataRefreshLockAccess;
import com.siteplain.service.SitemapService;
import java.nio.file.Files;
import java.sql.Connection;
import javax.sql.DataSource;
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

    @Autowired
    private DataSource dataSource;

    @TempDir
    Path dir;

    private DataProperties original;

    @BeforeEach
    void setUp() {
        original = new DataProperties();
        original.setNplBoundariesUrl(dataProperties.getNplBoundariesUrl());
        original.setHumanExposureUrl(dataProperties.getHumanExposureUrl());
        original.setRefreshOnStartup(dataProperties.isRefreshOnStartup());
        original.setAcceptActiveCount(dataProperties.getAcceptActiveCount());
        clearAll();
    }

    @AfterEach
    void tearDown() {
        dataProperties.setNplBoundariesUrl(original.getNplBoundariesUrl());
        dataProperties.setHumanExposureUrl(original.getHumanExposureUrl());
        dataProperties.setRefreshOnStartup(original.isRefreshOnStartup());
        dataProperties.setAcceptActiveCount(original.getAcceptActiveCount());
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
    void rejectedFeed_withLiveData_isLoggedAndRecorded_whileTheValidBoundaryReloadGoesAhead() throws Exception {
        load("OLD", true);
        Path boundaries = RefreshFixtures.writeBoundaries(dir.resolve("new.geojson"), "NEW");
        Path badFeed = dir.resolve("bad.json");
        Files.writeString(badFeed, Files.readString(RefreshFixtures.writeExposure(dir.resolve("e.json"), "NEW"))
                .replace("\"nplstatus\":\"F\"", "\"nplstatus\":\"Final\""));
        dataProperties.setNplBoundariesUrl(boundaries.toUri().toString());
        dataProperties.setHumanExposureUrl(badFeed.toUri().toString());
        dataProperties.setRefreshOnStartup(true);

        dataBootstrapService.refresh(true);

        assertThat(namesLike("npl_site_boundaries", "NEW")).isEqualTo(RefreshFixtures.SITES);
        assertThat(namesLike("npl_human_exposure", "OLD")).isEqualTo(RefreshFixtures.SITES);
        assertThat(count("site_seo_page_cache")).isEqualTo(RefreshFixtures.FINAL_SITES);
        assertThat(status("npl_boundaries")).containsEntry("last_status", "LOADED");
        Map<String, Object> exposure = status("human_exposure");
        assertThat(exposure.get("last_status")).isEqualTo("FAILED");
        assertThat((String) exposure.get("last_error")).isEqualTo("Human exposure feed has unknown nplstatus values: [FINAL]");
        // The identity still describes the live (OLD) feed.
        assertThat(exposure.get("source_url")).isNotEqualTo(badFeed.toUri().toString());
    }

    @Test
    void rejectedBoundaryFile_withLiveData_saysWhatFailed_keepsLiveBoundaries_andStillUpdatesTheFeed() throws Exception {
        load("OLD", true);
        String liveUrl = dataProperties.getNplBoundariesUrl();
        Path boundaries = RefreshFixtures.writeBoundaries(dir.resolve("dup.geojson"), "NEW");
        // The second site reuses the first site's EPA_ID.
        Files.writeString(boundaries, Files.readString(boundaries).replace(RefreshFixtures.epaId(1) + "\"", RefreshFixtures.epaId(0) + "\""));
        dataProperties.setNplBoundariesUrl(boundaries.toUri().toString());
        dataProperties.setHumanExposureUrl(RefreshFixtures.writeExposure(dir.resolve("e.json"), "NEW").toUri().toString());
        dataProperties.setRefreshOnStartup(false);

        dataBootstrapService.refresh(true);

        assertThat(namesLike("npl_site_boundaries", "OLD")).isEqualTo(RefreshFixtures.SITES);
        assertThat(namesLike("npl_human_exposure", "NEW")).isEqualTo(RefreshFixtures.SITES);
        Map<String, Object> record = status("npl_boundaries");
        assertThat(record.get("last_status")).isEqualTo("FAILED");
        assertThat(record.get("last_error"))
                .isEqualTo("NPL boundary file rejected: 1 duplicate EPA_ID values (" + RefreshFixtures.epaId(0) + ")");
        // Still the old URL, so the changed URL is retried on the next refresh.
        assertThat(record.get("source_url")).isEqualTo(liveUrl);
        assertThat(count("npl_site_boundaries_staging")).isZero();
    }

    @Test
    void unreachableSources_withLiveData_doNotStopTheRefresh() throws Exception {
        load("OLD", true);
        dataProperties.setNplBoundariesUrl("file:///nonexistent/release/npl_site_boundaries.geojson");
        dataProperties.setHumanExposureUrl("file:///nonexistent/feed.json");
        jdbcTemplate.update("DELETE FROM site_seo_page_cache WHERE epa_id = ?", RefreshFixtures.epaId(0));

        dataBootstrapService.refresh(true);

        assertThat(namesLike("npl_site_boundaries", "OLD")).isEqualTo(RefreshFixtures.SITES);
        assertThat(namesLike("npl_human_exposure", "OLD")).isEqualTo(RefreshFixtures.SITES);
        // Startup still rebuilds the caches from the live data.
        assertThat(count("site_seo_page_cache")).isEqualTo(RefreshFixtures.FINAL_SITES);
        assertThat(status("npl_boundaries").get("last_error"))
                .isEqualTo("NPL boundary file: file not found: /nonexistent/release/npl_site_boundaries.geojson");
        assertThat(status("human_exposure").get("last_error")).isEqualTo("Human exposure feed: file not found: /nonexistent/feed.json");
        assertThat(sitemapService.currentSitemap()).contains("/site/" + RefreshFixtures.epaId(0));
    }

    @Test
    void unreachableHttpSource_recordsWhereAndWhy() throws Exception {
        load("OLD", true);
        int port;
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            port = socket.getLocalPort();
        }
        String closed = "http://127.0.0.1:" + port + "/e.json";
        dataProperties.setHumanExposureUrl(closed);

        dataBootstrapService.refresh(false);

        assertThat(namesLike("npl_human_exposure", "OLD")).isEqualTo(RefreshFixtures.SITES);
        assertThat(status("human_exposure")).containsEntry("last_status", "FAILED")
                .containsEntry("last_error", "Human exposure feed: could not connect to " + closed
                        + ": ConnectException, caused by ClosedChannelException");
    }

    @Test
    void feedWhoseIdsDoNotMatchTheBoundaries_isRejected_andTheActiveSetStays() throws Exception {
        load("OLD", true);
        Path feed = dir.resolve("other-ids.json");
        // What a switch to another ID column looks like: the same feed, no epaid matching a boundary.
        Files.writeString(feed, Files.readString(RefreshFixtures.writeExposure(dir.resolve("e.json"), "NEW"))
                .replace("\"epaid\":\"GAR", "\"epaid\":\"GAX"));
        dataProperties.setHumanExposureUrl(feed.toUri().toString());

        dataBootstrapService.refresh(false);

        assertThat(namesLike("npl_human_exposure", "OLD")).isEqualTo(RefreshFixtures.SITES);
        assertThat(nplBoundaryRepository.countActiveSites()).isEqualTo(RefreshFixtures.FINAL_SITES);
        assertThat(status("human_exposure")).containsEntry("last_status", "FAILED")
                .containsEntry("last_error", "Only 0 of " + 2 * RefreshFixtures.FINAL_SITES + " projected active sites "
                        + "have both a boundary and an exposure feed row, under 90%: the EPA IDs in the boundary file "
                        + "and the feed do not match");
        assertThat(count("npl_human_exposure_staging")).isZero();
    }

    @Test
    void boundaryFileWhoseIdsDoNotMatchTheFeed_isRejected_andLiveBoundariesStay() throws Exception {
        load("OLD", true);
        Path boundaries = dir.resolve("other-ids.geojson");
        Files.writeString(boundaries, Files.readString(RefreshFixtures.writeBoundaries(dir.resolve("b.geojson"), "NEW"))
                .replace("\"EPA_ID\":\"GAR", "\"EPA_ID\":\"GAX"));
        dataProperties.setNplBoundariesUrl(boundaries.toUri().toString());

        dataBootstrapService.refresh(false);

        assertThat(namesLike("npl_site_boundaries", "OLD")).isEqualTo(RefreshFixtures.SITES);
        assertThat(nplBoundaryRepository.countActiveSites()).isEqualTo(RefreshFixtures.FINAL_SITES);
        assertThat(status("npl_boundaries")).containsEntry("last_status", "FAILED");
        assertThat((String) status("npl_boundaries").get("last_error")).startsWith("Only 0 of ");
    }

    @Test
    void inflatedLiveActiveSet_isRepairedOnlyWithTheExactCountOverride() throws Exception {
        load("OLD", true);
        // Live data from before the join gate: feed IDs that match no boundary double the active set.
        jdbcTemplate.update("UPDATE npl_human_exposure SET epa_id = replace(epa_id, 'GAR', 'GAX')");
        assertThat(nplBoundaryRepository.countActiveSites()).isEqualTo(2 * RefreshFixtures.FINAL_SITES);
        dataProperties.setHumanExposureUrl(RefreshFixtures.writeExposure(dir.resolve("fixed.json"), "NEW").toUri().toString());

        dataBootstrapService.refresh(true);

        assertThat(namesLike("npl_human_exposure", "OLD")).isEqualTo(RefreshFixtures.SITES);
        assertThat(status("human_exposure").get("last_error")).isEqualTo("Active site count would drop from "
                + 2 * RefreshFixtures.FINAL_SITES + " to " + RefreshFixtures.FINAL_SITES + ", more than 5%. If this change "
                + "is expected, set SITEPLAIN_DATA_ACCEPT_ACTIVE_COUNT=" + RefreshFixtures.FINAL_SITES + " for the next boot");

        dataProperties.setAcceptActiveCount(RefreshFixtures.FINAL_SITES);
        dataBootstrapService.refresh(true);

        assertThat(namesLike("npl_human_exposure", "NEW")).isEqualTo(RefreshFixtures.SITES);
        assertThat(nplBoundaryRepository.countActiveSites()).isEqualTo(RefreshFixtures.FINAL_SITES);
        assertThat(count("site_seo_page_cache")).isEqualTo(RefreshFixtures.FINAL_SITES);
        assertThat(status("human_exposure")).containsEntry("last_status", "LOADED");
    }

    @Test
    void unchangedFeed_isStagedAgain_whenSomethingElseRewroteTheLiveRows() throws Exception {
        load("OLD", true);
        Object recordedLoadedAt = status("human_exposure").get("loaded_at");
        // An older app version reloads the feed on every boot and keeps only rows with a boundary.
        jdbcTemplate.update("DELETE FROM npl_human_exposure WHERE epa_id IN (?, ?)",
                RefreshFixtures.epaId(0), RefreshFixtures.epaId(1));

        dataBootstrapService.refresh(false);

        assertThat(count("npl_human_exposure")).isEqualTo(RefreshFixtures.SITES);
        Map<String, Object> restaged = status("human_exposure");
        assertThat(restaged).containsEntry("last_status", "LOADED").containsEntry("row_count", RefreshFixtures.SITES);
        assertThat((java.sql.Timestamp) restaged.get("loaded_at")).isAfter((java.sql.Timestamp) recordedLoadedAt);

        // Same row count, but written after the recorded load.
        jdbcTemplate.update("UPDATE npl_human_exposure SET state_code = NULL, loaded_at = now() + interval '1 minute'");

        dataBootstrapService.refresh(false);

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM npl_human_exposure WHERE state_code = 'GA'",
                Integer.class)).isEqualTo(RefreshFixtures.SITES);
        assertThat(status("human_exposure")).containsEntry("last_status", "LOADED");

        // Untouched since: unchanged again.
        dataBootstrapService.refresh(false);
        assertThat(status("human_exposure")).containsEntry("last_status", "UNCHANGED");
    }

    @Test
    void firstBoot_withABadSource_abortsAndSwapsNothing() throws Exception {
        Path boundaries = RefreshFixtures.writeBoundaries(dir.resolve("first.geojson"), "NEW");
        dataProperties.setNplBoundariesUrl(boundaries.toUri().toString());
        dataProperties.setHumanExposureUrl("file:///nonexistent/feed.json");

        assertThatThrownBy(() -> dataBootstrapService.refresh(true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Human exposure feed failed and no data is live yet: Human exposure feed: file not found: "
                        + "/nonexistent/feed.json");

        assertThat(count("npl_site_boundaries")).isZero();
        assertThat(status("human_exposure").get("last_status")).isEqualTo("FAILED");
    }

    @Test
    void failureInsideTheSwap_rollsEverythingBack() throws Exception {
        load("OLD", true);
        jdbcTemplate.execute("""
                CREATE FUNCTION test_fail_state_insert() RETURNS trigger LANGUAGE plpgsql AS
                $$ BEGIN RAISE EXCEPTION 'injected failure'; END $$
                """);
        jdbcTemplate.execute("CREATE TRIGGER test_fail_state_insert BEFORE INSERT ON state_page_cache "
                + "FOR EACH ROW EXECUTE FUNCTION test_fail_state_insert()");
        try {
            dataProperties.setNplBoundariesUrl(
                    RefreshFixtures.writeBoundaries(dir.resolve("new.geojson"), "NEW").toUri().toString());
            dataProperties.setHumanExposureUrl(
                    RefreshFixtures.writeExposure(dir.resolve("new.json"), "NEW").toUri().toString());

            dataBootstrapService.refresh(true);
        } finally {
            jdbcTemplate.execute("DROP TRIGGER test_fail_state_insert ON state_page_cache");
            jdbcTemplate.execute("DROP FUNCTION test_fail_state_insert()");
        }

        assertThat(namesLike("npl_site_boundaries", "OLD")).isEqualTo(RefreshFixtures.SITES);
        assertThat(namesLike("npl_human_exposure", "OLD")).isEqualTo(RefreshFixtures.SITES);
        assertThat(namesLike("site_seo_page_cache", "OLD")).isEqualTo(RefreshFixtures.FINAL_SITES);
        assertThat(count("state_page_cache")).isEqualTo(1);
        assertThat(status("npl_boundaries").get("last_status")).isEqualTo("FAILED");
        assertThat((String) status("human_exposure").get("last_error")).startsWith("swap rolled back: ")
                .contains("injected failure");
        assertThat(status("human_exposure").get("source_url")).isNotEqualTo(dataProperties.getHumanExposureUrl());
    }

    @Test
    void refresh_skipsWhileAnotherSessionHoldsTheRefreshLock() throws Exception {
        load("OLD", true);
        dataProperties.setNplBoundariesUrl(
                RefreshFixtures.writeBoundaries(dir.resolve("new.geojson"), "NEW").toUri().toString());
        dataProperties.setHumanExposureUrl(RefreshFixtures.writeExposure(dir.resolve("new.json"), "NEW").toUri().toString());
        try (Connection otherInstance = dataSource.getConnection()) {
            otherInstance.createStatement().execute("SELECT pg_advisory_lock(" + DataRefreshLockAccess.key() + ")");

            dataBootstrapService.refresh(true);

            assertThat(namesLike("npl_site_boundaries", "OLD")).isEqualTo(RefreshFixtures.SITES);
            assertThat(namesLike("npl_human_exposure", "OLD")).isEqualTo(RefreshFixtures.SITES);
            otherInstance.createStatement().execute("SELECT pg_advisory_unlock(" + DataRefreshLockAccess.key() + ")");
        }

        dataBootstrapService.refresh(true);

        assertThat(namesLike("npl_site_boundaries", "NEW")).isEqualTo(RefreshFixtures.SITES);
        assertThat(namesLike("npl_human_exposure", "NEW")).isEqualTo(RefreshFixtures.SITES);
    }

    @Test
    void concurrentRefreshes_runOneAtATime() throws Exception {
        load("OLD", true);
        dataProperties.setNplBoundariesUrl(
                RefreshFixtures.writeBoundaries(dir.resolve("new.geojson"), "NEW").toUri().toString());
        dataProperties.setHumanExposureUrl(RefreshFixtures.writeExposure(dir.resolve("new.json"), "NEW").toUri().toString());
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
            List<java.util.concurrent.Future<?>> runs = new java.util.ArrayList<>();
            for (int i = 0; i < 2; i++) {
                runs.add(pool.submit(() -> {
                    start.await();
                    dataBootstrapService.refresh(false);
                    return null;
                }));
            }
            start.countDown();
            for (java.util.concurrent.Future<?> run : runs) {
                run.get();
            }
        } finally {
            pool.shutdown();
        }

        // Whichever ran second either skipped on the lock or found nothing changed; never a mix.
        assertThat(namesLike("npl_site_boundaries", "NEW")).isEqualTo(RefreshFixtures.SITES);
        assertThat(namesLike("npl_human_exposure", "NEW")).isEqualTo(RefreshFixtures.SITES);
        assertThat(count("site_seo_page_cache")).isEqualTo(RefreshFixtures.FINAL_SITES);
        // Only this database: other apps on the same Postgres server may hold their own advisory locks.
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM pg_locks WHERE locktype = 'advisory' "
                + "AND database = (SELECT oid FROM pg_database WHERE datname = current_database())", Integer.class))
                .isZero();
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

    private int namesLike(String table, String prefix) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE site_name LIKE ?", Integer.class, prefix + " %");
    }

    private Map<String, Object> status(String dataset) {
        return jdbcTemplate.queryForMap("SELECT * FROM data_source_state WHERE dataset = ?", dataset);
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
