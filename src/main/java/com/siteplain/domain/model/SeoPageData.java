package com.siteplain.domain.model;

import com.siteplain.support.EpaUrls;

public record SeoPageData(
        String epaId,
        String siteName,
        String stateCode,
        String exposureStatusCode,
        String exposureStatusLabel,
        String exposurePathwayDescription,
        String epaUrl,
        boolean hasBoundary
) {

    public String effectiveEpaUrl() {
        return EpaUrls.profileUrl(epaUrl, epaId);
    }
}
