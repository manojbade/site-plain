package com.siteplain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.siteplain.config.SecurityHeadersFilter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;

/** The security headers on every kind of response the app makes, and the CSP report endpoint. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "TEST_DATASOURCE_URL", matches = ".+")
@ExtendWith(OutputCaptureExtension.class)
class SecurityHeadersIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void everyKindOfResponse_carriesTheHeaders() throws Exception {
        assertHeaders(get("/").secure(true), 200);
        assertHeaders(get("/css/site-plain.css").secure(true), 200);
        assertHeaders(get("/robots.txt").secure(true), 200);
        assertHeaders(get("/api/site-search/suggestions").param("q", "ab").secure(true), 200);
        assertHeaders(get("/sitemap.xml").secure(true), 200);
        assertHeaders(get("/actuator/health/readiness").secure(true), 200);
        assertHeaders(get("/no-such-page").secure(true), 404);
        assertHeaders(get("/results").param("lat", "abc").param("lng", "1").secure(true), 400);
        assertHeaders(get("/lookup").secure(true), 405);
        assertHeaders(post("/feedback").param("pageType", "general").param("website", "bot").secure(true), 302);
    }

    @Test
    void plainHttp_hasNoHsts() throws Exception {
        MockHttpServletResponse response = mockMvc.perform(get("/")).andReturn().getResponse();
        assertThat(response.getHeader("Content-Security-Policy")).isEqualTo(SecurityHeadersFilter.CONTENT_SECURITY_POLICY);
        assertThat(response.getHeader("Strict-Transport-Security")).isNull();
    }

    @Test
    void cspReports_areLoggedWithoutTheQueryString(CapturedOutput output) throws Exception {
        String legacy = """
                {"csp-report": {"document-uri": "https://site-plain.com/results?lat=1&lng=2&address=12%20Elm%20Street",
                 "violated-directive": "script-src-elem", "effective-directive": "script-src-elem",
                 "blocked-uri": "inline", "disposition": "enforce"}}""";
        mockMvc.perform(post("/csp-report").contentType("application/csp-report").content(legacy))
                .andExpect(status().isNoContent());
        String reportingApi = """
                [{"type": "csp-violation", "url": "https://site-plain.com/about",
                  "body": {"documentURL": "https://site-plain.com/about?x=Elm", "effectiveDirective": "img-src",
                           "blockedURL": "https://tracker.example.net/pixel.gif?id=Elm", "disposition": "report"}}]""";
        mockMvc.perform(post("/csp-report").contentType("application/reports+json").content(reportingApi))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/csp-report").contentType(MediaType.TEXT_PLAIN).content("not json"))
                .andExpect(status().isNoContent());

        assertThat(output.getOut())
                .contains("Content-Security-Policy blocked script-src-elem for inline on /results")
                .contains("Content-Security-Policy would block img-src for https://tracker.example.net on /about")
                .doesNotContain("Elm");
    }

    private void assertHeaders(RequestBuilder request, int expectedStatus) throws Exception {
        MockHttpServletResponse response = mockMvc.perform(request).andReturn().getResponse();
        String path = response.getStatus() + " " + response.getContentType();
        assertThat(response.getStatus()).isEqualTo(expectedStatus);
        assertThat(response.getHeader("Content-Security-Policy")).as(path).isEqualTo(SecurityHeadersFilter.CONTENT_SECURITY_POLICY);
        assertThat(response.getHeader("X-Content-Type-Options")).as(path).isEqualTo("nosniff");
        assertThat(response.getHeader("X-Frame-Options")).as(path).isEqualTo("DENY");
        assertThat(response.getHeader("Referrer-Policy")).as(path).isEqualTo("strict-origin-when-cross-origin");
        assertThat(response.getHeader("Permissions-Policy")).as(path).isEqualTo(SecurityHeadersFilter.PERMISSIONS_POLICY);
        assertThat(response.getHeader("Strict-Transport-Security")).as(path).isEqualTo("max-age=31536000");
    }
}
