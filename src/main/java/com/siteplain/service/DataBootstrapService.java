package com.siteplain.service;

import com.siteplain.config.DataProperties;
import com.siteplain.data.loader.HumanExposureLoader;
import com.siteplain.data.loader.NplBoundaryLoader;
import com.siteplain.data.loader.SeoPageCacheBuilder;
import com.siteplain.data.loader.StatePageCacheBuilder;
import com.siteplain.data.repository.HumanExposureRepository;
import com.siteplain.data.repository.NplBoundaryRepository;
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

    private final DataProperties dataProperties;
    private final DataRefreshPolicyService dataRefreshPolicyService;
    private final NplBoundaryLoader nplBoundaryLoader;
    private final HumanExposureLoader humanExposureLoader;
    private final NplBoundaryRepository nplBoundaryRepository;
    private final HumanExposureRepository humanExposureRepository;
    private final SeoPageCacheBuilder seoPageCacheBuilder;
    private final StatePageCacheBuilder statePageCacheBuilder;
    private final SitemapService sitemapService;
    private final TransactionTemplate transactionTemplate;

    public DataBootstrapService(DataProperties dataProperties,
                                DataRefreshPolicyService dataRefreshPolicyService,
                                NplBoundaryLoader nplBoundaryLoader,
                                HumanExposureLoader humanExposureLoader,
                                NplBoundaryRepository nplBoundaryRepository,
                                HumanExposureRepository humanExposureRepository,
                                SeoPageCacheBuilder seoPageCacheBuilder,
                                StatePageCacheBuilder statePageCacheBuilder,
                                SitemapService sitemapService,
                                PlatformTransactionManager transactionManager) {
        this.dataProperties = dataProperties;
        this.dataRefreshPolicyService = dataRefreshPolicyService;
        this.nplBoundaryLoader = nplBoundaryLoader;
        this.humanExposureLoader = humanExposureLoader;
        this.nplBoundaryRepository = nplBoundaryRepository;
        this.humanExposureRepository = humanExposureRepository;
        this.seoPageCacheBuilder = seoPageCacheBuilder;
        this.statePageCacheBuilder = statePageCacheBuilder;
        this.sitemapService = sitemapService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (!dataProperties.isBootstrapEnabled()) {
            log.info("Site Plain bootstrap is disabled");
            return;
        }
        refreshIfNeeded();
    }

    public void refreshIfNeeded() throws Exception {
        refreshBoundariesIfNeeded();
        refreshHumanExposureAndCaches();
    }

    private void refreshBoundariesIfNeeded() throws Exception {
        if (!dataRefreshPolicyService.shouldRefreshBoundaries()) {
            return;
        }
        if (!StringUtils.hasText(dataProperties.getNplBoundariesUrl())) {
            throw new IllegalStateException("SITEPLAIN_DATA_NPL_BOUNDARIES_URL is required for initial boundary load");
        }
        int liveCount = nplBoundaryRepository.liveCount();
        int stagingCount = nplBoundaryLoader.loadIntoStaging(dataProperties.getNplBoundariesUrl());
        if (stagingCount <= 0) {
            throw new IllegalStateException("NPL boundary staging load produced zero rows");
        }
        if (liveCount > 0 && stagingCount < (liveCount * 0.9)) {
            throw new IllegalStateException("Boundary staging row count dropped below 90% of live count");
        }
        transactionTemplate.executeWithoutResult(status -> nplBoundaryRepository.swapStagingToLive());
        log.info("NPL boundaries loaded into staging: {} rows", stagingCount);
    }

    private void refreshHumanExposureAndCaches() throws Exception {
        int exposureCount = humanExposureLoader.loadIntoStaging(dataProperties.getHumanExposureUrl());
        if (exposureCount <= 1500) {
            throw new IllegalStateException("Human exposure staging row count is unexpectedly low: " + exposureCount);
        }
        humanExposureRepository.swapStagingToLive();
        int seoCount = seoPageCacheBuilder.rebuild();
        int stateCount = statePageCacheBuilder.rebuild();
        sitemapService.rebuild();
        log.info("Bootstrap complete. Sites: {}, Exposure records: {}, SEO pages: {}, State pages: {}",
                nplBoundaryRepository.countActiveSites(), exposureCount, seoCount, stateCount);
    }
}
