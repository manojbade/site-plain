package com.siteplain.domain.view;

import com.siteplain.domain.model.SeoPageData;
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
}
