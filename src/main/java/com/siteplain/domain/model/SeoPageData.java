package com.siteplain.domain.model;

public record SeoPageData(
        String epaId,
        String siteName,
        String stateCode,
        String exposureStatusCode,
        String exposureStatusLabel,
        String exposurePathwayDescription,
        String epaUrl
) {

    public String effectiveEpaUrl() {
        if (epaUrl != null && !epaUrl.isBlank()) {
            return epaUrl;
        }
        return "https://cumulis.epa.gov/supercpad/cursites/srchsites.cfm?search_string=" + epaId;
    }
}
