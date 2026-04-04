package com.siteplain.domain.view;

public record SiteSearchSuggestion(
        String epaId,
        String siteName,
        String stateCode,
        String exposureStatusLabel
) {
}
