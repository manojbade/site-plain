package com.siteplain.domain.model;

import com.siteplain.support.EpaText;
import com.siteplain.support.EpaUrls;
import java.util.List;

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

    /** The pathway description split at its line breaks, for escaped rendering (see EpaText). */
    public List<String> exposurePathwayLines() {
        return EpaText.lines(exposurePathwayDescription);
    }
}
