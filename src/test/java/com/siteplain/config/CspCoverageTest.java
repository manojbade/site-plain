package com.siteplain.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Every external origin the served templates and static files load from must be in the matching
 * Content-Security-Policy directive, or the browser blocks it. results-pdf.html is rendered on the
 * server by openhtmltopdf and never served to a browser, so it is not checked.
 */
class CspCoverageTest {

    private static final Path TEMPLATES = Path.of("src/main/resources/templates");
    private static final Path STATIC = Path.of("src/main/resources/static");
    private static final Pattern SCRIPT_SRC = Pattern.compile("<script[^>]*\\ssrc=\"(https://[^\"]+)\"");
    private static final Pattern LINK = Pattern.compile("<link\\b[^>]*>", Pattern.DOTALL);
    private static final Pattern HREF = Pattern.compile("href=\"(https://[^\"]+)\"");
    private static final Pattern CSS_IMPORT = Pattern.compile("@import url\\(\"(https://[^\"]+)\"\\)");
    private static final Pattern JS_URL = Pattern.compile("[`'\"](https://[^/`'\"]+)/");

    private final Map<String, String> directives = SecurityHeadersFilterTest.directives();

    @Test
    void templateScriptsAndStylesheets_areAllowed() throws IOException {
        List<String> scripts = new ArrayList<>();
        List<String> stylesheets = new ArrayList<>();
        for (Path template : files(TEMPLATES, ".html")) {
            if (template.endsWith("results-pdf.html")) {
                continue;
            }
            String html = Files.readString(template);
            collect(SCRIPT_SRC.matcher(html), scripts);
            Matcher links = LINK.matcher(html);
            while (links.find()) {
                if (links.group().contains("rel=\"stylesheet\"")) {
                    collect(HREF.matcher(links.group()), stylesheets);
                }
            }
        }
        assertThat(scripts).contains("https://www.googletagmanager.com", "https://api.mapbox.com");
        assertThat(stylesheets).contains("https://cdn.jsdelivr.net", "https://api.mapbox.com");
        assertThat(SecurityHeadersFilterTest.sources(directives, "script-src")).containsAll(scripts);
        assertThat(SecurityHeadersFilterTest.sources(directives, "style-src")).containsAll(stylesheets);
    }

    @Test
    void staticFileOrigins_areAllowed() throws IOException {
        List<String> imports = new ArrayList<>();
        List<String> requests = new ArrayList<>();
        for (Path css : files(STATIC, ".css")) {
            collect(CSS_IMPORT.matcher(Files.readString(css)), imports);
        }
        for (Path js : files(STATIC, ".js")) {
            collect(JS_URL.matcher(Files.readString(js)), requests);
        }
        assertThat(imports).containsExactly("https://fonts.googleapis.com");
        assertThat(requests).containsExactly("https://api.mapbox.com");
        assertThat(SecurityHeadersFilterTest.sources(directives, "style-src")).containsAll(imports);
        assertThat(SecurityHeadersFilterTest.sources(directives, "connect-src")).containsAll(requests);
    }

    private static void collect(Matcher matcher, List<String> origins) {
        while (matcher.find()) {
            URI uri = URI.create(matcher.group(1));
            origins.add(uri.getScheme() + "://" + uri.getHost());
        }
    }

    private static List<Path> files(Path root, String suffix) throws IOException {
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(p -> p.toString().endsWith(suffix)).toList();
        }
    }
}
