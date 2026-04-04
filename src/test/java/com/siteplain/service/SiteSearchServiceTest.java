package com.siteplain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.siteplain.data.repository.SeoPageRepository;
import com.siteplain.domain.model.SeoPageData;
import com.siteplain.domain.view.SiteSearchSuggestion;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SiteSearchServiceTest {

    @Mock
    private SeoPageRepository seoPageRepository;

    @InjectMocks
    private SiteSearchService siteSearchService;

    @Test
    void suggest_returnsEmpty_whenQueryTooShort() {
        assertThat(siteSearchService.suggest("a")).isEmpty();
    }

    @Test
    void search_redirects_whenExactEpaIdExists() {
        when(seoPageRepository.findByEpaId("GA1234567890"))
                .thenReturn(Optional.of(seoPageData("GA1234567890", "Example Site")));

        SiteSearchService.SiteSearchResolution resolution = siteSearchService.search("ga1234567890");

        assertThat(resolution.shouldRedirect()).isTrue();
        assertThat(resolution.redirectEpaId()).isEqualTo("GA1234567890");
    }

    @Test
    void search_redirects_whenSingleExactSiteNameMatchExists() {
        when(seoPageRepository.findByEpaId("EXAMPLE SUPERFUND SITE")).thenReturn(Optional.empty());
        when(seoPageRepository.findExactSiteNameMatches("Example Superfund Site", 2))
                .thenReturn(List.of(suggestion("GA1234567890", "Example Superfund Site")));

        SiteSearchService.SiteSearchResolution resolution = siteSearchService.search("Example Superfund Site");

        assertThat(resolution.shouldRedirect()).isTrue();
        assertThat(resolution.redirectEpaId()).isEqualTo("GA1234567890");
    }

    @Test
    void search_returnsRankedMatches_whenMultipleMatchesExist() {
        when(seoPageRepository.findByEpaId("TEST")).thenReturn(Optional.empty());
        when(seoPageRepository.findExactSiteNameMatches("TEST", 2)).thenReturn(List.of());
        when(seoPageRepository.searchSuggestions("TEST", 25)).thenReturn(List.of(
                suggestion("GA1234567890", "Test One"),
                suggestion("GA9999999999", "Test Two")
        ));

        SiteSearchService.SiteSearchResolution resolution = siteSearchService.search("TEST");

        assertThat(resolution.shouldRedirect()).isFalse();
        assertThat(resolution.query()).isEqualTo("TEST");
        assertThat(resolution.matches()).hasSize(2);
    }

    private SeoPageData seoPageData(String epaId, String siteName) {
        return new SeoPageData(epaId, siteName, "GA", "HENC", "Human exposure is NOT currently under control", null, null);
    }

    private SiteSearchSuggestion suggestion(String epaId, String siteName) {
        return new SiteSearchSuggestion(epaId, siteName, "GA", "Human exposure is NOT currently under control");
    }
}
