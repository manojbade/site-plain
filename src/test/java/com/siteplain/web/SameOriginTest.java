package com.siteplain.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class SameOriginTest {

    @Test
    void secFetchSiteDecidesWhenPresent() {
        assertThat(SameOrigin.isSameOriginPost(request("same-origin", null))).isTrue();
        assertThat(SameOrigin.isSameOriginPost(request("Same-Origin", "https://evil.example"))).isTrue();
        assertThat(SameOrigin.isSameOriginPost(request("same-site", "https://site-plain.com"))).isFalse();
        assertThat(SameOrigin.isSameOriginPost(request("cross-site", "https://site-plain.com"))).isFalse();
        assertThat(SameOrigin.isSameOriginPost(request("none", null))).isFalse();
    }

    @Test
    void originMustMatchSchemeHostAndPortWithoutSecFetchSite() {
        assertThat(SameOrigin.isSameOriginPost(request(null, "https://site-plain.com"))).isTrue();
        assertThat(SameOrigin.isSameOriginPost(request(null, "https://SITE-PLAIN.com:443"))).isTrue();
        assertThat(SameOrigin.isSameOriginPost(request(null, "http://site-plain.com"))).isFalse();
        assertThat(SameOrigin.isSameOriginPost(request(null, "https://site-plain.com:8443"))).isFalse();
        assertThat(SameOrigin.isSameOriginPost(request(null, "https://www.site-plain.com"))).isFalse();
        assertThat(SameOrigin.isSameOriginPost(request(null, "https://site-plain.com.evil.example"))).isFalse();
        assertThat(SameOrigin.isSameOriginPost(request(null, "null"))).isFalse();
        assertThat(SameOrigin.isSameOriginPost(request(null, "not a url"))).isFalse();
    }

    @Test
    void neitherHeaderIsNotABrowserForm() {
        assertThat(SameOrigin.isSameOriginPost(request(null, null))).isFalse();
        assertThat(SameOrigin.isSameOriginPost(request(" ", " "))).isFalse();
    }

    private static MockHttpServletRequest request(String fetchSite, String origin) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/lookup");
        request.setScheme("https");
        request.setServerName("site-plain.com");
        request.setServerPort(443);
        if (fetchSite != null) {
            request.addHeader("Sec-Fetch-Site", fetchSite);
        }
        if (origin != null) {
            request.addHeader("Origin", origin);
        }
        return request;
    }
}
