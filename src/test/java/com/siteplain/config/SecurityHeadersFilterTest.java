package com.siteplain.config;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.DispatcherType;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class SecurityHeadersFilterTest {

    @Test
    void enforceMode_setsTheEnforcedPolicyAndTheOtherHeaders() throws Exception {
        MockHttpServletResponse response = filter(SecurityProperties.CspMode.ENFORCE, request(true));

        assertThat(response.getHeader("Content-Security-Policy")).isEqualTo(SecurityHeadersFilter.CONTENT_SECURITY_POLICY);
        assertThat(response.getHeader("Content-Security-Policy-Report-Only")).isNull();
        assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(response.getHeader("X-Frame-Options")).isEqualTo("DENY");
        assertThat(response.getHeader("Referrer-Policy")).isEqualTo("strict-origin-when-cross-origin");
        assertThat(response.getHeader("Permissions-Policy"))
                .contains("camera=()", "microphone=()", "geolocation=()", "payment=()", "usb=()");
        assertThat(response.getHeader("Strict-Transport-Security")).isEqualTo("max-age=31536000");
    }

    @Test
    void reportOnlyMode_sendsThePolicyAsReportOnly() throws Exception {
        MockHttpServletResponse response = filter(SecurityProperties.CspMode.REPORT_ONLY, request(true));

        assertThat(response.getHeader("Content-Security-Policy")).isNull();
        assertThat(response.getHeader("Content-Security-Policy-Report-Only"))
                .isEqualTo(SecurityHeadersFilter.CONTENT_SECURITY_POLICY);
        assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
    }

    @Test
    void cspMode_bindsFromTheValuesTheEnvironmentVariableTakes() {
        assertThat(bindMode("report-only")).isEqualTo(SecurityProperties.CspMode.REPORT_ONLY);
        assertThat(bindMode("enforce")).isEqualTo(SecurityProperties.CspMode.ENFORCE);
        assertThat(new SecurityProperties().getCsp().getMode()).isEqualTo(SecurityProperties.CspMode.ENFORCE);
    }

    @Test
    void hsts_onlyOnSecureRequests() throws Exception {
        assertThat(filter(SecurityProperties.CspMode.ENFORCE, request(false)).getHeader("Strict-Transport-Security")).isNull();
    }

    @Test
    void errorDispatch_isFilteredToo() throws Exception {
        SecurityHeadersFilter filter = filter(SecurityProperties.CspMode.ENFORCE);
        MockHttpServletRequest request = request(true);
        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        // The container's error dispatch reuses the request, already marked as filtered.
        request.setDispatcherType(DispatcherType.ERROR);
        MockHttpServletResponse errorResponse = new MockHttpServletResponse();
        filter.doFilter(request, errorResponse, new MockFilterChain());

        assertThat(errorResponse.getHeader("Content-Security-Policy")).isEqualTo(SecurityHeadersFilter.CONTENT_SECURITY_POLICY);
        assertThat(errorResponse.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
    }

    @Test
    void policy_isStrict() {
        Map<String, String> directives = directives();

        assertThat(SecurityHeadersFilter.CONTENT_SECURITY_POLICY)
                .doesNotContain("'unsafe-inline'")
                .doesNotContain("'unsafe-eval'");
        assertThat(directives).containsEntry("default-src", "'self'")
                .containsEntry("object-src", "'none'")
                .containsEntry("base-uri", "'self'")
                .containsEntry("form-action", "'self'")
                .containsEntry("frame-ancestors", "'none'")
                .containsEntry("worker-src", "blob:")
                .containsEntry("child-src", "blob:")
                .containsEntry("report-uri", "/csp-report");
        assertThat(sources(directives, "script-src"))
                .containsExactly("'self'", "https://www.googletagmanager.com", "https://api.mapbox.com");
        assertThat(sources(directives, "style-src"))
                .containsExactly("'self'", "https://fonts.googleapis.com", "https://cdn.jsdelivr.net", "https://api.mapbox.com");
        assertThat(sources(directives, "font-src")).containsExactly("https://fonts.gstatic.com");
        assertThat(sources(directives, "connect-src")).contains("'self'", "https://*.google-analytics.com",
                "https://*.analytics.google.com", "https://www.google.com", "https://api.mapbox.com",
                "https://*.tiles.mapbox.com", "https://events.mapbox.com");
        assertThat(sources(directives, "img-src")).contains("'self'", "data:", "blob:", "https://*.tiles.mapbox.com");
    }

    static Map<String, String> directives() {
        return Arrays.stream(SecurityHeadersFilter.CONTENT_SECURITY_POLICY.split(";\\s*"))
                .map(directive -> directive.split(" ", 2))
                .collect(Collectors.toMap(parts -> parts[0], parts -> parts[1]));
    }

    static List<String> sources(Map<String, String> directives, String name) {
        return Arrays.asList(directives.get(name).split(" "));
    }

    private static SecurityProperties.CspMode bindMode(String value) {
        Binder binder = new Binder(new MapConfigurationPropertySource(Map.of("siteplain.security.csp.mode", value)));
        return binder.bind("siteplain.security", SecurityProperties.class).get().getCsp().getMode();
    }

    private static MockHttpServletRequest request(boolean secure) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/");
        request.setSecure(secure);
        return request;
    }

    private static SecurityHeadersFilter filter(SecurityProperties.CspMode mode) {
        SecurityProperties properties = new SecurityProperties();
        properties.getCsp().setMode(mode);
        return new SecurityHeadersFilter(properties);
    }

    private static MockHttpServletResponse filter(SecurityProperties.CspMode mode, MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter(mode).doFilter(request, response, new MockFilterChain());
        return response;
    }
}
