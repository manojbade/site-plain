package com.siteplain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.siteplain.config.SecurityHeadersFilter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** SITEPLAIN_SECURITY_CSP_MODE=report-only switches the policy to report-only without a code change. */
@SpringBootTest(properties = "SITEPLAIN_SECURITY_CSP_MODE=report-only")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "TEST_DATASOURCE_URL", matches = ".+")
class SecurityHeadersReportOnlyIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void reportOnlyMode_sendsTheSamePolicyAsReportOnly() throws Exception {
        MockHttpServletResponse response = mockMvc.perform(get("/about")).andReturn().getResponse();

        assertThat(response.getHeader("Content-Security-Policy")).isNull();
        assertThat(response.getHeader("Content-Security-Policy-Report-Only"))
                .isEqualTo(SecurityHeadersFilter.CONTENT_SECURITY_POLICY);
        assertThat(response.getHeader("X-Frame-Options")).isEqualTo("DENY");
    }
}
