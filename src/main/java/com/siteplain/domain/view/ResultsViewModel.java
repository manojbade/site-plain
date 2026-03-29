package com.siteplain.domain.view;

import com.siteplain.domain.model.NplSite;
import com.siteplain.support.StateResourceRegistry;
import java.util.ArrayList;
import java.util.List;

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
        return List.copyOf(resources);
    }
}
