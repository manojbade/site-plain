package com.siteplain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.siteplain.config.DataProperties;
import com.siteplain.data.repository.DataSourceRepository;
import com.siteplain.data.repository.DataSourceRepository.DataSourceRecord;
import com.siteplain.data.repository.NplBoundaryRepository;
import com.siteplain.service.DataRefreshPolicyService.Action;
import com.siteplain.service.DataRefreshPolicyService.BoundaryDecision;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DataRefreshPolicyServiceTest {

    private static final String RELEASE =
            "https://github.com/manojbade/site-plain/releases/download/data-2026-07-12/npl_site_boundaries.geojson";

    private final DataProperties properties = new DataProperties();
    private final NplBoundaryRepository boundaries = mock(NplBoundaryRepository.class);
    private final DataSourceRepository sources = mock(DataSourceRepository.class);
    private final DataRefreshPolicyService policy = new DataRefreshPolicyService(properties, boundaries, sources);

    @BeforeEach
    void prodLikeDefaults() {
        properties.setNplBoundariesUrl(RELEASE);
        properties.setMaxAgeDays(90);
        when(boundaries.liveDataPresent()).thenReturn(true);
    }

    @Test
    void emptyTable_reloads() {
        when(boundaries.liveDataPresent()).thenReturn(false);

        assertThat(policy.decideBoundaries(true).action()).isEqualTo(Action.RELOAD);
        assertThat(policy.decideBoundaries(false).action()).isEqualTo(Action.RELOAD);
    }

    @Test
    void refreshOnStartup_reloadsOnlyAtStartup() {
        properties.setRefreshOnStartup(true);
        when(sources.find(DataSourceRepository.BOUNDARIES)).thenReturn(Optional.of(record(RELEASE)));

        assertThat(policy.decideBoundaries(true))
                .isEqualTo(new BoundaryDecision(Action.RELOAD, "SITEPLAIN_DATA_REFRESH_ON_STARTUP=true"));
        assertThat(policy.decideBoundaries(false).action()).isEqualTo(Action.KEEP);
    }

    @Test
    void liveBoundariesWithNoRecordedSource_areAdoptedNotReloaded() {
        when(sources.find(DataSourceRepository.BOUNDARIES)).thenReturn(Optional.empty());

        assertThat(policy.decideBoundaries(true).action()).isEqualTo(Action.ADOPT);

        // A FAILED row written before any successful load has no source either.
        when(sources.find(DataSourceRepository.BOUNDARIES)).thenReturn(Optional.of(record(null)));
        assertThat(policy.decideBoundaries(true).action()).isEqualTo(Action.ADOPT);
    }

    @Test
    void changedUrl_reloads_sameUrl_keepsWhateverTheAge() {
        when(sources.find(DataSourceRepository.BOUNDARIES)).thenReturn(Optional.of(record(RELEASE)));
        assertThat(policy.decideBoundaries(true).action()).isEqualTo(Action.KEEP);

        properties.setNplBoundariesUrl(" " + RELEASE.replace("data-2026-07-12", "data-2026-10-20") + " ");
        assertThat(policy.decideBoundaries(true))
                .isEqualTo(new BoundaryDecision(Action.RELOAD, "SITEPLAIN_DATA_NPL_BOUNDARIES_URL changed from " + RELEASE));
    }

    @Test
    void noConfiguredUrl_keepsLiveBoundaries() {
        properties.setNplBoundariesUrl("  ");

        assertThat(policy.decideBoundaries(true).action()).isEqualTo(Action.KEEP);
    }

    private static DataSourceRecord record(String url) {
        // Loaded in July, long past the old 90-day trigger.
        return new DataSourceRecord(DataSourceRepository.BOUNDARIES, url, null, null, null, 1908, 1328,
                Instant.parse("2026-07-13T00:36:21Z"), Instant.parse("2026-07-13T00:36:21Z"),
                url == null ? "FAILED" : "ADOPTED", null);
    }
}
