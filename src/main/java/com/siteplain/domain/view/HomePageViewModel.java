package com.siteplain.domain.view;

import java.time.LocalDate;

public record HomePageViewModel(
        int totalSiteCount,
        String totalPopulationText,
        String totalDistrictsText,
        LocalDate updatedDate
) {
}
