package com.siteplain.data.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.siteplain.domain.model.SeoPageData;
import com.siteplain.domain.view.SiteSearchSuggestion;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "TEST_DATASOURCE_URL", matches = ".+")
class SeoPageRepositoryIntegrationTest {

    @Autowired
    private SeoPageRepository seoPageRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM site_seo_page_cache WHERE epa_id IN ('GA1111111111', 'GA2222222222', 'NC3333333333')");
        jdbcTemplate.update("DELETE FROM npl_site_boundaries WHERE epa_id IN ('GA1111111111', 'GA2222222222', 'NC3333333333')");
    }

    @Test
    void searchSuggestions_ranksExactEpaIdFirst() {
        insertBoundaryAndSeo("GA1111111111", "Alpha Chemical Site", "GA");
        insertBoundaryAndSeo("GA2222222222", "Alpha Metals Site", "GA");

        List<SiteSearchSuggestion> matches = seoPageRepository.searchSuggestions("GA2222222222", 5);

        assertThat(matches).isNotEmpty();
        assertThat(matches.getFirst().epaId()).isEqualTo("GA2222222222");
    }

    @Test
    void searchSuggestions_matchesByFacilityName() {
        insertBoundaryAndSeo("GA1111111111", "Alpha Chemical Site", "GA");
        insertBoundaryAndSeo("NC3333333333", "Durham Chemical Site", "NC");

        List<SiteSearchSuggestion> matches = seoPageRepository.searchSuggestions("Durham", 5);

        assertThat(matches).extracting(SiteSearchSuggestion::epaId).contains("NC3333333333");
    }

    @Test
    void findExactSiteNameMatches_ignoresCase() {
        insertBoundaryAndSeo("GA1111111111", "Alpha Chemical Site", "GA");

        List<SiteSearchSuggestion> matches = seoPageRepository.findExactSiteNameMatches("alpha chemical site", 5);

        assertThat(matches).hasSize(1);
        assertThat(matches.getFirst().epaId()).isEqualTo("GA1111111111");
    }

    private void insertBoundaryAndSeo(String epaId, String siteName, String stateCode) {
        jdbcTemplate.update("""
                INSERT INTO npl_site_boundaries
                  (epa_id, site_name, state_code, npl_status_code, epa_url, geom)
                VALUES
                  (?, ?, ?, 'F', ?, ST_Multi(ST_GeomFromText(
                    'POLYGON((-84.40 33.77, -84.39 33.77, -84.39 33.78, -84.40 33.78, -84.40 33.77))',
                    4326
                  )))
                """,
                epaId,
                siteName,
                stateCode,
                "https://example.com/" + epaId);

        seoPageRepository.insertSiteCacheRow(new SeoPageData(
                epaId,
                siteName,
                stateCode,
                "HENC",
                "Human exposure is NOT currently under control",
                "Test pathway",
                "https://example.com/" + epaId
        ));
    }
}
