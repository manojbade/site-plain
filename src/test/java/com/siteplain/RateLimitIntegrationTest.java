package com.siteplain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** Per-client rate limits through the real web stack, with small limits so a test can reach them. */
@SpringBootTest(properties = {
        "siteplain.limits.rateLimitsEnabled=true",
        "siteplain.limits.search.capacity=3",
        "siteplain.limits.search.refill=1h",
        "siteplain.limits.feedback.capacity=2",
        "siteplain.limits.feedback.refill=1h",
        "siteplain.limits.suggestions.capacity=2",
        "siteplain.limits.suggestions.refill=1h",
        "siteplain.limits.lookup.capacity=2",
        "siteplain.limits.lookup.refill=1h",
        "siteplain.limits.pdf.capacity=2",
        "siteplain.limits.pdf.refill=1h"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "TEST_DATASOURCE_URL", matches = ".+")
class RateLimitIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void aClientOverItsLimitGetsA429WithRetryAfterAndTheBusyPage() throws Exception {
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(get("/search/sites").param("q", "zzqx").header("X-Real-IP", "203.0.113.10"))
                    .andExpect(status().isOk());
        }
        String html = mockMvc.perform(get("/search/sites").param("q", "zzqx").header("X-Real-IP", "203.0.113.10"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "3600"))
                .andReturn().getResponse().getContentAsString();
        assertThat(html).contains("Site Plain is busy right now").contains("<title>Busy | Site Plain</title>");

        // Another client, and pages without a limit, are unaffected.
        mockMvc.perform(get("/search/sites").param("q", "zzqx").header("X-Real-IP", "203.0.113.11"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/about").header("X-Real-IP", "203.0.113.10")).andExpect(status().isOk());
    }

    @Test
    void theClientIsTheEdgeHeaderNotAForwardedForChainItCanWrite() throws Exception {
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(get("/api/site-search/suggestions").param("q", "zz")
                            .header("X-Real-IP", "203.0.113.20")
                            .header("X-Forwarded-For", "198.51.100." + i))
                    .andExpect(status().isOk());
        }
        mockMvc.perform(get("/api/site-search/suggestions").param("q", "zz")
                        .header("X-Real-IP", "203.0.113.20")
                        .header("X-Forwarded-For", "198.51.100.99"))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void feedbackIsLimitedPerClientBeforeAnythingIsSaved() throws Exception {
        String marker = "rate-limit-test 51c2";
        try {
            for (int i = 0; i < 2; i++) {
                mockMvc.perform(post("/feedback").param("pageType", "general").param("comments", marker)
                                .header("X-Real-IP", "203.0.113.30"))
                        .andExpect(status().is3xxRedirection());
            }
            mockMvc.perform(post("/feedback").param("pageType", "general").param("comments", marker)
                            .header("X-Real-IP", "203.0.113.30"))
                    .andExpect(status().isTooManyRequests());
            Integer saved = jdbcTemplate.queryForObject("SELECT count(*) FROM feedback WHERE comments = ?", Integer.class, marker);
            assertThat(saved).isEqualTo(2);
        } finally {
            jdbcTemplate.update("DELETE FROM feedback WHERE comments = ?", marker);
        }
    }

    /**
     * Spring strips ;params and decodes the path before matching, so "/feedback;a=b" and
     * "/feedbac%6B" reach the same handler as "/feedback". They must draw on the same bucket.
     */
    @Test
    void pathParametersAndPercentEncodingDoNotEscapeTheFeedbackLimit() throws Exception {
        String marker = "rate-limit-test 7e09";
        String ip = "203.0.113.40";
        try {
            for (int i = 0; i < 2; i++) {
                mockMvc.perform(post("/feedback").param("pageType", "general").param("comments", marker)
                                .header("X-Real-IP", ip))
                        .andExpect(status().is3xxRedirection());
            }
            for (String variant : new String[] {"/feedback;a=b", "/feedback;x=1", "/feedbac%6B", "/%66eedback"}) {
                mockMvc.perform(post(URI.create(variant)).param("pageType", "general").param("comments", marker)
                                .header("X-Real-IP", ip))
                        .andExpect(status().isTooManyRequests());
            }
            Integer saved = jdbcTemplate.queryForObject("SELECT count(*) FROM feedback WHERE comments = ?", Integer.class, marker);
            assertThat(saved).isEqualTo(2);
        } finally {
            jdbcTemplate.update("DELETE FROM feedback WHERE comments = ?", marker);
        }
    }

    @Test
    void pathVariantsDoNotEscapeTheLookupLimit() throws Exception {
        String ip = "203.0.113.41";
        // A blank address is refused as a 400 by the controller, but still spends a token first.
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(post("/lookup").param("address", "").header("X-Real-IP", ip))
                    .andExpect(status().isBadRequest());
        }
        for (String variant : new String[] {"/lookup;x", "/lookup;a=b", "/%6Cookup", "/looku%70"}) {
            mockMvc.perform(post(URI.create(variant)).param("address", "").header("X-Real-IP", ip))
                    .andExpect(status().isTooManyRequests());
        }
    }

    @Test
    void pathVariantsDoNotEscapeThePdfLimit() throws Exception {
        String ip = "203.0.113.42";
        // Without a location the controller answers 400, but the token is spent first.
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(get("/results/pdf").header("X-Real-IP", ip))
                    .andExpect(status().isBadRequest());
        }
        for (String variant : new String[] {"/results/pdf;a=b", "/%72esults/pdf", "/results/%70df", "/results;x/pdf"}) {
            mockMvc.perform(get(URI.create(variant)).header("X-Real-IP", ip))
                    .andExpect(status().isTooManyRequests());
        }
        // The results page has its own bucket, so it is still open to this client.
        mockMvc.perform(get("/results").header("X-Real-IP", ip)).andExpect(status().isOk());
    }
}
