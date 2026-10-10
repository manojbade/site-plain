package com.siteplain.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.net.URI;

@Component
public class CanonicalHostFilter extends OncePerRequestFilter {

    static final String PROBE_PATH_PREFIX = "/actuator/health/";

    private final SiteProperties siteProperties;

    public CanonicalHostFilter(SiteProperties siteProperties) {
        this.siteProperties = siteProperties;
    }

    /**
     * The liveness and readiness probes answer on any host: Railway's healthcheck calls the container
     * directly over plain HTTP with the host healthcheck.railway.app and needs a 200 from that container.
     * A redirect to the public host would fail the check, or answer for the deployment already live.
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI().startsWith(PROBE_PATH_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (!siteProperties.isEnforceCanonicalHost()) {
            filterChain.doFilter(request, response);
            return;
        }

        URI baseUri = URI.create(siteProperties.getBaseUrl());
        String requestHost = request.getServerName();
        String requestScheme = request.getScheme();
        String canonicalHost = baseUri.getHost();
        String canonicalScheme = baseUri.getScheme();

        if (canonicalHost == null || canonicalScheme == null) {
            filterChain.doFilter(request, response);
            return;
        }

        boolean hostMatches = canonicalHost.equalsIgnoreCase(requestHost);
        boolean schemeMatches = canonicalScheme.equalsIgnoreCase(requestScheme);
        if (hostMatches && schemeMatches) {
            filterChain.doFilter(request, response);
            return;
        }

        String redirectUrl = UriComponentsBuilder.fromUriString(siteProperties.getBaseUrl())
                .replacePath(request.getRequestURI())
                .replaceQuery(request.getQueryString())
                .build(true)
                .toUriString();

        response.setStatus(HttpStatus.PERMANENT_REDIRECT.value());
        response.setHeader("Location", redirectUrl);
    }
}
