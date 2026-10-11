package com.siteplain.web.limits;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class ClientAddressTest {

    @Test
    void usesTheEdgeHeaderOverThePeerAddress() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.7");
        request.addHeader("X-Real-IP", "203.0.113.9");
        request.addHeader("X-Forwarded-For", "198.51.100.1, 203.0.113.9");
        request.addHeader("CF-Connecting-IP", "198.51.100.1");

        assertThat(ClientAddress.of(request, "X-Real-IP")).isEqualTo("v4:203.0.113.9");
    }

    @Test
    void fallsBackToThePeerWhenTheHeaderIsMissingOrNotAnAddress() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        assertThat(ClientAddress.of(request, "X-Real-IP")).isEqualTo("v4:127.0.0.1");

        request.addHeader("X-Real-IP", "not-an-ip");
        assertThat(ClientAddress.of(request, "X-Real-IP")).isEqualTo("v4:127.0.0.1");
    }

    @Test
    void groupsIpv6ByTheFirst64Bits() {
        assertThat(ClientAddress.normalize("2001:db8:1:2:aaaa::1")).isEqualTo("v6:20010db800010002");
        assertThat(ClientAddress.normalize("2001:db8:1:2:bbbb::9")).isEqualTo("v6:20010db800010002");
        assertThat(ClientAddress.normalize("[2001:db8:1:3::1]")).isEqualTo("v6:20010db800010003");
        assertThat(ClientAddress.normalize("::ffff:192.0.2.5")).isEqualTo("v4:192.0.2.5");
    }

    @Test
    void acceptsOnlyIpLiteralsAndNeverResolvesNames() {
        assertThat(ClientAddress.normalize("256.1.1.1")).isNull();
        assertThat(ClientAddress.normalize("example.com")).isNull();
        assertThat(ClientAddress.normalize("abcd")).isNull();
        assertThat(ClientAddress.normalize("1.2.3.4, 5.6.7.8")).isNull();
        assertThat(ClientAddress.normalize("")).isNull();
        assertThat(ClientAddress.normalize(null)).isNull();
    }
}
