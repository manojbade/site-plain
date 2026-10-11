package com.siteplain.domain.view;

import com.siteplain.domain.model.NplSite;
import com.siteplain.support.DistanceFormatter;
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
        StateResourceRegistry.StateResource stateResource,
        int activeSiteCount,
        List<NplSite> stateSitesWithoutBoundary,
        int sitesWithoutBoundaryCount,
        String approximateMatch,
        String exposureStatusAsOf,
        String pdfPath
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

    /** "3 active Superfund sites found within 3 miles", with "site" singular for one. */
    public String resultCountSentence() {
        return resultCount + " active Superfund " + (resultCount == 1 ? "site" : "sites") + " found within 3 miles";
    }

    /** Distance to the nearest site boundary in the short summary-row form, or null with no sites. */
    public String nearestDistanceShort() {
        if (sites == null || sites.isEmpty()) {
            return null;
        }
        double nearest = sites.stream().mapToDouble(NplSite::getDistanceMeters).min().orElse(0);
        return DistanceFormatter.shortFormat(nearest);
    }

    /** Sites whose EPA human exposure status color is the given one (red, yellow, green or gray). */
    public long statusCount(String color) {
        if (sites == null) {
            return 0;
        }
        return sites.stream()
                .filter(site -> color.equals(site.getExposureStatusColor() == null ? "gray" : site.getExposureStatusColor()))
                .count();
    }
}
