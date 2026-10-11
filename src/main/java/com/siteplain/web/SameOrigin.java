package com.siteplain.web;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.Locale;

/**
 * Whether a POST came from one of this site's own pages, using the headers browsers attach to every
 * form submission. Sec-Fetch-Site (all current browsers) must be same-origin. Without it, Origin
 * (sent on POST by every browser that lacks Sec-Fetch-Site, back to Safari 15 and Firefox 70) must
 * name this request's own scheme, host and port. With neither header the request did not come from a
 * browser form (curl, a simple script), so it is refused too. No JavaScript is involved: a plain form
 * submission carries both headers.
 *
 * <p>The page's own Referrer-Policy (strict-origin-when-cross-origin) keeps the full origin in Origin
 * for same-origin posts. Behind Railway the scheme and host come from the forwarded headers
 * (server.forward-headers-strategy=framework), the same values the canonical host filter checks.
 */
public final class SameOrigin {

    private SameOrigin() {
    }

    public static boolean isSameOriginPost(HttpServletRequest request) {
        String fetchSite = request.getHeader("Sec-Fetch-Site");
        if (fetchSite != null && !fetchSite.isBlank()) {
            return "same-origin".equalsIgnoreCase(fetchSite.trim());
        }
        String origin = request.getHeader("Origin");
        if (origin == null || origin.isBlank()) {
            return false;
        }
        return matchesRequest(origin.trim(), request);
    }

    private static boolean matchesRequest(String origin, HttpServletRequest request) {
        URI uri;
        try {
            uri = URI.create(origin);
        } catch (IllegalArgumentException e) {
            return false;
        }
        if (uri.getScheme() == null || uri.getHost() == null) {
            // Includes the literal "null" origin (sandboxed frames, privacy redirects).
            return false;
        }
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        return scheme.equalsIgnoreCase(request.getScheme())
                && uri.getHost().equalsIgnoreCase(request.getServerName())
                && port(scheme, uri.getPort()) == port(request.getScheme().toLowerCase(Locale.ROOT), request.getServerPort());
    }

    private static int port(String scheme, int port) {
        if (port > 0) {
            return port;
        }
        return "https".equals(scheme) ? 443 : 80;
    }
}
