package com.siteplain.service;

import com.siteplain.data.repository.SeoPageRepository;
import com.siteplain.domain.view.SiteSearchSuggestion;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class SiteSearchService {

    private static final int SUGGESTION_LIMIT = 5;
    private static final int SEARCH_RESULT_LIMIT = 25;

    private final SeoPageRepository seoPageRepository;

    public SiteSearchService(SeoPageRepository seoPageRepository) {
        this.seoPageRepository = seoPageRepository;
    }

    public List<SiteSearchSuggestion> suggest(String query) {
        String normalized = normalize(query);
        if (normalized == null || normalized.length() < 2) {
            return List.of();
        }
        return seoPageRepository.searchSuggestions(normalized, SUGGESTION_LIMIT);
    }

    public SiteSearchResolution search(String query) {
        String normalized = normalize(query);
        if (normalized == null) {
            return SiteSearchResolution.empty();
        }

        String uppercaseQuery = normalized.toUpperCase(Locale.ROOT);
        if (seoPageRepository.findByEpaId(uppercaseQuery).isPresent()) {
            return SiteSearchResolution.redirect(uppercaseQuery);
        }

        List<SiteSearchSuggestion> exactNameMatches = seoPageRepository.findExactSiteNameMatches(normalized, 2);
        if (exactNameMatches.size() == 1) {
            return SiteSearchResolution.redirect(exactNameMatches.getFirst().epaId());
        }

        List<SiteSearchSuggestion> matches = seoPageRepository.searchSuggestions(normalized, SEARCH_RESULT_LIMIT);
        if (matches.size() == 1) {
            return SiteSearchResolution.redirect(matches.getFirst().epaId());
        }

        return SiteSearchResolution.results(normalized, matches);
    }

    private String normalize(String query) {
        if (!StringUtils.hasText(query)) {
            return null;
        }
        return query.trim();
    }

    public record SiteSearchResolution(
            String query,
            String redirectEpaId,
            List<SiteSearchSuggestion> matches
    ) {

        public static SiteSearchResolution empty() {
            return new SiteSearchResolution(null, null, List.of());
        }

        public static SiteSearchResolution redirect(String epaId) {
            return new SiteSearchResolution(null, epaId, List.of());
        }

        public static SiteSearchResolution results(String query, List<SiteSearchSuggestion> matches) {
            return new SiteSearchResolution(query, null, List.copyOf(matches));
        }

        public boolean shouldRedirect() {
            return redirectEpaId != null && !redirectEpaId.isBlank();
        }

        public boolean hasQuery() {
            return query != null && !query.isBlank();
        }
    }
}
