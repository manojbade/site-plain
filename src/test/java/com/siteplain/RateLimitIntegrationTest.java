package com.siteplain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
        "siteplain.limits.suggestions.refill=1h"
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
}
