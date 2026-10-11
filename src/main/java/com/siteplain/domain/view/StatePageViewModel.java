package com.siteplain.domain.view;

import com.siteplain.domain.model.SeoPageData;
import com.siteplain.support.ExposureStatusMapper;
import java.util.List;

public record StatePageViewModel(
        String stateCode,
        String stateName,
        int siteCount,
        List<SeoPageData> sites
) {

    public List<SeoPageData> sitesWithBoundary() {
        return sites.stream().filter(SeoPageData::hasBoundary).toList();
    }

    public List<SeoPageData> sitesWithoutBoundary() {
        return sites.stream().filter(site -> !site.hasBoundary()).toList();
    }

    /** Sites whose EPA human exposure status color is the given one (red, yellow, green or gray). */
    public long statusCount(String color) {
        return sites.stream().filter(site -> color.equals(ExposureStatusMapper.color(site.exposureStatusCode()))).count();
    }
}
