package com.siteplain.domain.view;

import com.siteplain.domain.model.NplSite;
import java.util.List;

public record ResultsViewModel(
        String inputAddress,
        String normalizedAddress,
        boolean unresolved,
        boolean hasSites,
        int resultCount,
        List<NplSite> sites,
        String mapboxPublicToken,
        Double userLat,
        Double userLng,
        String siteBoundaryGeojson
) {
}
