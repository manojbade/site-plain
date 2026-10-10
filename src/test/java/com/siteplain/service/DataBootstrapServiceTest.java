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
        assertThatCode(() -> DataBootstrapService.checkExposureStatuses(REAL_FEED, 1343, 1328))
                .doesNotThrowAnyException();
        // First load into an empty database: no live count to compare against.
        assertThatCode(() -> DataBootstrapService.checkExposureStatuses(REAL_FEED, 1343, 0))
                .doesNotThrowAnyException();
    }

    @Test
    void checkExposureStatuses_rejectsAnUnknownStatusVocabulary() {
        assertThatThrownBy(() -> DataBootstrapService.checkExposureStatuses(
                Map.of("Final", 1337, "Deleted", 466), 21, 1343))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unknown nplstatus values: [Deleted, Final]");
        assertThatThrownBy(() -> DataBootstrapService.checkExposureStatuses(
                Map.of("F", 1336, "D", 466, "X", 1), 1342, 1343))
                .hasMessageContaining("[X]");
    }

    @Test
    void checkExposureStatuses_rejectsAFinalCountOutsideTheBand() {
        assertThatThrownBy(() -> DataBootstrapService.checkExposureStatuses(Map.of("F", 900, "D", 1000), 910, 0))
                .hasMessageContaining("Final count 900 is outside the expected band 1200..1450");
        assertThatThrownBy(() -> DataBootstrapService.checkExposureStatuses(Map.of("F", 1800), 1800, 0))
                .hasMessageContaining("Final count 1800");
    }

    @Test
    void checkExposureStatuses_rejectsALargeDropInActiveSites() {
        assertThatThrownBy(() -> DataBootstrapService.checkExposureStatuses(REAL_FEED, 1260, 1343))
                .hasMessageContaining("Active site count would drop from 1343 to 1260");
        assertThatCode(() -> DataBootstrapService.checkExposureStatuses(REAL_FEED, 1280, 1343))
                .doesNotThrowAnyException();
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
        when(exposureRepository.projectedActiveSiteCount(false, true)).thenReturn(0);
        when(boundaryRepository.countActiveSites()).thenReturn(1343);

        DataBootstrapService service = new DataBootstrapService(properties, policy, mock(DataSourceRepository.class), downloader, mock(NplBoundaryLoader.class), loader,
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
        DataBootstrapService service = new DataBootstrapService(properties, policy, mock(DataSourceRepository.class),
                mock(SourceDownloader.class), mock(NplBoundaryLoader.class), mock(HumanExposureLoader.class),
                mock(NplBoundaryRepository.class), mock(HumanExposureRepository.class), mock(SeoPageCacheBuilder.class),
                mock(StatePageCacheBuilder.class), mock(SeoPageRepository.class), mock(StatePageRepository.class),
                sitemapService, mock(PlatformTransactionManager.class));

        service.run(null);

        verify(sitemapService).rebuild();
        verify(policy, never()).decideBoundaries(anyBoolean());
    }

    @Test
    void describe_keepsRejectionMessagesAndNamesOtherExceptions() {
        assertThat(DataBootstrapService.describe(new java.io.IOException("Human exposure feed: HTTP 503 from x")))
                .isEqualTo("Human exposure feed: HTTP 503 from x");
        assertThat(DataBootstrapService.describe(new IllegalArgumentException("bad")))
                .isEqualTo("IllegalArgumentException: bad");
    }
}
