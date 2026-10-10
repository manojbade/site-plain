package com.siteplain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.siteplain.data.loader.HumanExposureLoader;
import com.siteplain.data.loader.SeoPageCacheBuilder;
import com.siteplain.data.loader.StatePageCacheBuilder;
import com.siteplain.data.repository.HumanExposureRepository;
import com.siteplain.data.repository.NplBoundaryRepository;
import com.siteplain.domain.model.GeocodedAddress;
import com.siteplain.domain.model.NplSite;
import com.siteplain.service.NplLookupService;
import com.siteplain.service.SitemapService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * EPA's exposure feed nplstatus decides whether a site is active, with the boundary status as the
 * fallback, and Final sites with no published boundary still get pages, search and disclosure.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "TEST_DATASOURCE_URL", matches = ".+")
class ActiveSiteDefinitionIntegrationTest {

    private static final List<String> IDS = List.of(
            "GAT000000001", "GAT000000002", "GAT000000003", "GAT000000004",
            "NNT000000005", "OHT000000006", "GAT000000007", "GAT000000008");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private HumanExposureLoader humanExposureLoader;

    @Autowired
    private HumanExposureRepository humanExposureRepository;

    @Autowired
    private SeoPageCacheBuilder seoPageCacheBuilder;

    @Autowired
    private StatePageCacheBuilder statePageCacheBuilder;

    @Autowired
    private SitemapService sitemapService;

    @Autowired
    private NplLookupService nplLookupService;

    @Autowired
    private NplBoundaryRepository nplBoundaryRepository;

    @BeforeEach
    void loadFixture() throws Exception {
        cleanup();
        // Deleted per the feed even though the boundary file still says Final.
        insertBoundary("GAT000000001", "Deleted Test Site", "F");
        insertBoundary("GAT000000002", "Mapped Test Site", "F");
        // No exposure row, so the boundary status is used.
        insertBoundary("GAT000000007", "Boundary Only Test Site", "F");
        // Feed says Final, boundary file says Deleted.
        insertBoundary("GAT000000008", "Relisted Test Site", "D");

        String fixtureUrl = new ClassPathResource("fixtures/human-exposure-sample.json").getURI().toString();
        assertThat(humanExposureLoader.loadIntoStaging(fixtureUrl)).isEqualTo(7);
        humanExposureRepository.swapStagingToLive();
        seoPageCacheBuilder.rebuild();
        statePageCacheBuilder.rebuild();
        sitemapService.rebuild();
    }

    @AfterEach
    void cleanup() {
        String placeholders = String.join(",", IDS.stream().map(id -> "?").toList());
        jdbcTemplate.update("DELETE FROM site_seo_page_cache WHERE epa_id IN (" + placeholders + ")", IDS.toArray());
        jdbcTemplate.update("DELETE FROM npl_human_exposure WHERE epa_id IN (" + placeholders + ")", IDS.toArray());
        jdbcTemplate.update("DELETE FROM npl_human_exposure_staging WHERE epa_id IN (" + placeholders + ")", IDS.toArray());
        jdbcTemplate.update("DELETE FROM npl_site_boundaries WHERE epa_id IN (" + placeholders + ")", IDS.toArray());
        jdbcTemplate.update("DELETE FROM state_page_cache WHERE state_code IN ('GA', 'OH')");
    }

    @Test
    void activeSites_useFeedStatusWithBoundaryFallback() {
        List<String> active = jdbcTemplate.queryForList(
                "SELECT epa_id FROM active_npl_sites WHERE epa_id LIKE '__T00000000_' ORDER BY epa_id", String.class);

        assertThat(active).containsExactly(
                "GAT000000002", "GAT000000003", "GAT000000007", "GAT000000008", "NNT000000005", "OHT000000006");
        assertThat(nplBoundaryRepository.countActiveSites()).isGreaterThanOrEqualTo(6);
    }

    @Test
    void stagingGateQueries_matchTheFeedAndTheActiveSiteView() {
        // Staging still holds the fixture feed after the swap, so its projected active count must
        // equal the view's count, and the status counts are the fixture's (lowercase f uppercased).
        assertThat(humanExposureRepository.stagingStatusCounts()).containsExactly(Map.entry("D", 2), Map.entry("F", 5));
        assertThat(humanExposureRepository.stagingActiveSiteCount()).isEqualTo(nplBoundaryRepository.countActiveSites());

        jdbcTemplate.update("UPDATE npl_human_exposure_staging SET npl_status = 'FINAL' WHERE npl_status = 'F'");
        assertThat(humanExposureRepository.stagingStatusCounts()).containsKey("FINAL").doesNotContainKey("F");
        assertThat(humanExposureRepository.stagingActiveSiteCount())
                .isEqualTo(nplBoundaryRepository.countActiveSites() - 5);
    }

    @Test
    void exposureLoader_storesRowsWithoutBoundaryWithStateAndUrl() {
        Map<String, Object> unmapped = jdbcTemplate.queryForMap(
                "SELECT state_code, epa_url, npl_status FROM npl_human_exposure WHERE epa_id = 'GAT000000003'");
        assertThat(unmapped.get("state_code")).isEqualTo("GA");
        assertThat(unmapped.get("epa_url")).isEqualTo("https://www.epa.gov/superfund/unmapped-test-site");
        assertThat(unmapped.get("npl_status")).isEqualTo("F");

        assertThat(jdbcTemplate.queryForObject(
                "SELECT state_code FROM npl_human_exposure WHERE epa_id = 'NNT000000005'", String.class)).isNull();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT state_code FROM npl_human_exposure WHERE epa_id = 'OHT000000006'", String.class)).isEqualTo("OH");
    }

    @Test
    void lookup_excludesFeedDeletedSitesAndIncludesFallbackAndRelistedSites() {
        List<NplSite> sites = nplLookupService.findSitesNear(new GeocodedAddress(
                "test", "test", "Atlanta", "GA", "30303", "test", 33.775, -84.395, true)).sites();

        assertThat(sites).extracting(NplSite::getEpaId)
                .contains("GAT000000002", "GAT000000007", "GAT000000008")
                .doesNotContain("GAT000000001", "GAT000000003");
        assertThat(nplLookupService.findActiveSitesWithoutBoundary("ga"))
                .extracting(NplSite::getEpaId)
                .contains("GAT000000003")
                .doesNotContain("GAT000000001", "GAT000000002", "GAT000000004");
    }

    @Test
    void sitePage_rendersNoBoundaryNotice_andDeletedSiteIsGone() throws Exception {
        String unmapped = mockMvc.perform(get("/site/GAT000000003"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(unmapped).contains("UNMAPPED TEST SITE")
                .contains("EPA has not published a boundary for this site")
                .contains("https://www.epa.gov/superfund/unmapped-test-site")
                .doesNotContain("Check distance");

        String mapped = mockMvc.perform(get("/site/GAT000000002"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(mapped).contains("Check distance").doesNotContain("EPA has not published a boundary");

        mockMvc.perform(get("/site/GAT000000001")).andExpect(status().isNotFound());
        mockMvc.perform(get("/site/GAT000000004")).andExpect(status().isNotFound());
    }

    @Test
    void statePage_listsNoBoundarySitesInSeparateGroup() throws Exception {
        String html = mockMvc.perform(get("/state/GA"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        int groupStart = html.indexOf("Active sites with no published boundary");
        assertThat(groupStart).isPositive();
        assertThat(html.indexOf("/site/GAT000000002")).isBetween(0, groupStart);
        assertThat(html.indexOf("/site/GAT000000003")).isGreaterThan(groupStart);
        assertThat(html).doesNotContain("/site/GAT000000001").doesNotContain("/site/GAT000000004");
    }

    @Test
    void resultsPage_namesStateSitesWithoutBoundary() throws Exception {
        String html = mockMvc.perform(get("/results")
                        .param("lat", "33.775")
                        .param("lng", "-84.395")
                        .param("address", "Test Address, Atlanta, GA")
                        .param("state", "GA"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("Active sites in this state with no published boundary")
                .contains("UNMAPPED TEST SITE")
                .contains("/site/GAT000000002")
                .doesNotContain("/site/GAT000000001");
    }

    @Test
    void searchAndSitemap_includeNoBoundarySitesAndExcludeDeletedSites() throws Exception {
        String suggestions = mockMvc.perform(get("/api/site-search/suggestions").param("q", "UNMAPPED"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(suggestions).contains("GAT000000003").doesNotContain("GAT000000004");

        mockMvc.perform(get("/search/sites").param("q", "GAT000000003"))
                .andExpect(status().is3xxRedirection());

        String sitemap = mockMvc.perform(get("/sitemap.xml"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(sitemap).contains("/site/GAT000000003").contains("/site/OHT000000006")
                .doesNotContain("/site/GAT000000001");
    }

    private void insertBoundary(String epaId, String siteName, String statusCode) {
        jdbcTemplate.update("""
                INSERT INTO npl_site_boundaries
                  (epa_id, site_name, state_code, npl_status_code, epa_url, geom)
                VALUES
                  (?, ?, 'GA', ?, ?, ST_Multi(ST_GeomFromText(
                    'POLYGON((-84.40 33.77, -84.39 33.77, -84.39 33.78, -84.40 33.78, -84.40 33.77))',
                    4326
                  )))
                """,
                epaId, siteName, statusCode, "https://example.com/" + epaId);
    }
}
