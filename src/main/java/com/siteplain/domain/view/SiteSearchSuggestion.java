package com.siteplain.domain.view;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * One site search match. The status code drives the status square on the search results page;
 * it is left out of the suggestions JSON so that API stays as it was.
 */
public record SiteSearchSuggestion(
        String epaId,
        String siteName,
        String stateCode,
        String exposureStatusLabel,
        @JsonIgnore String exposureStatusCode
) {

    public SiteSearchSuggestion(String epaId, String siteName, String stateCode, String exposureStatusLabel) {
        this(epaId, siteName, stateCode, exposureStatusLabel, null);
    }
}
