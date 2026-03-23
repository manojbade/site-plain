package com.siteplain.data.loader;

import com.siteplain.data.repository.SeoPageRepository;
import com.siteplain.domain.model.SeoPageData;
import com.siteplain.support.ExposureStatusMapper;
import org.springframework.stereotype.Component;

@Component
public class SeoPageCacheBuilder {

    private final SeoPageRepository seoPageRepository;

    public SeoPageCacheBuilder(SeoPageRepository seoPageRepository) {
        this.seoPageRepository = seoPageRepository;
    }

    public int rebuild() {
        seoPageRepository.clearSiteCache();
        int count = 0;
        for (SeoPageRepository.ActiveBoundaryRow row : seoPageRepository.findActiveBoundaryRows()) {
            seoPageRepository.insertSiteCacheRow(new SeoPageData(
                    row.epaId(),
                    row.siteName(),
                    row.stateCode(),
                    row.exposureStatusCode(),
                    ExposureStatusMapper.label(row.exposureStatusCode()),
                    row.exposurePathwayDesc(),
                    row.epaUrl()
            ));
            count++;
        }
        return count;
    }
}
