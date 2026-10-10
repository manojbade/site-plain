package com.siteplain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.siteplain.data.loader.SeoPageCacheBuilder;
import com.siteplain.data.loader.StatePageCacheBuilder;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Renders real site, state and results pages against fixture rows and checks the text users and
 * search engines see.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "TEST_DATASOURCE_URL", matches = ".+")
class PageRenderingIntegrationTest {

    private static final List<String> IDS = List.of("OHR000000001", "OHR000000002");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private SeoPageCacheBuilder seoPageCacheBuilder;

    @Autowired
    private StatePageCacheBuilder statePageCacheBuilder;

    @BeforeEach
    void loadFixture() {
        cleanup();
        // Scheme-less, like three boundary rows in prod.
        insertBoundary("OHR000000001", "Render Test Landfill", "www.epa.gov/superfund/render-test-landfill");
        insertExposure("OHR000000001", "HENC", "OH", null);
        // Final per the feed, no published boundary.
        insertExposure("OHR000000002", "HEID", "OH", "www.epa.gov/superfund/render-test-unmapped");
        seoPageCacheBuilder.rebuild();
        statePageCacheBuilder.rebuild();
    }

    @AfterEach
    void cleanup() {
        String placeholders = String.join(",", IDS.stream().map(id -> "?").toList());
        jdbcTemplate.update("DELETE FROM site_seo_page_cache WHERE epa_id IN (" + placeholders + ")", IDS.toArray());
        jdbcTemplate.update("DELETE FROM npl_human_exposure WHERE epa_id IN (" + placeholders + ")", IDS.toArray());
        jdbcTemplate.update("DELETE FROM npl_site_boundaries WHERE epa_id IN (" + placeholders + ")", IDS.toArray());
        jdbcTemplate.update("DELETE FROM state_page_cache WHERE state_code = 'OH'");
    }

    @Test
    void sitePage_metaDescriptionNamesStateAndStatus() throws Exception {
        String html = mockMvc.perform(get("/site/OHR000000001"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).doesNotContain("viewModel.")
                .contains("<title>Render Test Landfill: EPA Superfund Site | Site Plain</title>")
                .contains("content=\"Render Test Landfill is an active EPA Superfund site in Ohio. "
                        + "Human exposure is NOT currently under control. "
                        + "Enter your address to see your distance from this site.\"");

        String unmapped = mockMvc.perform(get("/site/OHR000000002"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(unmapped).doesNotContain("viewModel.")
                .contains("in Ohio. Exposure status: insufficient data. "
                        + "EPA has not published a boundary for this site.\"");
    }

    @Test
    void epaProfileLinks_areAbsoluteOnSiteAndResultsPages() throws Exception {
        String site = mockMvc.perform(get("/site/OHR000000001"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(site).contains("href=\"https://www.epa.gov/superfund/render-test-landfill\"")
                .doesNotContain("href=\"www.epa.gov");

        String unmapped = mockMvc.perform(get("/site/OHR000000002"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(unmapped).contains("href=\"https://www.epa.gov/superfund/render-test-unmapped\"");

        String results = mockMvc.perform(get("/results")
                        .param("lat", "39.825")
                        .param("lng", "-84.055")
                        .param("address", "Render Test Address, Dayton, OH")
                        .param("state", "OH"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(results).contains("href=\"https://www.epa.gov/superfund/render-test-landfill\"")
                .doesNotContain("href=\"www.epa.gov");
    }

    @Test
    void resultsPage_showsApproximateLocationNoticeForCoarseMatches() throws Exception {
        String approximate = mockMvc.perform(get("/results")
                        .param("lat", "39.825")
                        .param("lng", "-84.055")
                        .param("address", "Dayton, Montgomery County, Ohio, United States")
                        .param("state", "OH")
                        .param("geocoder", "nominatim")
                        .param("approx", "city"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(approximate).contains("Approximate location.")
                .contains("only to the following\n      <span>city</span>:\n      "
                        + "<span>Dayton, Montgomery County, Ohio, United States</span>")
                .contains("approx=city");

        String exact = mockMvc.perform(get("/results")
                        .param("lat", "39.825")
                        .param("lng", "-84.055")
                        .param("address", "1 Main St, Dayton, OH")
                        .param("state", "OH"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(exact).doesNotContain("Approximate location.");
    }

    @Test
    void results_rejectInvalidCoordinatesWithoutAuditRow() throws Exception {
        int auditRows = auditRowCount();
        String[][] invalid = {
                {"NaN", "NaN"}, {"Infinity", "-84.05"}, {"39.82", "-Infinity"},
                {"95", "200"}, {"1e300", "-84.05"}, {"0", "0"}, {"51.5", "-0.12"}};
        for (String[] point : invalid) {
            String html = mockMvc.perform(get("/results")
                            .param("lat", point[0])
                            .param("lng", point[1])
                            .param("address", "1 Example Street, Woburn, MA 01801"))
                    .andExpect(status().isBadRequest())
                    .andReturn().getResponse().getContentAsString();
            assertThat(html).contains("Address could not be resolved")
                    .doesNotContain("No active EPA Superfund sites found");

            mockMvc.perform(get("/results/pdf")
                            .param("lat", point[0])
                            .param("lng", point[1])
                            .param("address", "1 Example Street, Woburn, MA 01801"))
                    .andExpect(status().isBadRequest());
        }
        assertThat(auditRowCount()).isEqualTo(auditRows);

        mockMvc.perform(get("/results")
                        .param("lat", "39.825")
                        .param("lng", "-84.055")
                        .param("address", "1 Main St, Dayton, OH"))
                .andExpect(status().isOk());
        assertThat(auditRowCount()).isEqualTo(auditRows + 1);
    }

    @Test
    void statePage_metaDescriptionNamesState() throws Exception {
        String html = mockMvc.perform(get("/state/OH"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).doesNotContain("viewModel.")
                .contains("content=\"2 active EPA Superfund sites in Ohio. "
                        + "Enter your address to see how close you are to any of them.\"");
    }

    private int auditRowCount() {
        Integer count = jdbcTemplate.queryForObject("SELECT count(*) FROM lookup_audit", Integer.class);
        return count == null ? 0 : count;
    }

    private void insertBoundary(String epaId, String siteName, String epaUrl) {
        jdbcTemplate.update("""
                INSERT INTO npl_site_boundaries
                  (epa_id, site_name, state_code, npl_status_code, epa_url, geom)
                VALUES
                  (?, ?, 'OH', 'F', ?, ST_Multi(ST_GeomFromText(
                    'POLYGON((-84.06 39.82, -84.05 39.82, -84.05 39.83, -84.06 39.83, -84.06 39.82))',
                    4326
                  )))
                """,
                epaId, siteName, epaUrl);
    }

    private void insertExposure(String epaId, String statusCode, String stateCode, String epaUrl) {
        jdbcTemplate.update("""
                INSERT INTO npl_human_exposure
                  (epa_id, humexposurestscode, npl_status, site_name, state_code, epa_url, loaded_at)
                VALUES (?, ?, 'F', ?, ?, ?, now())
                """,
                epaId, statusCode, "UNMAPPED RENDER TEST SITE", stateCode, epaUrl);
    }
}
