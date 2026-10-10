package com.siteplain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.siteplain.config.DataProperties;
import com.siteplain.data.loader.DownloadedFile;
import com.siteplain.data.loader.ExposureFeedStats;
import com.siteplain.data.loader.HumanExposureLoader;
import com.siteplain.data.loader.NplBoundaryLoader;
import com.siteplain.data.loader.SeoPageCacheBuilder;
import com.siteplain.data.loader.SourceDownloader;
import com.siteplain.data.loader.StatePageCacheBuilder;
import com.siteplain.data.repository.DataSourceRepository;
import com.siteplain.data.repository.HumanExposureRepository;
import com.siteplain.data.repository.HumanExposureRepository.ProjectedActiveSites;
import com.siteplain.data.repository.NplBoundaryRepository;
import com.siteplain.data.repository.SeoPageRepository;
import com.siteplain.data.repository.StatePageRepository;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

class DataBootstrapServiceTest {

    // Status mix of the real 2026-10-10 feed.
    private static final Map<String, Integer> REAL_FEED = Map.of("F", 1337, "D", 466, "N", 60, "P", 37, "R", 3, "S", 2);

    @Test
    void checkExposureStatuses_acceptsTheRealFeedMix() {
        assertThatCode(() -> DataBootstrapService.checkExposureStatuses(REAL_FEED, real(1343), 1328, null))
                .doesNotThrowAnyException();
        // First load into an empty database: no live count to compare against.
        assertThatCode(() -> DataBootstrapService.checkExposureStatuses(REAL_FEED, real(1343), 0, null))
                .doesNotThrowAnyException();
    }

    @Test
    void checkExposureStatuses_rejectsAnUnknownStatusVocabulary() {
        assertThatThrownBy(() -> DataBootstrapService.checkExposureStatuses(
                Map.of("Final", 1337, "Deleted", 466), real(21), 1343, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unknown nplstatus values: [Deleted, Final]");
        assertThatThrownBy(() -> DataBootstrapService.checkExposureStatuses(
                Map.of("F", 1336, "D", 466, "X", 1), real(1342), 1343, null))
                .hasMessageContaining("[X]");
    }

    @Test
    void checkExposureStatuses_rejectsAFinalCountOutsideTheBand() {
        assertThatThrownBy(() -> DataBootstrapService.checkExposureStatuses(Map.of("F", 900, "D", 1000), real(910), 0, null))
                .hasMessageContaining("Final count 900 is outside the expected band 1200..1450");
        assertThatThrownBy(() -> DataBootstrapService.checkExposureStatuses(Map.of("F", 1800), real(1800), 0, null))
                .hasMessageContaining("Final count 1800");
    }

    @Test
    void checkExposureStatuses_rejectsALargeDropInActiveSites() {
        assertThatThrownBy(() -> DataBootstrapService.checkExposureStatuses(REAL_FEED, real(1260), 1343, null))
                .hasMessageContaining("Active site count would drop from 1343 to 1260");
        assertThatCode(() -> DataBootstrapService.checkExposureStatuses(REAL_FEED, real(1280), 1343, null))
                .doesNotThrowAnyException();
    }

    @Test
    void checkExposureStatuses_rejectsALargeRiseInActiveSites() {
        assertThatThrownBy(() -> DataBootstrapService.checkExposureStatuses(REAL_FEED, real(2665), 1337, null))
                .hasMessage("Active site count would rise from 1337 to 2665, more than 5%. If this change is expected, "
                        + "set SITEPLAIN_DATA_ACCEPT_ACTIVE_COUNT=2665 for the next boot");
        assertThatCode(() -> DataBootstrapService.checkExposureStatuses(REAL_FEED, real(1400), 1337, null))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> DataBootstrapService.checkExposureStatuses(REAL_FEED, real(1410), 1337, null))
                .hasMessageContaining("would rise from 1337 to 1410");
    }

    @Test
    void checkExposureStatuses_rejectsSourcesWhoseIdsDoNotJoin() {
        assertThatThrownBy(() -> DataBootstrapService.checkExposureStatuses(REAL_FEED,
                new ProjectedActiveSites(2665, 0), 1337, 2665))
                .hasMessage("Only 0 of 2665 projected active sites have both a boundary and an exposure feed row, "
                        + "under 90%: the EPA IDs in the boundary file and the feed do not match");
        // Real data: 1,322 of 1,337.
        assertThatCode(() -> DataBootstrapService.checkExposureStatuses(REAL_FEED,
                new ProjectedActiveSites(1337, 1322), 1337, null)).doesNotThrowAnyException();
        assertThatThrownBy(() -> DataBootstrapService.checkExposureStatuses(REAL_FEED,
                new ProjectedActiveSites(1337, 1200), 1337, null)).hasMessageContaining("Only 1200 of 1337");
    }

    @Test
    void checkProjectedActiveSites_skipsTheJoinWhenTheOtherSideIsEmpty() {
        assertThatCode(() -> DataBootstrapService.checkProjectedActiveSites(
                new ProjectedActiveSites(1328, 0), 1328, null, false)).doesNotThrowAnyException();
    }

    @Test
    void checkActiveSiteChange_acceptsOnlyTheExactCountTheOperatorNamed() {
        assertThatCode(() -> DataBootstrapService.checkActiveSiteChange(1337, 2665, 1337)).doesNotThrowAnyException();
        assertThatThrownBy(() -> DataBootstrapService.checkActiveSiteChange(1338, 2665, 1337))
                .hasMessage("Active site count would drop from 2665 to 1338, more than 5%. If this change is expected, "
                        + "set SITEPLAIN_DATA_ACCEPT_ACTIVE_COUNT=1338 for the next boot");
        // Within 5% the override plays no part.
        assertThatCode(() -> DataBootstrapService.checkActiveSiteChange(1340, 1337, 99)).doesNotThrowAnyException();
    }

    /** A projection in which every active site has a boundary and a feed row. */
    private static ProjectedActiveSites real(int total) {
        return new ProjectedActiveSites(total, total);
    }

    @Test
    void firstBoot_withAllStatusesSpelledOut_abortsWithoutSwappingOrRebuilding() throws Exception {
        DataProperties properties = new DataProperties();
        properties.setHumanExposureUrl("file:///feed.json");
        DataRefreshPolicyService policy = mock(DataRefreshPolicyService.class);
        HumanExposureLoader loader = mock(HumanExposureLoader.class);
        HumanExposureRepository exposureRepository = mock(HumanExposureRepository.class);
        NplBoundaryRepository boundaryRepository = mock(NplBoundaryRepository.class);
        SeoPageCacheBuilder seoBuilder = mock(SeoPageCacheBuilder.class);
        StatePageCacheBuilder stateBuilder = mock(StatePageCacheBuilder.class);
        SitemapService sitemapService = mock(SitemapService.class);
        when(policy.decideBoundaries(true)).thenReturn(
                new DataRefreshPolicyService.BoundaryDecision(DataRefreshPolicyService.Action.KEEP, "test"));
        ExposureFeedStats stats = mock(ExposureFeedStats.class);
        when(stats.problems()).thenReturn(List.of());
        when(stats.staged()).thenReturn(1905);
        when(stats.unknownStatusCodes()).thenReturn(Map.of());
        when(loader.loadIntoStaging(any(Path.class))).thenReturn(stats);
        SourceDownloader downloader = mock(SourceDownloader.class);
        when(downloader.download(anyString(), anyString(), any(), anyLong()))
                .thenReturn(new DownloadedFile("file:///feed.json", Path.of("/nonexistent/feed.json"), 10, "ab", null));
        when(exposureRepository.stagingStatusCounts()).thenReturn(Map.of("FINAL", 1337, "DELETED", 466, "NOT NPL", 102));
        when(exposureRepository.projectActiveSites(false, true)).thenReturn(new ProjectedActiveSites(0, 0));
        when(boundaryRepository.countActiveSites()).thenReturn(1343);

        DataBootstrapService service = new DataBootstrapService(properties, policy, mock(DataSourceRepository.class),
                runningLock(), downloader, mock(NplBoundaryLoader.class), loader,
                boundaryRepository, exposureRepository, seoBuilder, stateBuilder, mock(SeoPageRepository.class),
                mock(StatePageRepository.class), sitemapService,
                mock(PlatformTransactionManager.class));

        // No boundaries are live (the mock reports none), so the rejected feed aborts startup.
        assertThatThrownBy(() -> service.refresh(true)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no data is live yet: Human exposure feed has unknown nplstatus values");
        verify(exposureRepository, never()).replaceLiveFromStaging();
        verify(seoBuilder, never()).rebuild();
        verify(stateBuilder, never()).rebuild();
        verify(sitemapService, never()).rebuild();
    }

    @Test
    void disabledBootstrap_stillBuildsTheSitemapFromTheLiveCaches() throws Exception {
        DataProperties properties = new DataProperties();
        properties.setBootstrapEnabled(false);
        SitemapService sitemapService = mock(SitemapService.class);
        DataRefreshPolicyService policy = mock(DataRefreshPolicyService.class);
        DataBootstrapService service = new DataBootstrapService(properties, policy, mock(DataSourceRepository.class), runningLock(),
                mock(SourceDownloader.class), mock(NplBoundaryLoader.class), mock(HumanExposureLoader.class),
                mock(NplBoundaryRepository.class), mock(HumanExposureRepository.class), mock(SeoPageCacheBuilder.class),
                mock(StatePageCacheBuilder.class), mock(SeoPageRepository.class), mock(StatePageRepository.class),
                sitemapService, mock(PlatformTransactionManager.class));

        service.run(null);

        verify(sitemapService).rebuild();
        verify(policy, never()).decideBoundaries(anyBoolean());
    }

    @Test
    void refresh_whenAnotherInstanceHoldsTheLock_skipsAndStillBuildsTheSitemapAtStartup() throws Exception {
        DataRefreshLock lock = mock(DataRefreshLock.class);
        when(lock.runExclusively(any())).thenReturn(false);
        DataRefreshPolicyService policy = mock(DataRefreshPolicyService.class);
        SourceDownloader downloader = mock(SourceDownloader.class);
        SitemapService sitemapService = mock(SitemapService.class);
        DataBootstrapService service = new DataBootstrapService(new DataProperties(), policy, mock(DataSourceRepository.class),
                lock, downloader, mock(NplBoundaryLoader.class), mock(HumanExposureLoader.class),
                mock(NplBoundaryRepository.class), mock(HumanExposureRepository.class), mock(SeoPageCacheBuilder.class),
                mock(StatePageCacheBuilder.class), mock(SeoPageRepository.class), mock(StatePageRepository.class),
                sitemapService, mock(PlatformTransactionManager.class));

        service.refresh(true);
        service.refresh(false);

        verify(policy, never()).decideBoundaries(anyBoolean());
        verify(downloader, never()).download(anyString(), anyString(), any(), anyLong());
        verify(sitemapService, times(1)).rebuild();
    }

    /** A lock that is always free: runs the work. */
    private static DataRefreshLock runningLock() throws Exception {
        DataRefreshLock lock = mock(DataRefreshLock.class);
        when(lock.runExclusively(any())).thenAnswer(invocation -> {
            invocation.<DataRefreshLock.Work>getArgument(0).run();
            return true;
        });
        return lock;
    }

    @Test
    void describe_keepsRejectionMessagesAndNamesOtherExceptions() {
        assertThat(DataBootstrapService.describe(new java.io.IOException("Human exposure feed: HTTP 503 from x")))
                .isEqualTo("Human exposure feed: HTTP 503 from x");
        assertThat(DataBootstrapService.describe(new IllegalArgumentException("bad")))
                .isEqualTo("IllegalArgumentException: bad");
    }

    @Test
    void describe_namesTheExceptionChainWhenThereIsNoMessage() {
        java.net.ConnectException refused = new java.net.ConnectException();
        refused.initCause(new java.nio.channels.ClosedChannelException());
        assertThat(DataBootstrapService.describe(refused)).isEqualTo("ConnectException, caused by ClosedChannelException");
        assertThat(DataBootstrapService.describe(new IllegalStateException())).isEqualTo("IllegalStateException");
    }
}
