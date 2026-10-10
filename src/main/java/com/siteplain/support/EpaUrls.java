package com.siteplain.support;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

public final class EpaUrls {

    private static final String SITE_SEARCH_URL = "https://cumulis.epa.gov/supercpad/cursites/srchsites.cfm?search_string=";

    private EpaUrls() {
    }

    /**
     * Returns an absolute https or http link for an upstream site profile URL, or null when the
     * value cannot be used. EPA files carry some URLs with no scheme (www.epa.gov/superfund/...),
     * which a browser treats as a relative link. Only .gov hosts are accepted.
     */
    public static String normalize(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        String candidate = url.trim();
        String lower = candidate.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            if (lower.contains("://") || lower.startsWith("/")) {
                return null;
            }
            candidate = "https://" + candidate;
        }
        try {
            URI uri = new URI(candidate);
            String host = uri.getHost();
            if (host == null || uri.getUserInfo() != null) {
                return null;
            }
            String normalizedHost = host.toLowerCase(Locale.ROOT);
            return normalizedHost.endsWith(".gov") ? candidate : null;
        } catch (URISyntaxException ex) {
            return null;
        }
    }

    /** The site profile link to show: the normalized URL, else EPA's site search for this EPA ID. */
    public static String profileUrl(String url, String epaId) {
        String normalized = normalize(url);
        return normalized != null ? normalized : SITE_SEARCH_URL + epaId;
    }
}
