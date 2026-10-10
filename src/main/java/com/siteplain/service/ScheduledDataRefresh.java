package com.siteplain.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Daily exposure refresh between deploys, through the same path as the startup refresh
 * (DataBootstrapService.refresh(false)): same download limits and validation, same single swap
 * transaction, same advisory lock, and no swap when the feed is byte-for-byte the live one. On this
 * path SITEPLAIN_DATA_REFRESH_ON_STARTUP is not applied, so boundaries reload only when the configured
 * URL still differs from the live source (a boundary reload that failed at startup is retried).
 *
 * Afterwards it rebuilds the in-memory sitemap from the page caches, so an instance whose own
 * refresh was skipped (another instance held the lock and swapped new data in) catches up within a day.
 *
 * It never overlaps the startup refresh: it skips until the app is ready (Spring Boot marks it
 * ready only after the startup refresh has returned), and the advisory lock keeps it apart from a
 * refresh in another instance. Not created when SITEPLAIN_DATA_BOOTSTRAP_ENABLED=false; a cron of "-"
 * turns it off too.
 */
@Component
@EnableScheduling
@ConditionalOnProperty(name = "siteplain.data.bootstrapEnabled", havingValue = "true", matchIfMissing = true)
public class ScheduledDataRefresh {

    private static final Logger log = LoggerFactory.getLogger(ScheduledDataRefresh.class);

    private final DataBootstrapService dataBootstrapService;
    private final SitemapService sitemapService;
    private final ApplicationAvailability applicationAvailability;

    public ScheduledDataRefresh(DataBootstrapService dataBootstrapService,
                                SitemapService sitemapService,
                                ApplicationAvailability applicationAvailability) {
        this.dataBootstrapService = dataBootstrapService;
        this.sitemapService = sitemapService;
        this.applicationAvailability = applicationAvailability;
    }

    @Scheduled(cron = "${siteplain.data.scheduledRefreshCron:0 30 8 * * *}", zone = "UTC")
    public void refresh() {
        if (applicationAvailability.getReadinessState() != ReadinessState.ACCEPTING_TRAFFIC) {
            log.info("Scheduled data refresh skipped: the startup refresh has not finished");
            return;
        }
        log.info("Scheduled data refresh starting");
        try {
            dataBootstrapService.refresh(false);
        } catch (Exception e) {
            // With live data a failed source never throws; this is an unexpected error (the database
            // unreachable, say). The live data is untouched and the next run tries again.
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.error("Scheduled data refresh failed; keeping the live data", e);
        }
        try {
            sitemapService.rebuild();
        } catch (RuntimeException e) {
            log.error("Sitemap rebuild after the scheduled refresh failed; /sitemap.xml keeps its previous content", e);
        }
    }
}
