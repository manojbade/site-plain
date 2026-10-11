package com.siteplain.web.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class CspReportControllerTest {

    private final CspReportController controller = new CspReportController(new ObjectMapper());

    @Test
    void reportUriFormat_keepsTheDirectiveNameAndDropsTheQuery() {
        List<CspReportController.Violation> found = parse("""
                {"csp-report": {"document-uri": "https://site-plain.com/results?address=1%20Main",
                 "violated-directive": "style-src-elem 'self' https://cdn.jsdelivr.net",
                 "blocked-uri": "https://evil.example.com:8443/x.css?q=1", "disposition": "enforce"}}""");

        assertThat(found).containsExactly(new CspReportController.Violation(
                "style-src-elem", "https://evil.example.com:8443", "/results", "blocked"));
    }

    @Test
    void reportingApiFormat_readsEachCspViolation() {
        List<CspReportController.Violation> found = parse("""
                [{"type": "deprecation", "body": {}},
                 {"type": "csp-violation", "body": {"documentURL": "https://site-plain.com/", "effectiveDirective": "connect-src",
                  "blockedURL": "https://www.google.com/g/collect?v=2", "disposition": "report"}}]""");

        assertThat(found).containsExactly(new CspReportController.Violation(
                "connect-src", "https://www.google.com", "/", "would block"));
    }

    @Test
    void unknownDirectivesKeywordsAndJunk_areReducedToSafeValues() {
        assertThat(parse("""
                {"csp-report": {"document-uri": "not a url", "effective-directive": "x\\nFAKE LOG LINE",
                 "blocked-uri": "eval\\r\\nINJECTED"}}"""))
                .containsExactly(new CspReportController.Violation("other", "evalINJECTED", "(unknown page)", "blocked"));
        assertThat(CspReportController.blockedSource("blob:https://site-plain.com/1234")).isEqualTo("blob");
        assertThat(CspReportController.blockedSource("")).isEqualTo("(none)");
        assertThat(parse("not json")).isEmpty();
        assertThat(parse("{}")).isEmpty();
        assertThat(parse("")).isEmpty();
    }

    private List<CspReportController.Violation> parse(String json) {
        return controller.parse(json.getBytes(StandardCharsets.UTF_8));
    }
}
