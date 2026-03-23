package com.siteplain.service;

import com.siteplain.data.repository.SeoPageRepository;
import com.siteplain.data.repository.StatePageRepository;
import com.siteplain.domain.view.SitePageViewModel;
import com.siteplain.domain.view.StatePageViewModel;
import java.util.Locale;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class SeoPageService {

    private final SeoPageRepository seoPageRepository;
    private final StatePageRepository statePageRepository;

    public SeoPageService(SeoPageRepository seoPageRepository,
                          StatePageRepository statePageRepository) {
        this.seoPageRepository = seoPageRepository;
        this.statePageRepository = statePageRepository;
    }

    public Optional<SitePageViewModel> buildSitePage(String epaId) {
        return seoPageRepository.findByEpaId(epaId.toUpperCase(Locale.ROOT))
                .map(SitePageViewModel::new);
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
