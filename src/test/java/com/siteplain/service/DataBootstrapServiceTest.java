package com.siteplain.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.siteplain.config.DataProperties;
import com.siteplain.data.loader.HumanExposureLoader;
import com.siteplain.data.loader.NplBoundaryLoader;
import com.siteplain.data.loader.SeoPageCacheBuilder;
import com.siteplain.data.loader.StatePageCacheBuilder;
import com.siteplain.data.repository.HumanExposureRepository;
import com.siteplain.data.repository.NplBoundaryRepository;
import com.siteplain.data.repository.SeoPageRepository;
import com.siteplain.data.repository.StatePageRepository;
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
    void refresh_withAllStatusesSpelledOut_neverSwapsOrRebuilds() throws Exception {
        DataProperties properties = new DataProperties();
        properties.setHumanExposureUrl("file:///feed.json");
        DataRefreshPolicyService policy = mock(DataRefreshPolicyService.class);
        HumanExposureLoader loader = mock(HumanExposureLoader.class);
        HumanExposureRepository exposureRepository = mock(HumanExposureRepository.class);
        NplBoundaryRepository boundaryRepository = mock(NplBoundaryRepository.class);
        SeoPageCacheBuilder seoBuilder = mock(SeoPageCacheBuilder.class);
        StatePageCacheBuilder stateBuilder = mock(StatePageCacheBuilder.class);
        SitemapService sitemapService = mock(SitemapService.class);
        when(policy.shouldRefreshBoundaries()).thenReturn(false);
        when(loader.loadIntoStaging(anyString())).thenReturn(1905);
        when(exposureRepository.stagingStatusCounts()).thenReturn(Map.of("FINAL", 1337, "DELETED", 466, "NOT NPL", 102));
        when(exposureRepository.stagingActiveSiteCount(false)).thenReturn(0);
        when(boundaryRepository.countActiveSites()).thenReturn(1343);

        DataBootstrapService service = new DataBootstrapService(properties, policy, mock(NplBoundaryLoader.class), loader,
                boundaryRepository, exposureRepository, seoBuilder, stateBuilder, mock(SeoPageRepository.class),
                mock(StatePageRepository.class), sitemapService,
                mock(PlatformTransactionManager.class));

        assertThatThrownBy(service::refreshIfNeeded).isInstanceOf(IllegalStateException.class);
        verify(exposureRepository, never()).replaceLiveFromStaging();
        verify(seoBuilder, never()).rebuild();
        verify(stateBuilder, never()).rebuild();
        verify(sitemapService, never()).rebuild();
    }
}
