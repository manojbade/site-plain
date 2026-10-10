package com.siteplain.data.loader;

import com.siteplain.data.repository.SeoPageRepository;
import com.siteplain.support.ExposureStatusMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class SeoPageCacheBuilder {

    private final SeoPageRepository seoPageRepository;

    public SeoPageCacheBuilder(SeoPageRepository seoPageRepository) {
        this.seoPageRepository = seoPageRepository;
    }

    /** Replaces the cache with one row per active site. Runs inside the refresh transaction. */
    public int rebuild() {
        Map<String, String> labelsByCode = new LinkedHashMap<>();
        for (String code : seoPageRepository.findActiveExposureStatusCodes()) {
            labelsByCode.put(code, ExposureStatusMapper.label(code));
        }
        seoPageRepository.clearSiteCache();
        return seoPageRepository.insertSiteCacheFromActiveSites(labelsByCode, ExposureStatusMapper.label(null));
    }
}
