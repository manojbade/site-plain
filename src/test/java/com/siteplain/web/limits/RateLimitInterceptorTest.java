package com.siteplain.web.limits;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.HandlerMapping;

class RateLimitInterceptorTest {

    @Test
    void theLimiterKeyIsTheMappingPatternSpringMatched() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/results;x/pd%66");
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/results/pdf");

        assertThat(RateLimitInterceptor.matchedPath(request)).isEqualTo("/results/pdf");
    }

    @Test
    void withoutAPatternTheKeyIsTheDecodedPathWithoutParams() {
        assertThat(RateLimitInterceptor.matchedPath(new MockHttpServletRequest("POST", "/feedbac%6B;a=b")))
                .isEqualTo("/feedback");
        assertThat(RateLimitInterceptor.matchedPath(new MockHttpServletRequest("GET", "/%72esults;x/pdf;y=1")))
                .isEqualTo("/results/pdf");
    }
}
