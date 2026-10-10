package com.siteplain.support;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Set;

public final class EpaUrls {

    private static final String SITE_SEARCH_URL = "https://cumulis.epa.gov/supercpad/cursites/srchsites.cfm?search_string=";
    private static final Set<String> PLACEHOLDER_SEGMENTS = Set.of("null", "undefined");

    private EpaUrls() {
    }

    /**
     * Returns an absolute https or http link for an upstream site profile URL, or null when the
     * value cannot be used. EPA files carry some URLs with no scheme (www.epa.gov/superfund/...),
     * which a browser treats as a relative link. Only .gov hosts are accepted. A path that ends in
     * a placeholder segment (www.epa.gov/superfund/null) is not a real page, so it is dropped too.
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
            if (!normalizedHost.endsWith(".gov") || hasPlaceholderPath(uri.getPath())) {
                return null;
            }
            return candidate;
        } catch (URISyntaxException ex) {
            return null;
        }
    }

    private static boolean hasPlaceholderPath(String path) {
        if (path == null) {
            return false;
        }
        String trimmed = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        String lastSegment = trimmed.substring(trimmed.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        return PLACEHOLDER_SEGMENTS.contains(lastSegment);
    }

    /** The site profile link to show: the normalized URL, else EPA's site search for this EPA ID. */
    public static String profileUrl(String url, String epaId) {
        String normalized = normalize(url);
        return normalized != null ? normalized : SITE_SEARCH_URL + epaId;
    }
}
