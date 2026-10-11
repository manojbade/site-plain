package com.siteplain.domain.view;

import java.time.LocalDate;
import java.util.List;

public record HomePageViewModel(
        int totalSiteCount,
        String totalPopulationText,
        String totalDistrictsText,
        LocalDate updatedDate,
        List<DataSourceLink> dataSources
) {
}
