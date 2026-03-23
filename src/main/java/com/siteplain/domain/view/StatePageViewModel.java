package com.siteplain.domain.view;

import com.siteplain.domain.model.SeoPageData;
import java.util.List;

public record StatePageViewModel(
        String stateCode,
        String stateName,
        int siteCount,
        List<SeoPageData> sites
) {
}
