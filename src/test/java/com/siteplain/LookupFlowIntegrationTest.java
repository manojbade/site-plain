package com.siteplain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.siteplain.domain.model.GeocodedAddress;
import com.siteplain.service.GeocodingService;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The lookup flow end to end with a stub geocoder: POST /lookup writes the one audit row and
 * redirects to an encrypted link; the page, its share link and its PDF link carry only that link.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "TEST_DATASOURCE_URL", matches = ".+")
class LookupFlowIntegrationTest {

    private static final String EPA_ID = "OHR000000009";
    private static final Pattern PDF_LINK = Pattern.compile("href=\"(/results/pdf\\?r=[A-Za-z0-9_-]+)\"");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private GeocodingService geocodingService;

    @BeforeEach
    void loadFixture() {
        cleanup();
        jdbcTemplate.update("""
                INSERT INTO npl_site_boundaries (epa_id, site_name, state_code, npl_status_code, epa_url, geom)
                VALUES (?, 'Flow Test Landfill', 'OH', 'F', 'https://www.epa.gov/superfund/flow-test',
                        ST_Multi(ST_GeomFromText('POLYGON((-84.06 39.82, -84.05 39.82, -84.05 39.83, -84.06 39.83, -84.06 39.82))', 4326)))
                """, EPA_ID);
        jdbcTemplate.update("""
                INSERT INTO npl_human_exposure (epa_id, humexposurestscode, npl_status, site_name, state_code)
                VALUES (?, 'HENC', 'F', 'Flow Test Landfill', 'OH')
                """, EPA_ID);
    }

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM npl_human_exposure WHERE epa_id = ?", EPA_ID);
        jdbcTemplate.update("DELETE FROM npl_site_boundaries WHERE epa_id = ?", EPA_ID);
    }

    @Test
    void lookupWritesOneAuditRowAndTheResultsLinkCarriesNoAddress() throws Exception {
        when(geocodingService.geocode(anyString())).thenReturn(new GeocodedAddress(
                "12 Flow Test Rd, Dayton, OH", "12 FLOW TEST RD, DAYTON, OH, 45431", "DAYTON", "OH", "45431",
                "census", 39.825, -84.055, true));
        int auditBefore = auditRows();

        String location = mockMvc.perform(post("/lookup").param("address", "12 Flow Test Rd, Dayton, OH"))
                .andExpect(status().is3xxRedirection())
                .andReturn().getResponse().getRedirectedUrl();
        assertThat(location).matches("/results\\?r=[A-Za-z0-9_-]+");
        assertThat(auditRows()).isEqualTo(auditBefore + 1);
        Map<String, Object> audit = jdbcTemplate.queryForMap(
                "SELECT state_code, result_count, geocoder_used, resolved FROM lookup_audit ORDER BY id DESC LIMIT 1");
        assertThat(audit).containsEntry("state_code", "OH").containsEntry("geocoder_used", "census")
                .containsEntry("resolved", true);
        assertThat(((Number) audit.get("result_count")).intValue()).isGreaterThanOrEqualTo(1);

        // Viewing (and reloading) the page shows the address from the link and writes nothing.
        String html = mockMvc.perform(get(location)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        mockMvc.perform(get(location)).andExpect(status().isOk());
        assertThat(html).contains("<h1 class=\"tt-page-title mb-3\">12 FLOW TEST RD, DAYTON, OH, 45431</h1>")
                .contains("Flow Test Landfill")
                .contains("id=\"share-btn\"");
        // The PDF link is the same encrypted link, not the address.
        Matcher pdf = PDF_LINK.matcher(html);
        assertThat(pdf.find()).isTrue();
        assertThat(pdf.group(1)).isEqualTo(location.replace("/results?", "/results/pdf?"));
        assertThat(html).doesNotContain("address=").doesNotContain("raw=").doesNotContain("?lat=").doesNotContain("&amp;lat=");

        byte[] report = mockMvc.perform(get(pdf.group(1))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(report, 0, 5, java.nio.charset.StandardCharsets.ISO_8859_1)).isEqualTo("%PDF-");
        assertThat(auditRows()).isEqualTo(auditBefore + 1);
    }

    @Test
    void anUnresolvedLookupShowsWhatWasTypedFromTheEncryptedLink() throws Exception {
        when(geocodingService.geocode(anyString())).thenReturn(GeocodedAddress.unresolved("zzqx nowhere"));
        int auditBefore = auditRows();

        String location = mockMvc.perform(post("/lookup").param("address", "zzqx nowhere"))
                .andExpect(status().is3xxRedirection())
                .andReturn().getResponse().getRedirectedUrl();
        assertThat(location).matches("/results\\?r=[A-Za-z0-9_-]+");
        assertThat(auditRows()).isEqualTo(auditBefore + 1);

        String html = mockMvc.perform(get(location)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(html).contains("Address could not be resolved").contains("zzqx nowhere");
        assertThat(auditRows()).isEqualTo(auditBefore + 1);
    }

    private int auditRows() {
        Integer count = jdbcTemplate.queryForObject("SELECT count(*) FROM lookup_audit", Integer.class);
        return count == null ? 0 : count;
    }
}
