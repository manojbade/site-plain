package com.siteplain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.RequestDispatcher;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.stereotype.Controller;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Renders the shell pages (home, about, feedback, thank-you, error pages) and checks the markup
 * a strict Content-Security-Policy and the shared design depend on.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "TEST_DATASOURCE_URL", matches = ".+")
@ExtendWith(OutputCaptureExtension.class)
class ShellPagesIntegrationTest {

    /** A script tag without a src attribute, that is, an inline script block. */
    private static final Pattern INLINE_SCRIPT = Pattern.compile("<script(?![^>]*\\bsrc=)[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern STYLE_ATTRIBUTE = Pattern.compile("\\sstyle\\s*=", Pattern.CASE_INSENSITIVE);
    private static final Pattern STYLE_BLOCK = Pattern.compile("<style[\\s>]", Pattern.CASE_INSENSITIVE);
    private static final Pattern ANALYTICS_SCRIPT = Pattern.compile("<script src=\"/js/analytics(-[0-9a-f]{32})?\\.js\"></script>");
    private static final Pattern HOME_SCRIPT = Pattern.compile("<script src=\"/js/home(-[0-9a-f]{32})?\\.js\"></script>");
    private static final Pattern STYLESHEET = Pattern.compile("href=\"/css/site-plain(-[0-9a-f]{32})?\\.css\\?v=3\"");
    private static final String COMMENT_MARKER = "shell-pages-test comment 7f3c";
    private static final String EPA_POPULATION_PDF =
            "https://www.epa.gov/system/files/documents/2023-08/FY22%20Population%20Estimates%20Superfund%20Final.pdf";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM feedback WHERE comments LIKE ?", COMMENT_MARKER + "%");
    }

    @Test
    void shellPages_haveNoInlineScriptsStylesOrEmDashes() throws Exception {
        Map<String, String> pages = Map.of(
                "/", page("/", 200),
                "/about", page("/about", 200),
                "/feedback", page("/feedback", 200),
                "/feedback/thanks", page("/feedback/thanks", 200),
                "/no-such-page", page("/no-such-page", 404),
                "500", serverErrorPage());
        pages.forEach((path, html) -> {
            assertThat(INLINE_SCRIPT.matcher(html).find()).as("inline script on %s", path).isFalse();
            assertThat(STYLE_ATTRIBUTE.matcher(html).find()).as("style attribute on %s", path).isFalse();
            assertThat(STYLE_BLOCK.matcher(html).find()).as("style block on %s", path).isFalse();
            assertThat(html).as("em dash on %s", path).doesNotContain("\u2014").doesNotContain("&mdash;");
            assertThat(ANALYTICS_SCRIPT.matcher(html).find()).as("analytics script on %s", path).isTrue();
            assertThat(STYLESHEET.matcher(html).find()).as("stylesheet on %s", path).isTrue();
            assertThat(html).as("shared assets on %s", path)
                    .contains("integrity=\"sha384-QWTKZyjpPEjISv5WaRU9OFeRpok6YctnYmDr5pNlyT2bRjXh0JMhjY6hW+ALEwIH\"")
                    .doesNotContain("bootstrap.bundle")
                    .contains("class=\"bg-page\"");
            assertThat(html).as("header feedback link on %s", path)
                    .contains("class=\"tt-nav-button tt-btn-secondary\" href=\"/feedback\"")
                    .doesNotContain("href=\"/#feedback\"");
        });
    }

    @Test
    void home_leadsWithTheEpaFigureAndLabelsSitePlainNumbersAsOwnAnalysis() throws Exception {
        String html = page("/", 200);
        int epaFigure = html.indexOf("Approximately 23 million people live within 1 mile of a Superfund site");
        int ownFigure = html.indexOf("19.4 million Americans live within 1 mile of an active Superfund site.");
        assertThat(epaFigure).isPositive();
        assertThat(ownFigure).isGreaterThan(epaFigure);
        assertThat(html).contains("href=\"" + EPA_POPULATION_PDF + "\"")
                .contains("Population Surrounding 1,881 Superfund Sites</a>, updated July 2023.")
                .contains("357 congressional districts, counting DC and territorial delegate districts,")
                .doesNotContain("of 435")
                .contains("Source: Site Plain's own analysis, March 2026.")
                .contains("href=\"/about#methodology\"")
                .contains("<label class=\"form-label mb-2 tt-primary-input-label\" for=\"lookup-address\">")
                .contains("<label class=\"form-label mb-2 tt-primary-input-label\" for=\"site-search-query\">");
        assertThat(HOME_SCRIPT.matcher(html).find()).isTrue();
        assertThat(page("/about", 200)).contains("id=\"methodology\"");
    }

    @Test
    void feedbackPage_rendersTheFormWithContext() throws Exception {
        String general = page("/feedback", 200);
        assertThat(general).contains("action=\"/feedback\" method=\"post\"")
                .contains("<input type=\"hidden\" name=\"pageType\" value=\"general\">")
                .doesNotContain("name=\"epaId\"")
                .contains("name=\"comments\"")
                .contains("name=\"helpful\"")
                .contains("<label class=\"form-label tt-field-label mb-2\" for=\"feedback-message\">Message</label>");

        String fromSite = page("/feedback?pageType=site&epaId=ak1570028646", 200);
        assertThat(fromSite).contains("name=\"pageType\" value=\"site\"")
                .contains("name=\"epaId\" value=\"AK1570028646\"");

        String odd = page("/feedback?pageType=something-much-too-long&epaId=%3Cscript%3E", 200);
        assertThat(odd).contains("name=\"pageType\" value=\"general\"").doesNotContain("name=\"epaId\"");
    }

    @Test
    void feedbackPost_savesTheCommentAndShowsTheThankYouPage() throws Exception {
        mockMvc.perform(post("/feedback")
                        .param("pageType", "general")
                        .param("helpful", "true")
                        .param("comments", COMMENT_MARKER))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/feedback/thanks"));

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT page_type, helpful FROM feedback WHERE comments = ?", COMMENT_MARKER);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsEntry("page_type", "general").containsEntry("helpful", true);

        String thanks = page("/feedback/thanks", 200);
        assertThat(thanks).contains("<h1 class=\"h2 mb-3\">Thank you for your feedback.</h1>")
                .contains("class=\"tt-masthead\"")
                .contains("class=\"tt-footer\"");
    }

    @Test
    void feedbackPost_rejectsWhatAFormCannotSendWithA400AndSavesNothing() throws Exception {
        int before = feedbackRows();
        mockMvc.perform(post("/feedback").param("pageType", "x".repeat(21)).param("comments", COMMENT_MARKER))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/feedback").param("pageType", "nosuchpage").param("comments", COMMENT_MARKER))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/feedback").param("pageType", "site").param("epaId", "GA1234567890123456789")
                        .param("comments", COMMENT_MARKER))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/feedback").param("pageType", "site").param("epaId", "<script>")
                        .param("comments", COMMENT_MARKER))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/feedback").param("pageType", "general")
                        .param("comments", COMMENT_MARKER + "x".repeat(500)))
                .andExpect(status().isBadRequest());
        assertThat(feedbackRows()).isEqualTo(before);
    }

    @Test
    void feedbackPost_countsABrowserLineBreakAsOneCharacter() throws Exception {
        // 500 characters as the textarea counts them, sent with CRLF line breaks (510 on the wire).
        String typed = (COMMENT_MARKER + "\n" + "y".repeat(60) + "\n").repeat(5);
        typed = typed + "z".repeat(500 - typed.length());
        mockMvc.perform(post("/feedback").param("pageType", "site").param("epaId", "ga1234567890")
                        .param("comments", typed.replace("\n", "\r\n")))
                .andExpect(redirectedUrl("/feedback/thanks"));

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT page_type, epa_id, comments FROM feedback WHERE comments LIKE ?", COMMENT_MARKER + "%");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsEntry("page_type", "site").containsEntry("epa_id", "GA1234567890");
        assertThat((String) rows.get(0).get("comments")).hasSize(500).doesNotContain("\r");
    }

    @Test
    void feedbackPost_discardsBotsThatFillTheHoneypotButStillThanksThem() throws Exception {
        int before = feedbackRows();
        mockMvc.perform(post("/feedback")
                        .param("pageType", "general")
                        .param("comments", COMMENT_MARKER)
                        .param("website", "http://spam.example"))
                .andExpect(redirectedUrl("/feedback/thanks"));
        assertThat(feedbackRows()).isEqualTo(before);

        // Both forms carry the field, hidden from people and screen readers.
        assertThat(page("/feedback", 200)).containsPattern(
                "<div class=\"d-none\" aria-hidden=\"true\">\\s*<label for=\"feedback-website\">Leave this field empty</label>\\s*"
                        + "<input type=\"text\" id=\"feedback-website\" name=\"website\" value=\"\" tabindex=\"-1\" autocomplete=\"off\">");
    }

    @Test
    void lookupPost_overlongOrBlankAddressIsA400WithTheFormError() throws Exception {
        String html = mockMvc.perform(post("/lookup").param("address", "1 Main St ".repeat(21)))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();
        assertThat(html).contains("id=\"lookup-address\"").contains("class=\"text-danger small mt-2\"");
        mockMvc.perform(post("/lookup").param("address", " ")).andExpect(status().isBadRequest());
    }

    @Test
    void clientErrors_are4xxPagesWithOneWarnLineAndNoStackTrace(CapturedOutput output) throws Exception {
        String wrongMethod = mockMvc.perform(get("/lookup"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", "POST"))
                .andReturn().getResponse().getContentAsString();
        assertThat(wrongMethod).contains("Site Plain could not use this request").doesNotContain("Page not found");

        mockMvc.perform(post("/results")).andExpect(status().isMethodNotAllowed());
        String missingParam = mockMvc.perform(post("/feedback"))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();
        assertThat(missingParam).contains("Site Plain could not use this request");
        mockMvc.perform(get("/results").param("lat", "abc").param("lng", "-84.0"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/feedback").param("pageType", "general").param("helpful", "maybe"))
                .andExpect(status().isBadRequest());

        assertThat(output.getOut() + output.getErr())
                .contains("Client error 405 on GET /lookup (HttpRequestMethodNotSupportedException)")
                .contains("Client error 400 on POST /feedback (MissingServletRequestParameterException)")
                .doesNotContain("Unhandled application error")
                .doesNotContain("\tat org.springframework");
    }

    @Test
    void errorPath_isAPlainPageWithTheRealStatus() throws Exception {
        // Requested directly there is no error status: a missing page, not Boot's status 999 JSON.
        String direct = page("/error", 404);
        assertThat(direct).contains("Page not found").doesNotContain("999");

        String containerBadRequest = mockMvc.perform(get("/error").requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 400))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();
        assertThat(containerBadRequest).contains("Site Plain could not use this request");

        mockMvc.perform(get("/error").requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 500))
                .andExpect(status().isInternalServerError());
    }

    @Test
    void errorPages_sayNotFoundOnlyForMissingPages() throws Exception {
        String missing = page("/no-such-page", 404);
        assertThat(missing).contains("Page not found").contains("<p class=\"eyebrow mb-2\">404</p>");

        String serverError = serverErrorPage();
        assertThat(serverError).doesNotContain("Page not found")
                .doesNotContain("Not Found")
                .contains("Site Plain could not finish this request");
    }

    private int feedbackRows() {
        Integer count = jdbcTemplate.queryForObject("SELECT count(*) FROM feedback", Integer.class);
        return count == null ? 0 : count;
    }

    private String page(String path, int expectedStatus) throws Exception {
        return mockMvc.perform(get(path))
                .andExpect(status().is(expectedStatus))
                .andReturn().getResponse().getContentAsString();
    }

    /** A genuine server error, from a test-only endpoint, reaches the catch-all handler and its 500 page. */
    private String serverErrorPage() throws Exception {
        return mockMvc.perform(get(FailingController.PATH))
                .andExpect(status().isInternalServerError())
                .andReturn().getResponse().getContentAsString();
    }

    @TestConfiguration
    static class FailingControllerConfig {
        @Bean
        FailingController failingController() {
            return new FailingController();
        }
    }

    @Controller
    static class FailingController {
        static final String PATH = "/test-only/server-error";

        @GetMapping(PATH)
        String fail() {
            throw new IllegalStateException("test-only server error");
        }
    }
}
