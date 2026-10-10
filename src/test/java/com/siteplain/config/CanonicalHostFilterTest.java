package com.siteplain.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class CanonicalHostFilterTest {

    @Test
    void probePaths_answerOnRailwaysHealthcheckHost_withoutARedirect() throws Exception {
        for (String path : new String[] {"/actuator/health/readiness", "/actuator/health/liveness"}) {
            MockHttpServletResponse response = filter("healthcheck.railway.app", path);

            assertThat(response.getStatus()).as(path).isEqualTo(200);
            assertThat(response.getHeader("Location")).as(path).isNull();
        }
    }

    @Test
    void otherPaths_stillRedirectToTheCanonicalHost() throws Exception {
        MockHttpServletResponse page = filter("healthcheck.railway.app", "/site/GAD990855074");
        assertThat(page.getStatus()).isEqualTo(308);
        assertThat(page.getHeader("Location")).isEqualTo("https://site-plain.com/site/GAD990855074");

        // The aggregate health endpoint keeps its old behavior.
        MockHttpServletResponse health = filter("healthcheck.railway.app", "/actuator/health");
        assertThat(health.getStatus()).isEqualTo(308);
        assertThat(health.getHeader("Location")).isEqualTo("https://site-plain.com/actuator/health");
    }

    private static MockHttpServletResponse filter(String host, String path) throws Exception {
        SiteProperties properties = new SiteProperties();
        properties.setEnforceCanonicalHost(true);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setServerName(host);
        request.setScheme("http");
        MockHttpServletResponse response = new MockHttpServletResponse();
        new CanonicalHostFilter(properties).doFilter(request, response, new MockFilterChain());
        return response;
    }
}
