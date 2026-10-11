package com.siteplain.web.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.siteplain.support.ThrottledLog;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Receives the browser's Content-Security-Policy violation reports, both the report-uri format
 * (application/csp-report, one {"csp-report": {...}}) and the Reporting API format
 * (application/reports+json, an array of {"type": "csp-violation", "body": {...}}), and logs each as
 * one WARN line: the directive, the blocked origin (or a keyword such as inline) and the page path.
 * Never the query string, which on old results links holds an address.
 *
 * <p>Anyone can post here, so nothing is stored, the body is capped, every logged value is reduced
 * to a short safe form, and the log is throttled to one line a minute per directive (with a count).
 * Always 204.
 */
@RestController
public class CspReportController {

    private static final Logger log = LoggerFactory.getLogger(CspReportController.class);

    static final int MAX_BODY_BYTES = 16 * 1024;
    private static final int MAX_REPORTS = 20;
    private static final Set<String> DIRECTIVES = Set.of(
            "default-src", "script-src", "script-src-elem", "script-src-attr", "style-src", "style-src-elem",
            "style-src-attr", "font-src", "img-src", "connect-src", "worker-src", "child-src", "frame-src",
            "object-src", "base-uri", "form-action", "frame-ancestors", "manifest-src", "media-src");
    private static final Pattern SAFE = Pattern.compile("[^A-Za-z0-9._:/*-]");

    private final ObjectMapper objectMapper;
    private final ThrottledLog violations = new ThrottledLog(log, 60_000, System::currentTimeMillis);

    public CspReportController(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @PostMapping("/csp-report")
    public ResponseEntity<Void> report(HttpServletRequest request) {
        for (Violation violation : parse(readCapped(request))) {
            violations.warn(violation.directive(), "Content-Security-Policy " + violation.disposition()
                    + " " + violation.directive() + " for " + violation.blocked() + " on " + violation.path());
        }
        return ResponseEntity.noContent().build();
    }

    record Violation(String directive, String blocked, String path, String disposition) {
    }

    List<Violation> parse(byte[] body) {
        List<Violation> found = new ArrayList<>();
        if (body.length == 0) {
            return found;
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (IOException e) {
            return found;
        }
        if (root == null) {
            return found;
        }
        if (root.has("csp-report")) {
            JsonNode report = root.get("csp-report");
            found.add(violation(text(report, "effective-directive", "violated-directive"),
                    text(report, "blocked-uri"), text(report, "document-uri"), text(report, "disposition")));
        } else if (root.isArray()) {
            for (JsonNode entry : root) {
                if (found.size() >= MAX_REPORTS) {
                    break;
                }
                JsonNode reportBody = entry.path("body");
                if ("csp-violation".equals(entry.path("type").asText()) && reportBody.isObject()) {
                    found.add(violation(text(reportBody, "effectiveDirective"), text(reportBody, "blockedURL"),
                            text(reportBody, "documentURL"), text(reportBody, "disposition")));
                }
            }
        }
        return found;
    }

    private static Violation violation(String directive, String blocked, String document, String disposition) {
        // violated-directive can carry the whole directive with its sources; keep the name.
        String name = directive.strip().split("\\s+")[0].toLowerCase(Locale.ROOT);
        return new Violation(DIRECTIVES.contains(name) ? name : "other",
                blockedSource(blocked), pathOf(document), "report".equals(disposition) ? "would block" : "blocked");
    }

    /** The blocked URL's scheme and host, or the keyword the browser sent (inline, eval, data, blob). */
    static String blockedSource(String blocked) {
        String value = blocked.strip();
        if (value.isEmpty()) {
            return "(none)";
        }
        try {
            URI uri = URI.create(value);
            if (uri.getScheme() != null && uri.getHost() != null) {
                return safe(uri.getScheme() + "://" + uri.getHost() + (uri.getPort() == -1 ? "" : ":" + uri.getPort()));
            }
            if (uri.getScheme() != null) {
                return safe(uri.getScheme());
            }
        } catch (IllegalArgumentException e) {
            // Not a URL: a keyword, or junk; reduced below.
        }
        return safe(value);
    }

    /** The page's path, without its query string. */
    static String pathOf(String document) {
        try {
            String path = URI.create(document.strip()).getRawPath();
            return path == null || path.isEmpty() ? "(unknown page)" : safe(path);
        } catch (IllegalArgumentException e) {
            return "(unknown page)";
        }
    }

    private static String safe(String value) {
        String cleaned = SAFE.matcher(value).replaceAll("");
        return cleaned.length() > 80 ? cleaned.substring(0, 80) : cleaned;
    }

    private static String text(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.get(field);
            if (value != null && value.isTextual() && !value.asText().isBlank()) {
                return value.asText();
            }
        }
        return "";
    }

    private static byte[] readCapped(HttpServletRequest request) {
        try (InputStream in = request.getInputStream()) {
            byte[] body = in.readNBytes(MAX_BODY_BYTES + 1);
            // A body over the cap is ignored rather than parsed in part.
            return body.length > MAX_BODY_BYTES ? new byte[0] : body;
        } catch (IOException e) {
            return new byte[0];
        }
    }
}
