package com.siteplain.domain.view;

import com.siteplain.domain.model.NplSite;
import com.siteplain.support.StateResourceRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public record ResultsViewModel(
        String inputAddress,
        String normalizedAddress,
        boolean unresolved,
        boolean hasSites,
        int resultCount,
        List<NplSite> sites,
        String mapboxPublicToken,
        String stateCode,
        Double lat,
        Double lng,
        String siteBoundaryGeojson,
        StateResourceRegistry.StateResource stateResource
) {

    private static final String EPA_SUPERFUND_SITE_SEARCH_URL = "https://www.epa.gov/superfund/search-superfund-sites-where-you-live";
    private static final String EPA_SEMS_FACILITY_PAGE_URL = "https://cumulis.epa.gov/supercpad/cursites/srchsites.cfm";
    private static final String ATSDR_SITE_INFO_URL = "https://www.atsdr.cdc.gov/sites/npl/index.html";

    public List<OfficialResourceLink> getOfficialResources() {
        List<OfficialResourceLink> resources = new ArrayList<>();
        if (stateResource != null) {
            resources.add(new OfficialResourceLink(stateResource.name(), stateResource.url()));
        }
        resources.add(new OfficialResourceLink("EPA Superfund Site Search", EPA_SUPERFUND_SITE_SEARCH_URL));
        resources.add(new OfficialResourceLink("EPA SEMS facility page", EPA_SEMS_FACILITY_PAGE_URL));
        resources.add(new OfficialResourceLink("ATSDR site info", ATSDR_SITE_INFO_URL));
        if (lat != null && lng != null) {
            resources.add(new OfficialResourceLink(
                    "EPA EJSCREEN",
                    "https://ejscreen.epa.gov/mapper/?lat=%s&lon=%s&zoom=13".formatted(
                            formatCoordinate(lat),
                            formatCoordinate(lng)
                    )
            ));
        }
        return List.copyOf(resources);
    }

    private static String formatCoordinate(Double coordinate) {
        return String.format(Locale.US, "%.6f", coordinate);
    }
}
