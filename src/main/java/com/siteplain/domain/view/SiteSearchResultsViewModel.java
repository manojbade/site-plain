package com.siteplain.domain.view;

import java.util.List;

public record SiteSearchResultsViewModel(
        String query,
        List<SiteSearchSuggestion> matches
) {

    public boolean hasMatches() {
        return matches != null && !matches.isEmpty();
    }
}
