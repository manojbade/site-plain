package com.siteplain.domain.model;

import java.util.List;

public record NplLookupResult(
        GeocodedAddress address,
        List<NplSite> sites,
        Double nearestMiles,
        boolean unresolved
) {
}
