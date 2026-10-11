package com.siteplain.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Security headers on every response: pages, static files, JSON, the PDF, redirects, error pages and
 * the actuator probes. Runs right after Spring's ForwardedHeaderFilter (HIGHEST_PRECEDENCE), so
 * isSecure() already reflects the edge's X-Forwarded-Proto, and before everything else, so a filter
 * that answers early (the canonical-host redirect) still carries them. It also runs on the error
 * dispatch, which re-sets them on the container's error page.
 *
 * <p>The Content-Security-Policy has no 'unsafe-inline' and no 'unsafe-eval': pages have no inline
 * script, style block or style attribute (only results-pdf.html has a style block, and openhtmltopdf
 * renders it on the server). Each origin below is one the pages load from:
 * <ul>
 *   <li>www.googletagmanager.com: gtag.js (every page but /search/sites), and GA4's own requests.</li>
 *   <li>*.google-analytics.com and *.analytics.google.com: GA4 collection. www.google.com too: this
 *       GA4 property also sends each hit to www.google.com/g/collect (seen in Chrome 153, Oct 2026).</li>
 *   <li>cdn.jsdelivr.net: the Bootstrap 5.3.3 stylesheet (with SRI). No Bootstrap script.</li>
 *   <li>fonts.googleapis.com and fonts.gstatic.com: Public Sans, imported by site-plain.css.</li>
 *   <li>api.mapbox.com: Mapbox GL JS and its CSS on /results, the map's styles, sprites and glyphs,
 *       and the home page's address suggestions (Geocoding v6).</li>
 *   <li>*.tiles.mapbox.com and events.mapbox.com: map tiles and Mapbox GL's telemetry.</li>
 *   <li>blob: (workers, images) and data: (images): Mapbox GL's workers and images, Bootstrap's and
 *       Mapbox GL's inline SVG icons. Mapbox GL sets element styles through the CSSOM, which
 *       style-src does not restrict.</li>
 * </ul>
 * Violations are reported to /csp-report (CspReportController), which logs them, so report-only mode
 * shows in the app log what enforce mode would block. report-uri only: with report-to present Chrome
 * ignores report-uri and queues reports for the Reporting API, which delivered none in testing.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class SecurityHeadersFilter extends OncePerRequestFilter {

    static final String REPORT_PATH = "/csp-report";

    public static final String CONTENT_SECURITY_POLICY = String.join("; ",
            "default-src 'self'",
            "script-src 'self' https://www.googletagmanager.com https://api.mapbox.com",
            "style-src 'self' https://fonts.googleapis.com https://cdn.jsdelivr.net https://api.mapbox.com",
            "font-src https://fonts.gstatic.com",
            "img-src 'self' data: blob: https://www.googletagmanager.com https://*.google-analytics.com "
                    + "https://*.analytics.google.com https://www.google.com https://api.mapbox.com "
                    + "https://*.tiles.mapbox.com",
            "connect-src 'self' https://www.googletagmanager.com https://*.google-analytics.com "
                    + "https://*.analytics.google.com https://www.google.com https://api.mapbox.com "
                    + "https://*.tiles.mapbox.com https://events.mapbox.com",
            "worker-src blob:",
            "child-src blob:",
            "object-src 'none'",
            "base-uri 'self'",
            "form-action 'self'",
            "frame-ancestors 'none'",
            "report-uri " + REPORT_PATH);

    /** One year. No includeSubDomains (other subdomains were not checked) and no preload. */
    public static final String STRICT_TRANSPORT_SECURITY = "max-age=31536000";
    public static final String REFERRER_POLICY = "strict-origin-when-cross-origin";
    public static final String PERMISSIONS_POLICY = "accelerometer=(), camera=(), geolocation=(), gyroscope=(), "
            + "magnetometer=(), microphone=(), payment=(), usb=()";

    private final String cspHeaderName;

    public SecurityHeadersFilter(SecurityProperties properties) {
        this.cspHeaderName = properties.getCsp().getMode() == SecurityProperties.CspMode.REPORT_ONLY
                ? "Content-Security-Policy-Report-Only"
                : "Content-Security-Policy";
    }

    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        response.setHeader(cspHeaderName, CONTENT_SECURITY_POLICY);
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Referrer-Policy", REFERRER_POLICY);
        response.setHeader("Permissions-Policy", PERMISSIONS_POLICY);
        if (request.isSecure()) {
            // Browsers ignore HSTS received over plain HTTP; the edge redirects http:// to https://.
            response.setHeader("Strict-Transport-Security", STRICT_TRANSPORT_SECURITY);
        }
        filterChain.doFilter(request, response);
    }
}
