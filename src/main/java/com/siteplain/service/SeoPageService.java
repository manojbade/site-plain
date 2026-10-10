package com.siteplain.service;

import com.siteplain.data.repository.HumanExposureRepository;
import com.siteplain.data.repository.SeoPageRepository;
import com.siteplain.data.repository.StatePageRepository;
import com.siteplain.domain.view.SitePageViewModel;
import com.siteplain.domain.view.StatePageViewModel;
import com.siteplain.support.DisplayDates;
import java.util.Locale;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class SeoPageService {

    private final SeoPageRepository seoPageRepository;
    private final StatePageRepository statePageRepository;
    private final HumanExposureRepository humanExposureRepository;

    public SeoPageService(SeoPageRepository seoPageRepository,
                          StatePageRepository statePageRepository,
                          HumanExposureRepository humanExposureRepository) {
        this.seoPageRepository = seoPageRepository;
        this.statePageRepository = statePageRepository;
        this.humanExposureRepository = humanExposureRepository;
    }

    public Optional<SitePageViewModel> buildSitePage(String epaId) {
        return seoPageRepository.findByEpaId(epaId.toUpperCase(Locale.ROOT))
                .map(pageData -> new SitePageViewModel(
                        pageData,
                        DisplayDates.format(humanExposureRepository.statusAsOfDate().orElse(null))
                ));
    }

    public Optional<StatePageViewModel> buildStatePage(String stateCode) {
        String normalized = stateCode.toUpperCase(Locale.ROOT);
        return statePageRepository.findByStateCode(normalized)
                .map(summary -> new StatePageViewModel(
                        summary.stateCode(),
                        summary.stateName(),
                        summary.siteCount(),
                        seoPageRepository.findByStateCode(normalized)
                ));
    }
}
