package com.siteplain.domain.view;

import com.siteplain.domain.model.SeoPageData;
import com.siteplain.support.StateNames;

public record SitePageViewModel(
        SeoPageData pageData,
        String exposureStatusAsOf
) {

    public String metaDescription() {
        StringBuilder description = new StringBuilder(pageData.siteName())
                .append(" is an active EPA Superfund site");
        if (pageData.stateCode() != null && !pageData.stateCode().isBlank()) {
            description.append(" in ").append(StateNames.nameFor(pageData.stateCode()));
        }
        description.append(". ").append(pageData.exposureStatusLabel()).append(". ");
        if (pageData.hasBoundary()) {
            description.append("Enter your address to see your distance from this site.");
        } else {
            description.append("EPA has not published a boundary for this site.");
        }
        return description.toString();
    }

    /** Full state name for the state link, or null when the site has no known state. */
    public String stateName() {
        String code = pageData.stateCode();
        if (code == null || code.isBlank() || !StateNames.isKnown(code)) {
            return null;
        }
        return StateNames.nameFor(code);
    }
}
