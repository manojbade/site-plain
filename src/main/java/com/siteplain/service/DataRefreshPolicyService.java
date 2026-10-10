package com.siteplain.service;

import com.siteplain.config.DataProperties;
import com.siteplain.data.repository.DataSourceRepository;
import com.siteplain.data.repository.DataSourceRepository.DataSourceRecord;
import com.siteplain.data.repository.NplBoundaryRepository;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Decides whether boundaries reload, by source identity rather than age: only when no boundaries are
 * live, when SITEPLAIN_DATA_REFRESH_ON_STARTUP=true at startup, or when SITEPLAIN_DATA_NPL_BOUNDARIES_URL
 * differs from the URL recorded for the live boundaries. SITEPLAIN_DATA_MAX_AGE_DAYS is ignored.
 */
@Service
public class DataRefreshPolicyService {

    public enum Action { RELOAD, ADOPT, KEEP }

    public record BoundaryDecision(Action action, String reason) {
    }

    private final DataProperties dataProperties;
    private final NplBoundaryRepository nplBoundaryRepository;
    private final DataSourceRepository dataSourceRepository;

    public DataRefreshPolicyService(DataProperties dataProperties,
                                    NplBoundaryRepository nplBoundaryRepository,
                                    DataSourceRepository dataSourceRepository) {
        this.dataProperties = dataProperties;
        this.nplBoundaryRepository = nplBoundaryRepository;
        this.dataSourceRepository = dataSourceRepository;
    }

    public BoundaryDecision decideBoundaries(boolean startup) {
        if (!nplBoundaryRepository.liveDataPresent()) {
            return new BoundaryDecision(Action.RELOAD, "no boundaries are live (first boot)");
        }
        if (startup && dataProperties.isRefreshOnStartup()) {
            return new BoundaryDecision(Action.RELOAD, "SITEPLAIN_DATA_REFRESH_ON_STARTUP=true");
        }
        String configuredUrl = StringUtils.trimWhitespace(dataProperties.getNplBoundariesUrl());
        if (!StringUtils.hasText(configuredUrl)) {
            return new BoundaryDecision(Action.KEEP, "SITEPLAIN_DATA_NPL_BOUNDARIES_URL is not set");
        }
        String loadedUrl = dataSourceRepository.find(DataSourceRepository.BOUNDARIES)
                .map(DataSourceRecord::sourceUrl)
                .orElse(null);
        if (loadedUrl == null) {
            // Boundaries loaded before data_source_state existed (prod's first boot with V7).
            return new BoundaryDecision(Action.ADOPT, "the live boundaries have no recorded source");
        }
        if (!loadedUrl.equals(configuredUrl)) {
            return new BoundaryDecision(Action.RELOAD, "SITEPLAIN_DATA_NPL_BOUNDARIES_URL changed from " + loadedUrl);
        }
        return new BoundaryDecision(Action.KEEP, "SITEPLAIN_DATA_NPL_BOUNDARIES_URL matches the live source");
    }
}
