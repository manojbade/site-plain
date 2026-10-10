package com.siteplain.service;

import com.siteplain.config.DataProperties;
import com.siteplain.data.loader.BoundaryFileStats;
import com.siteplain.data.loader.DownloadedFile;
import com.siteplain.data.loader.ExposureFeedStats;
import com.siteplain.data.loader.HumanExposureLoader;
import com.siteplain.data.loader.NplBoundaryLoader;
import com.siteplain.data.loader.SeoPageCacheBuilder;
import com.siteplain.data.loader.SourceDownloader;
import com.siteplain.data.loader.SourceRejectedException;
import com.siteplain.data.loader.StatePageCacheBuilder;
import com.siteplain.data.repository.DataSourceRepository;
import com.siteplain.data.repository.DataSourceRepository.DataSourceRecord;
import com.siteplain.data.repository.HumanExposureRepository;
import com.siteplain.data.repository.HumanExposureRepository.ProjectedActiveSites;
import com.siteplain.data.repository.NplBoundaryRepository;
import com.siteplain.data.repository.SeoPageRepository;
import com.siteplain.data.repository.StatePageRepository;
import com.siteplain.service.DataRefreshPolicyService.Action;
import com.siteplain.service.DataRefreshPolicyService.BoundaryDecision;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

@Service
public class DataBootstrapService implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DataBootstrapService.class);

    // The feed's nplstatus decides which sites are active, so a changed vocabulary ('Final' for 'F')
    // or a partial feed would empty the site even when the row count looks fine. The Final band
    // matches the converter's --min-final/--max-final (1,337 Final rows in the 2026-10-10 feed).
    static final Set<String> KNOWN_NPL_STATUSES = Set.of("F", "D", "P", "N", "R", "S");
    static final int MIN_FEED_FINAL = 1200;
    static final int MAX_FEED_FINAL = 1450;
    static final double MIN_ACTIVE_RATIO = 0.95;
    static final double MAX_ACTIVE_RATIO = 1.05;
    // Share of projected active sites that must have both a boundary and a feed row. On real data
    // 1,322 of 1,337 do (98.9%); a source whose EPA IDs stop matching takes it toward 0%.
    static final double MIN_JOINED_RATIO = 0.90;
    static final String NPL_BOUNDARY_FILE = "NPL boundary file";
    static final String HUMAN_EXPOSURE_FEED = "Human exposure feed";

    private final DataProperties dataProperties;
    private final DataRefreshPolicyService dataRefreshPolicyService;
    private final DataSourceRepository dataSourceRepository;
    private final DataRefreshLock dataRefreshLock;
    private final SourceDownloader sourceDownloader;
    private final NplBoundaryLoader nplBoundaryLoader;
    private final HumanExposureLoader humanExposureLoader;
    private final NplBoundaryRepository nplBoundaryRepository;
    private final HumanExposureRepository humanExposureRepository;
    private final SeoPageCacheBuilder seoPageCacheBuilder;
    private final StatePageCacheBuilder statePageCacheBuilder;
    private final SeoPageRepository seoPageRepository;
    private final StatePageRepository statePageRepository;
    private final SitemapService sitemapService;
    private final TransactionTemplate transactionTemplate;

    public DataBootstrapService(DataProperties dataProperties,
                                DataRefreshPolicyService dataRefreshPolicyService,
                                DataSourceRepository dataSourceRepository,
                                DataRefreshLock dataRefreshLock,
                                SourceDownloader sourceDownloader,
                                NplBoundaryLoader nplBoundaryLoader,
                                HumanExposureLoader humanExposureLoader,
                                NplBoundaryRepository nplBoundaryRepository,
                                HumanExposureRepository humanExposureRepository,
                                SeoPageCacheBuilder seoPageCacheBuilder,
                                StatePageCacheBuilder statePageCacheBuilder,
                                SeoPageRepository seoPageRepository,
                                StatePageRepository statePageRepository,
                                SitemapService sitemapService,
                                PlatformTransactionManager transactionManager) {
        this.dataProperties = dataProperties;
        this.dataRefreshPolicyService = dataRefreshPolicyService;
        this.dataSourceRepository = dataSourceRepository;
        this.dataRefreshLock = dataRefreshLock;
        this.sourceDownloader = sourceDownloader;
        this.nplBoundaryLoader = nplBoundaryLoader;
        this.humanExposureLoader = humanExposureLoader;
        this.nplBoundaryRepository = nplBoundaryRepository;
        this.humanExposureRepository = humanExposureRepository;
        this.seoPageCacheBuilder = seoPageCacheBuilder;
        this.statePageCacheBuilder = statePageCacheBuilder;
        this.seoPageRepository = seoPageRepository;
        this.statePageRepository = statePageRepository;
        this.sitemapService = sitemapService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (dataProperties.getMaxAgeDays() != null) {
            log.warn("SITEPLAIN_DATA_MAX_AGE_DAYS={} is ignored: boundaries reload only on first boot, with "
                    + "SITEPLAIN_DATA_REFRESH_ON_STARTUP=true, or when SITEPLAIN_DATA_NPL_BOUNDARIES_URL changes",
                    dataProperties.getMaxAgeDays());
        }
        if (!dataProperties.isBootstrapEnabled()) {
            log.info("Site Plain bootstrap is disabled; serving the data already in the database");
            sitemapService.rebuild();
            return;
        }
        refresh(true);
    }

    /**
     * Downloads, stages and validates every changed source first, then replaces boundaries (when
     * reloading), exposure (when the feed changed) and both page caches in one transaction. A failure
     * before commit leaves live data as it was. At startup the caches are rebuilt even when no source
     * changed, because a deploy can change how they are derived (labels, the active-site view).
     *
     * Failure policy: when boundaries are already live, a failed download, a rejected file or a failed
     * swap is logged at ERROR, recorded in data_source_state, and the app keeps serving the live data;
     * the other source still goes ahead. Only an empty database (first boot) makes this throw, which
     * aborts startup, since the app has nothing to serve.
     *
     * The whole refresh runs under DataRefreshLock; when another instance holds it this one skips.
     */
    public void refresh(boolean startup) throws Exception {
        boolean ran = dataRefreshLock.runExclusively(() -> refreshLocked(startup));
        if (!ran) {
            log.warn("Data refresh skipped: another instance holds the refresh lock (an overlapping deploy or a "
                    + "scheduled refresh); serving the data already live");
            if (startup) {
                sitemapService.rebuild();
            }
        }
    }

    private void refreshLocked(boolean startup) throws Exception {
        boolean firstBoot = !nplBoundaryRepository.liveDataPresent();
        BoundaryDecision decision = dataRefreshPolicyService.decideBoundaries(startup);
        log.info("NPL boundaries: {} ({})", decision.action(), decision.reason());
        if (decision.action() == Action.ADOPT) {
            adoptLiveBoundaries();
        }
        StagedSource boundaries = null;
        if (decision.action() == Action.RELOAD) {
            try {
                boundaries = stageBoundaries();
            } catch (Exception e) {
                sourceFailed(DataSourceRepository.BOUNDARIES, NPL_BOUNDARY_FILE, e, firstBoot);
            }
        }
        StagedSource exposure = null;
        boolean exposureFailed = false;
        try {
            exposure = stageHumanExposure(boundaries != null);
        } catch (Exception e) {
            exposureFailed = true;
            sourceFailed(DataSourceRepository.EXPOSURE, HUMAN_EXPOSURE_FEED, e, firstBoot);
        }
        if (boundaries != null && exposure == null) {
            try {
                // With no live feed rows there is nothing for the boundary IDs to match yet.
                checkProjectedActiveSites(humanExposureRepository.projectActiveSites(true, false),
                        nplBoundaryRepository.countActiveSites(), dataProperties.getAcceptActiveCount(),
                        humanExposureRepository.liveCount() > 0);
            } catch (IllegalStateException e) {
                sourceFailed(DataSourceRepository.BOUNDARIES, NPL_BOUNDARY_FILE, e, firstBoot);
                boundaries = null;
            }
        }
        if (boundaries == null && exposure == null && !startup) {
            if (!exposureFailed) {
                dataSourceRepository.recordUnchanged(DataSourceRepository.EXPOSURE);
            }
            clearStaging();
            log.info("Data refresh done: no source changed");
            return;
        }
        try {
            swapIntoLive(boundaries, exposure);
        } catch (RuntimeException e) {
            // The transaction rolled back, so live data is as it was.
            String reason = "swap rolled back: " + describe(e);
            if (boundaries != null) {
                recordFailure(DataSourceRepository.BOUNDARIES, reason);
            }
            if (exposure != null) {
                recordFailure(DataSourceRepository.EXPOSURE, reason);
            }
            clearStaging();
            if (firstBoot) {
                throw new IllegalStateException("Initial data load failed with no live data to serve: " + reason, e);
            }
            log.error("Data refresh failed; keeping the live data: {}", reason, e);
            sitemapService.rebuild();
            return;
        }
        if (exposure == null && !exposureFailed) {
            dataSourceRepository.recordUnchanged(DataSourceRepository.EXPOSURE);
        }
    }

    /**
     * Records a failed source and logs it at ERROR, or throws when there is no live data to fall back
     * on. Nothing live has changed; staging is cleared at the end of the refresh or by the next load.
     */
    private void sourceFailed(String dataset, String label, Exception e, boolean firstBoot) {
        if (e instanceof InterruptedException) {
            Thread.currentThread().interrupt();
        }
        String reason = describe(e);
        recordFailure(dataset, reason);
        if (firstBoot) {
            throw new IllegalStateException(label + " failed and no data is live yet: " + reason, e);
        }
        if (e instanceof SourceRejectedException || e instanceof IOException) {
            log.error("{} refresh failed; keeping the live data: {}", label, reason);
        } else {
            log.error("{} refresh failed; keeping the live data: {}", label, reason, e);
        }
    }

    private void recordFailure(String dataset, String reason) {
        try {
            dataSourceRepository.recordFailure(dataset, reason);
        } catch (RuntimeException dbError) {
            log.error("Could not record the {} failure in data_source_state", dataset, dbError);
        }
    }

    static String describe(Exception e) {
        if (e.getMessage() == null) {
            // Never record a bare "null": name the exception and its causes instead.
            return SourceDownloader.summarize(e);
        }
        if (e instanceof SourceRejectedException || e instanceof IOException || e instanceof IllegalStateException) {
            return e.getMessage();
        }
        return e.getClass().getSimpleName() + ": " + e.getMessage();
    }

    /** First boot with V7 on a database whose boundaries predate data_source_state: no reload. */
    private void adoptLiveBoundaries() {
        String url = dataProperties.getNplBoundariesUrl().trim();
        int liveCount = nplBoundaryRepository.liveCount();
        LocalDateTime loadedAt = nplBoundaryRepository.lastLoadedAt().orElse(null);
        dataSourceRepository.recordAdopted(DataSourceRepository.BOUNDARIES, url, liveCount,
                nplBoundaryRepository.liveFinalCount(), loadedAt);
        log.warn("Adopted {} as the source of the {} live NPL boundaries (loaded {}) without reloading them; "
                + "its SHA-256 is recorded on the next real load", url, liveCount, loadedAt);
    }

    private StagedSource stageBoundaries() throws Exception {
        String url = StringUtils.trimWhitespace(dataProperties.getNplBoundariesUrl());
        if (!StringUtils.hasText(url)) {
            throw new IllegalStateException("SITEPLAIN_DATA_NPL_BOUNDARIES_URL is required to load boundaries");
        }
        int liveCount = nplBoundaryRepository.liveCount();
        try (DownloadedFile file = sourceDownloader.download(url, NPL_BOUNDARY_FILE,
                dataProperties.getBoundariesDownloadTimeout(), dataProperties.getBoundariesMaxSize().toBytes())) {
            BoundaryFileStats stats = nplBoundaryLoader.loadIntoStaging(file.path());
            List<String> problems = stats.problems(liveCount);
            if (!problems.isEmpty()) {
                throw new SourceRejectedException(NPL_BOUNDARY_FILE, problems);
            }
            log.info("NPL boundaries loaded into staging: {} rows, {} Final", stats.staged(), stats.finalCount());
            return new StagedSource(file, stats.staged(), stats.finalCount());
        }
    }

    /** Stages the exposure feed, or returns null when it is byte-for-byte the live feed. */
    private StagedSource stageHumanExposure(boolean boundariesStaged) throws Exception {
        String url = StringUtils.trimWhitespace(dataProperties.getHumanExposureUrl());
        if (!StringUtils.hasText(url)) {
            throw new IllegalStateException("SITEPLAIN_DATA_HUMAN_EXPOSURE_URL is not set");
        }
        try (DownloadedFile file = sourceDownloader.download(url, HUMAN_EXPOSURE_FEED,
                dataProperties.getHumanExposureDownloadTimeout(), dataProperties.getHumanExposureMaxSize().toBytes())) {
            DataSourceRecord live = dataSourceRepository.find(DataSourceRepository.EXPOSURE).orElse(null);
            if (live != null && file.sha256().equals(live.sha256()) && url.equals(live.sourceUrl())) {
                if (live.rowCount() != null && humanExposureRepository.liveMatchesRecord(live.rowCount(), live.loadedAt())) {
                    log.info("Human exposure feed unchanged (sha256 {}); keeping the live rows", file.sha256());
                    return null;
                }
                log.warn("Human exposure feed unchanged (sha256 {}), but the live rows ({}) no longer match the {} "
                        + "recorded at {}: something else wrote npl_human_exposure. Staging the feed again",
                        file.sha256(), humanExposureRepository.liveCount(), live.rowCount(), live.loadedAt());
            }
            ExposureFeedStats stats = humanExposureLoader.loadIntoStaging(file.path());
            List<String> problems = stats.problems();
            if (!problems.isEmpty()) {
                throw new SourceRejectedException(HUMAN_EXPOSURE_FEED, problems);
            }
            if (!stats.unknownStatusCodes().isEmpty()) {
                // Not fatal: such a site shows "Exposure status: CODE" until ExposureStatusMapper learns the code.
                log.warn("Human exposure feed has humexposurestscode values with no label: {}", stats.unknownStatusCodes());
            }
            Map<String, Integer> statusCounts = humanExposureRepository.stagingStatusCounts();
            checkExposureStatuses(statusCounts,
                    humanExposureRepository.projectActiveSites(boundariesStaged, true),
                    nplBoundaryRepository.countActiveSites(), dataProperties.getAcceptActiveCount());
            return new StagedSource(file, stats.staged(), statusCounts.getOrDefault("F", 0));
        }
    }

    private void swapIntoLive(StagedSource boundaries, StagedSource exposure) {
        long started = System.nanoTime();
        int[] cacheCounts = transactionTemplate.execute(status -> {
            if (boundaries != null) {
                nplBoundaryRepository.replaceLiveFromStaging();
                boundaries.record(dataSourceRepository, DataSourceRepository.BOUNDARIES);
            }
            if (exposure != null) {
                humanExposureRepository.replaceLiveFromStaging();
                exposure.record(dataSourceRepository, DataSourceRepository.EXPOSURE);
            }
            int seoCount = seoPageCacheBuilder.rebuild();
            if (seoCount == 0) {
                // Rolls the whole swap back: the validated sources can never yield zero active sites.
                throw new IllegalStateException("Refresh would leave no active sites; rolled back");
            }
            return new int[] {seoCount, statePageCacheBuilder.rebuild()};
        });
        long swapMillis = (System.nanoTime() - started) / 1_000_000;
        try {
            // After commit: fresh planner statistics for the replaced tables, then empty staging.
            if (boundaries != null) {
                nplBoundaryRepository.analyzeLive();
            }
            if (exposure != null) {
                humanExposureRepository.analyzeLive();
            }
            seoPageRepository.analyzeSiteCache();
            statePageRepository.analyze();
        } catch (RuntimeException e) {
            log.warn("ANALYZE after the data swap failed; autovacuum will analyze later", e);
        }
        clearStaging();
        try {
            sitemapService.rebuild();
        } catch (RuntimeException e) {
            log.error("Sitemap rebuild after the data swap failed; /sitemap.xml keeps its previous content", e);
        }
        log.info("Data refresh done in a {} ms swap. Boundaries {}, exposure {}. SEO pages: {}, State pages: {}",
                swapMillis,
                boundaries == null ? "unchanged" : "reloaded (" + boundaries.rows() + " rows)",
                exposure == null ? "unchanged" : "replaced (" + exposure.rows() + " rows)",
                cacheCounts[0], cacheCounts[1]);
    }

    private void clearStaging() {
        try {
            nplBoundaryRepository.clearStaging();
            humanExposureRepository.clearStaging();
        } catch (RuntimeException e) {
            log.warn("Could not clear the staging tables", e);
        }
    }

    /** Identity and counts of a validated, staged source, kept after its temp file is deleted. */
    private record StagedSource(String url, String sha256, long bytes, Instant lastModified, int rows, int finalCount) {

        StagedSource(DownloadedFile file, int rows, int finalCount) {
            this(file.sourceUrl(), file.sha256(), file.bytes(), file.lastModified(), rows, finalCount);
        }

        void record(DataSourceRepository repository, String dataset) {
            repository.recordLoaded(dataset, url, sha256, bytes, lastModified, rows, finalCount);
        }
    }

    /** Throws, before anything live changes, when the staged feed would publish a wrong active site set. */
    static void checkExposureStatuses(Map<String, Integer> statusCounts, ProjectedActiveSites projected,
                                      int liveActiveCount, Integer acceptActiveCount) {
        Set<String> unknown = new TreeSet<>(statusCounts.keySet());
        unknown.removeAll(KNOWN_NPL_STATUSES);
        if (!unknown.isEmpty()) {
            throw new IllegalStateException("Human exposure feed has unknown nplstatus values: " + unknown);
        }
        int finalCount = statusCounts.getOrDefault("F", 0);
        if (finalCount < MIN_FEED_FINAL || finalCount > MAX_FEED_FINAL) {
            throw new IllegalStateException("Human exposure feed Final count " + finalCount
                    + " is outside the expected band " + MIN_FEED_FINAL + ".." + MAX_FEED_FINAL);
        }
        checkProjectedActiveSites(projected, liveActiveCount, acceptActiveCount, true);
    }

    /**
     * Throws when the staged data would publish a wrong active site set: when too few of the projected
     * active sites have both a boundary and a feed row (one source's EPA IDs no longer match the
     * other's, which adds unmatched sites from both sides instead of removing any), or when the count
     * moves more than 5% either way from what is live.
     */
    static void checkProjectedActiveSites(ProjectedActiveSites projected, int liveActiveCount,
                                          Integer acceptActiveCount, boolean checkJoin) {
        if (checkJoin && projected.total() > 0 && projected.withBoth() < projected.total() * MIN_JOINED_RATIO) {
            throw new IllegalStateException("Only " + projected.withBoth() + " of " + projected.total()
                    + " projected active sites have both a boundary and an exposure feed row, under "
                    + Math.round(MIN_JOINED_RATIO * 100) + "%: the EPA IDs in the boundary file and the feed do not match");
        }
        checkActiveSiteChange(projected.total(), liveActiveCount, acceptActiveCount);
    }

    /**
     * Throws when the staged data would move the active site count by more than 5% either way, unless
     * SITEPLAIN_DATA_ACCEPT_ACTIVE_COUNT names exactly the new count.
     */
    static void checkActiveSiteChange(int stagingActiveCount, int liveActiveCount, Integer acceptActiveCount) {
        if (liveActiveCount <= 0) {
            return;
        }
        boolean drop = stagingActiveCount < liveActiveCount * MIN_ACTIVE_RATIO;
        boolean rise = stagingActiveCount > liveActiveCount * MAX_ACTIVE_RATIO;
        if (!drop && !rise) {
            return;
        }
        String change = "Active site count would " + (drop ? "drop" : "rise") + " from " + liveActiveCount
                + " to " + stagingActiveCount + ", more than " + Math.round((1 - MIN_ACTIVE_RATIO) * 100) + "%";
        if (acceptActiveCount != null && acceptActiveCount == stagingActiveCount) {
            log.warn("{}; accepted because SITEPLAIN_DATA_ACCEPT_ACTIVE_COUNT={}. Unset it once this refresh is live",
                    change, acceptActiveCount);
            return;
        }
        throw new IllegalStateException(change + ". If this change is expected, set SITEPLAIN_DATA_ACCEPT_ACTIVE_COUNT="
                + stagingActiveCount + " for the next boot");
    }
}
